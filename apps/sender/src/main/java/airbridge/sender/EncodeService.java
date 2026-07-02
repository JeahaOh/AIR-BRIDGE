package airbridge.sender;

import airbridge.common.ConsoleSupport;
import airbridge.common.QrPayloadSupport;
import airbridge.common.RelativePathSupport;
import airbridge.common.fountain.LtFountain;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

final class EncodeService {
    // reencode re-emits a failed file's whole stream with a larger repair margin than the
    // first pass, since that pass already came up short.
    private static final double REENCODE_REPAIR_OVERHEAD = 1.0;

    private final QrImageWriter qrImageWriter;
    private final int chunkDataSize;
    private final boolean convertXlsxToCsv;
    private final boolean convertOfficeToText;
    private final boolean folderStructure;
    private final int filesPerFolder;
    private final int encodeWorkers;
    private final double repairOverhead;

    EncodeService(QrImageWriter qrImageWriter,
                     int chunkDataSize,
                     boolean convertXlsxToCsv,
                     boolean convertOfficeToText,
                     boolean folderStructure,
                     int filesPerFolder) {
        this(qrImageWriter, chunkDataSize, convertXlsxToCsv, convertOfficeToText,
                folderStructure, filesPerFolder, SenderDefaults.DEFAULT_ENCODE_WORKERS,
                SenderDefaults.DEFAULT_REPAIR_OVERHEAD);
    }

    EncodeService(QrImageWriter qrImageWriter,
                     int chunkDataSize,
                     boolean convertXlsxToCsv,
                     boolean convertOfficeToText,
                     boolean folderStructure,
                     int filesPerFolder,
                     int encodeWorkers,
                     double repairOverhead) {
        if (chunkDataSize < 1) {
            throw new IllegalArgumentException("chunkDataSize must be >= 1");
        }
        if (filesPerFolder < 1) {
            throw new IllegalArgumentException("filesPerFolder must be >= 1");
        }
        if (repairOverhead < 0) {
            throw new IllegalArgumentException("repairOverhead must be >= 0");
        }
        this.qrImageWriter = qrImageWriter;
        this.chunkDataSize = chunkDataSize;
        this.convertXlsxToCsv = convertXlsxToCsv;
        this.convertOfficeToText = convertOfficeToText;
        this.folderStructure = folderStructure;
        this.filesPerFolder = filesPerFolder;
        this.encodeWorkers = Math.max(1, encodeWorkers);
        this.repairOverhead = repairOverhead;
    }

    EncodeSummary encode(Path srcPath,
                            Path outPath,
                            Path rootPath,
                            List<String> targetExtensions,
                            List<String> skipDirs,
                            List<String> excludePaths,
                            EncodeListener listener) throws Exception {
        return encode(srcPath, outPath, rootPath, targetExtensions, skipDirs, excludePaths, listener, () -> false);
    }

    EncodeSummary encode(Path srcPath,
                            Path outPath,
                            Path rootPath,
                            List<String> targetExtensions,
                            List<String> skipDirs,
                            List<String> excludePaths,
                            EncodeListener listener,
                            BooleanSupplier cancelled) throws Exception {
        EncodeListener effectiveListener = listener != null ? listener : line -> { };
        BooleanSupplier effectiveCancelled = cancelled != null ? cancelled : () -> false;
        requireSourceUnderRoot(srcPath, rootPath);
        List<Path> sourceFiles = SourceCollector.collectSourceFiles(srcPath, targetExtensions, skipDirs, excludePaths);
        if (sourceFiles.isEmpty()) {
            return new EncodeSummary(0, 0, 0, null);
        }

        // Per-chunk QR generation (build payload + render + PNG write) is CPU-bound and
        // independent, so it runs on a shared pool. filePermits bounds how many source files
        // are open at once (temp file + channel), and a file's plan is closed by its last chunk.
        int workers = Math.max(1, encodeWorkers);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        Semaphore filePermits = new Semaphore(workers);
        AtomicInteger pngCounter = new AtomicInteger();
        AtomicInteger totalQrCount = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean cancelledObserved = new AtomicBoolean(false);
        Queue<Path> createdFiles = new ConcurrentLinkedQueue<>();
        Set<Path> createdDirs = ConcurrentHashMap.newKeySet();

        int totalFileCount = 0;
        long totalOrigBytes = 0;
        Set<String> plannedRelPaths = new HashSet<>();
        StringBuilder manifest = new StringBuilder();
        manifest.append("SOURCE : ").append(srcPath).append("\n");
        manifest.append("DATE   : ").append(new Date()).append("\n");
        manifest.append(ConsoleSupport.line('=', 60)).append("\n\n");

        boolean aborted = false;
        try {
            createDirectoryConcurrent(outPath, createdDirs);

            for (int fi = 0; fi < sourceFiles.size(); fi++) {
                if (effectiveCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    cancelledObserved.set(true);
                    aborted = true;
                    break;
                }
                if (failure.get() != null) {
                    aborted = true;
                    break;
                }
                Path file = sourceFiles.get(fi);
                String sourceRelPath = rootPath.relativize(file).toString().replace('\\', '/');

                String origName = file.getFileName().toString();
                String origExtLower = detectExtension(origName);
                if (".xls".equals(origExtLower)) {
                    effectiveListener.onLog("");
                    effectiveListener.onLog("  [WARN] .xls(구형 바이너리 포맷)는 자동 CSV 변환 미지원. 원본 그대로 인코딩합니다.");
                }

                FileEncodingPlan plan = FileEncodingPlan.fromSourceFile(
                        file, sourceRelPath, convertXlsxToCsv, convertOfficeToText, chunkDataSize);
                boolean submitted = false;
                try {
                    // Office conversion rewrites the payload relPath (a.xlsx -> a.csv), so two
                    // source files can land on one relPath; their interleaved symbol streams
                    // would corrupt each other at decode (first-seen wins, the rest become
                    // errors). Encode the first and skip the rest loudly.
                    if (!plannedRelPaths.add(plan.relPath())) {
                        effectiveListener.onLog("");
                        effectiveListener.onLog(String.format(
                                "  [WARN] 중복 relPath 건너뜀: %s (%s — 변환 결과가 이미 인코딩된 경로와 겹칩니다)",
                                plan.relPath(), sourceRelPath));
                        manifest.append(String.format("[SKIP] %s -> %s (중복 relPath)\n",
                                sourceRelPath, plan.relPath()));
                        continue;
                    }
                    totalOrigBytes += plan.fileSize();
                    final int totalSymbols = symbolCount(plan.totalChunks());

                    effectiveListener.onLog("");
                    if (plan.convertedType() != null) {
                        effectiveListener.onLog(String.format("[FILE %d/%d] %s (%s 변환)",
                                fi + 1, sourceFiles.size(), plan.relPath(), plan.convertedType()));
                        effectiveListener.onLog(String.format("  변환후: %,d bytes -> 압축(gzip): %,d bytes -> QR %d장(소스 %d + 복구 %d)",
                                plan.fileSize(), plan.encodedSize(), totalSymbols, plan.totalChunks(), totalSymbols - plan.totalChunks()));
                    } else {
                        effectiveListener.onLog(String.format("[FILE %d/%d] %s", fi + 1, sourceFiles.size(), plan.relPath()));
                        effectiveListener.onLog(String.format("  원본: %,d bytes -> 압축(gzip): %,d bytes -> QR %d장(소스 %d + 복구 %d)",
                                plan.fileSize(), plan.encodedSize(), totalSymbols, plan.totalChunks(), totalSymbols - plan.totalChunks()));
                    }

                    Path fileOutDir = null;
                    if (folderStructure) {
                        Path relDir = srcPath.relativize(file).getParent();
                        fileOutDir = (relDir != null) ? outPath.resolve(relDir) : outPath;
                        createDirectoryConcurrent(fileOutDir, createdDirs);
                    }
                    final Path folderModeOutDir = fileOutDir;

                    manifest.append(String.format("[%s] %,d bytes -> QR %d장 (소스 %d, hash: %s)\n",
                            plan.relPath(), plan.fileSize(), totalSymbols, plan.totalChunks(), plan.fileHash().substring(0, 16)));

                    try {
                        filePermits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancelledObserved.set(true);
                        aborted = true;
                        closeQuietly(plan);
                        break;
                    }

                    final FileEncodingPlan planRef = plan;
                    AtomicInteger remaining = new AtomicInteger(totalSymbols);
                    for (int i = 0; i < totalSymbols; i++) {
                        final int esi = i;
                        pool.execute(() -> {
                            try {
                                if (failure.get() != null) {
                                    return;
                                }
                                if (effectiveCancelled.getAsBoolean()) {
                                    cancelledObserved.set(true);
                                    return;
                                }
                                writeSymbol(planRef, esi, totalSymbols, folderModeOutDir,
                                        outPath, pngCounter, createdDirs, createdFiles);
                                totalQrCount.incrementAndGet();
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            } finally {
                                if (remaining.decrementAndGet() == 0) {
                                    closeQuietly(planRef);
                                    filePermits.release();
                                }
                            }
                        });
                    }
                    submitted = true;
                    totalFileCount++;
                } finally {
                    if (!submitted) {
                        closeQuietly(plan);
                    }
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            aborted = true;
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                cancelledObserved.set(true);
                aborted = true;
            }
        }

        Throwable failed = failure.get();
        if (failed != null) {
            cleanupCancelledOutput(createdFiles, createdDirs, effectiveListener, "[FAILED]");
            if (failed instanceof Exception ex) {
                throw ex;
            }
            if (failed instanceof Error er) {
                throw er;
            }
            throw new RuntimeException(failed);
        }
        if (aborted || cancelledObserved.get() || effectiveCancelled.getAsBoolean()) {
            cleanupCancelledOutput(createdFiles, createdDirs, effectiveListener, "[CANCELLED]");
            throw new CancellationException("encode cancelled");
        }

        manifest.append("\n").append(ConsoleSupport.line('=', 60)).append("\n");
        manifest.append(String.format("총 파일: %d개 / 총 QR: %d장 / 총 원본: %,d bytes\n",
                totalFileCount, totalQrCount.get(), totalOrigBytes));

        Path manifestPath = outPath.resolve("_manifest.txt");
        Files.write(manifestPath, manifest.toString().getBytes(StandardCharsets.UTF_8));
        createdFiles.add(manifestPath);

        return new EncodeSummary(totalQrCount.get(), totalFileCount, totalOrigBytes, manifestPath);
    }

    // Total fountain symbols to emit for a k-source block: the k systematic symbols plus a
    // repair margin for the lossy one-way channel. The decoder needs slightly more than k
    // distinct symbols to recover any lost source symbols.
    private int symbolCount(int k) {
        return symbolCount(k, repairOverhead);
    }

    private static int symbolCount(int k, double overhead) {
        return k + (int) Math.ceil(k * overhead);
    }

    // Builds fountain symbol `esi` for the file: XOR of its source-symbol neighbors. For
    // esi < k this reads back source symbol esi verbatim (systematic).
    private static byte[] buildSymbolBytes(FileEncodingPlan plan, int esi) throws IOException {
        int[] neighbors = LtFountain.neighbors(esi, plan.totalChunks());
        byte[] symbol = plan.readSymbol(neighbors[0]);
        for (int n = 1; n < neighbors.length; n++) {
            LtFountain.xorInto(symbol, plan.readSymbol(neighbors[n]));
        }
        return symbol;
    }

    // Generates one fountain symbol's QR PNG (esi in [0, totalSymbols)). Safe to run
    // concurrently: positional source-symbol reads, an atomic folder counter (flat mode),
    // idempotent directory creation, and concurrent output tracking.
    private void writeSymbol(FileEncodingPlan plan,
                             int esi,
                             int totalSymbols,
                             Path folderModeOutDir,
                             Path outPath,
                             AtomicInteger pngCounter,
                             Set<Path> createdDirs,
                             Queue<Path> createdFiles) throws Exception {
        byte[] symbol = buildSymbolBytes(plan, esi);

        byte[] payload = QrPayloadSupport.buildPayload(
                plan.relPath(), plan.fileHash(), plan.totalChunks(), plan.encodedSize(), esi, symbol);

        String line1 = buildQrLabel(plan.fileName(), esi + 1, totalSymbols);
        String line2 = plan.relPath();
        BufferedImage qrImage = qrImageWriter.generateQrImage(payload, line1, line2);

        Path outDir;
        if (folderModeOutDir != null) {
            outDir = folderModeOutDir;
        } else {
            int seq = pngCounter.getAndIncrement();
            String folderName = String.format("%07d", (seq / filesPerFolder) * filesPerFolder);
            outDir = outPath.resolve(folderName);
            createDirectoryConcurrent(outDir, createdDirs);
        }

        String prefix = folderStructure ? plan.safePrefix() : plan.flatSafePrefix();
        String qrFileName = buildQrFileName(prefix, esi + 1, totalSymbols);
        Path qrFilePath = outDir.resolve(qrFileName);
        // Register before writing: if the write dies halfway, cleanup still removes the
        // truncated PNG instead of leaving it to poison a later decode run.
        createdFiles.add(qrFilePath);
        QrImageWriter.writePng(qrImage, qrFilePath);
    }

    private static void closeQuietly(FileEncodingPlan plan) {
        try {
            plan.close();
        } catch (IOException ignored) {
            // best-effort; temp file is also marked deleteOnExit
        }
    }

    ReencodeSummary reencode(Path srcPath,
                                Path outPath,
                                Path rootPath,
                                Path resultPath,
                                EncodeListener listener) throws Exception {
        EncodeListener effectiveListener = listener != null ? listener : line -> { };
        List<String> lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
        List<String> failedFiles = ReencodeResultParser.parseFailedFiles(lines);

        if (failedFiles.isEmpty()) {
            return new ReencodeSummary(0, 0, 0);
        }

        Files.createDirectories(outPath);
        int totalQrCount = 0;
        int fileCount = 0;
        int errorCount = 0;
        int pngCounter = 0;

        for (String relPath : failedFiles) {
            String normalizedRelPath;
            try {
                normalizedRelPath = RelativePathSupport.normalizeRelativePath(relPath);
            } catch (IllegalArgumentException e) {
                effectiveListener.onLog(String.format("%n[SKIP] %s - 잘못된 상대 경로: %s", relPath, e.getMessage()));
                errorCount++;
                continue;
            }

            Path originalFile = FileEncodingPlan.resolveSourceFile(
                    rootPath,
                    normalizedRelPath,
                    convertXlsxToCsv,
                    convertOfficeToText
            );
            if (!Files.exists(originalFile)) {
                effectiveListener.onLog(String.format("%n[SKIP] %s - 원본 파일 없음", normalizedRelPath));
                errorCount++;
                continue;
            }

            try (FileEncodingPlan plan = FileEncodingPlan.fromSourceFile(
                    originalFile,
                    normalizedRelPath,
                    convertXlsxToCsv,
                    convertOfficeToText,
                    chunkDataSize
            )) {
                // The previous capture failed, so re-emit the whole fountain stream with a
                // larger repair margin (at least REENCODE_REPAIR_OVERHEAD, never below the
                // configured one): a fresh decode run reads only these PNGs, and the extra
                // distinct symbols raise the odds the retry pass clears the file.
                int totalSymbols = symbolCount(plan.totalChunks(),
                        Math.max(repairOverhead, REENCODE_REPAIR_OVERHEAD));
                Path fileOutDir = outPath;

                effectiveListener.onLog(String.format("%n[FILE %d/%d] %s (소스 %d, QR %d장 재생성)",
                        fileCount + 1, failedFiles.size(), plan.relPath(),
                        plan.totalChunks(), totalSymbols));

                for (int esi = 0; esi < totalSymbols; esi++) {
                    byte[] symbol = buildSymbolBytes(plan, esi);
                    byte[] payload = QrPayloadSupport.buildPayload(
                            plan.relPath(), plan.fileHash(), plan.totalChunks(), plan.encodedSize(), esi, symbol);

                    String line1 = buildQrLabel(plan.fileName(), esi + 1, totalSymbols);
                    String line2 = plan.relPath();
                    BufferedImage qrImage = qrImageWriter.generateQrImage(payload, line1, line2);

                    if (!folderStructure) {
                        String folderName = String.format("%07d", (pngCounter / filesPerFolder) * filesPerFolder);
                        fileOutDir = outPath.resolve(folderName);
                        Files.createDirectories(fileOutDir);
                    }

                    // reencode never rebuilds the relDir tree, so output is always flat;
                    // use the path-derived prefix to keep names unique across files.
                    String qrFileName = buildQrFileName(plan.flatSafePrefix(), esi + 1, totalSymbols);
                    Path qrFilePath = fileOutDir.resolve(qrFileName);
                    QrImageWriter.writePng(qrImage, qrFilePath);

                    effectiveListener.onLog(String.format("  -> %s", qrFileName));
                    totalQrCount++;
                    pngCounter++;
                }

                fileCount++;
            }
        }

        return new ReencodeSummary(totalQrCount, fileCount, errorCount);
    }

    // encode relativizes each source file against rootPath to build the QR payload's
    // relative path. If rootPath is not an ancestor of srcPath, relativize produces
    // paths containing "..", which the receiver rejects during restore. Require the
    // source directory to live under the encode root.
    static boolean isSourceUnderRoot(Path srcPath, Path rootPath) {
        return srcPath.toAbsolutePath().normalize().startsWith(rootPath.toAbsolutePath().normalize());
    }

    private static void requireSourceUnderRoot(Path srcPath, Path rootPath) {
        if (!isSourceUnderRoot(srcPath, rootPath)) {
            throw new IllegalArgumentException(
                    "encode-root must be an ancestor of the source directory: encode-root="
                            + rootPath.toAbsolutePath().normalize()
                            + ", source=" + srcPath.toAbsolutePath().normalize());
        }
    }

    private String buildQrFileName(String safePrefix, int chunkIdx, int totalChunks) {
        int width = Math.max(3, String.valueOf(totalChunks).length());
        return String.format(Locale.ROOT, "%s_%0" + width + "dof%0" + width + "d.png",
                safePrefix, chunkIdx, totalChunks);
    }

    private String buildQrLabel(String fileName, int chunkIdx, int totalChunks) {
        int width = Math.max(3, String.valueOf(totalChunks).length());
        return String.format(Locale.ROOT, "%s  [%0" + width + "d/%0" + width + "d]",
                fileName, chunkIdx, totalChunks);
    }

    private static String detectExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return "";
        }
        return fileName.substring(dotIdx).toLowerCase(Locale.ROOT);
    }

    private static void createDirectoryConcurrent(Path dir, Set<Path> createdDirs) throws IOException {
        if (Files.notExists(dir)) {
            // createDirectories is idempotent, so a race between workers creating the same dir
            // is harmless; the set just records which dirs we made for cleanup.
            Files.createDirectories(dir);
            createdDirs.add(dir);
        }
    }

    private static void cleanupCancelledOutput(Queue<Path> createdFiles, Set<Path> createdDirs,
                                               EncodeListener listener, String label) {
        for (Path createdFile : createdFiles) {
            try {
                Files.deleteIfExists(createdFile);
            } catch (IOException e) {
                listener.onLog(label + "[WARN] 생성 파일 삭제 실패: " + createdFile + " (" + e.getMessage() + ")");
            }
        }
        createdDirs.stream()
                .sorted(Comparator.reverseOrder())
                .forEach(dir -> {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException ignored) {
                        // Keep non-empty directories because they may contain pre-existing user files.
                    }
                });
        listener.onLog(label + " 생성된 encode 파일을 정리했습니다.");
    }
}
