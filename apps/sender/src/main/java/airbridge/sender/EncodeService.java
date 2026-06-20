package airbridge.sender;

import airbridge.common.ConsoleSupport;
import airbridge.common.QrPayloadSupport;
import airbridge.common.RelativePathSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
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
    private final QrImageWriter qrImageWriter;
    private final int chunkDataSize;
    private final boolean convertXlsxToCsv;
    private final boolean convertOfficeToText;
    private final boolean folderStructure;
    private final int filesPerFolder;
    private final int encodeWorkers;

    EncodeService(QrImageWriter qrImageWriter,
                     int chunkDataSize,
                     boolean convertXlsxToCsv,
                     boolean convertOfficeToText,
                     boolean folderStructure,
                     int filesPerFolder) {
        this(qrImageWriter, chunkDataSize, convertXlsxToCsv, convertOfficeToText,
                folderStructure, filesPerFolder, SenderDefaults.DEFAULT_ENCODE_WORKERS);
    }

    EncodeService(QrImageWriter qrImageWriter,
                     int chunkDataSize,
                     boolean convertXlsxToCsv,
                     boolean convertOfficeToText,
                     boolean folderStructure,
                     int filesPerFolder,
                     int encodeWorkers) {
        if (chunkDataSize < 1) {
            throw new IllegalArgumentException("chunkDataSize must be >= 1");
        }
        if (filesPerFolder < 1) {
            throw new IllegalArgumentException("filesPerFolder must be >= 1");
        }
        this.qrImageWriter = qrImageWriter;
        this.chunkDataSize = chunkDataSize;
        this.convertXlsxToCsv = convertXlsxToCsv;
        this.convertOfficeToText = convertOfficeToText;
        this.folderStructure = folderStructure;
        this.filesPerFolder = filesPerFolder;
        this.encodeWorkers = Math.max(1, encodeWorkers);
    }

    EncodeSummary encode(Path srcPath,
                            Path outPath,
                            Path rootPath,
                            String projectName,
                            List<String> targetExtensions,
                            List<String> skipDirs,
                            List<String> excludePaths,
                            EncodeListener listener) throws Exception {
        return encode(srcPath, outPath, rootPath, projectName, targetExtensions, skipDirs, excludePaths, listener, () -> false);
    }

    EncodeSummary encode(Path srcPath,
                            Path outPath,
                            Path rootPath,
                            String projectName,
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
        StringBuilder manifest = new StringBuilder();
        manifest.append("PROJECT: ").append(projectName).append("\n");
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
                    totalOrigBytes += plan.fileSize();

                    effectiveListener.onLog("");
                    if (plan.convertedType() != null) {
                        effectiveListener.onLog(String.format("[FILE %d/%d] %s (%s 변환)",
                                fi + 1, sourceFiles.size(), plan.relPath(), plan.convertedType()));
                        effectiveListener.onLog(String.format("  변환후: %,d bytes -> 압축(gzip): %,d bytes -> QR %d장",
                                plan.fileSize(), plan.encodedSize(), plan.totalChunks()));
                    } else {
                        effectiveListener.onLog(String.format("[FILE %d/%d] %s", fi + 1, sourceFiles.size(), plan.relPath()));
                        effectiveListener.onLog(String.format("  원본: %,d bytes -> 압축(gzip): %,d bytes -> QR %d장",
                                plan.fileSize(), plan.encodedSize(), plan.totalChunks()));
                    }

                    Path fileOutDir = null;
                    if (folderStructure) {
                        Path relDir = srcPath.relativize(file).getParent();
                        fileOutDir = (relDir != null) ? outPath.resolve(relDir) : outPath;
                        createDirectoryConcurrent(fileOutDir, createdDirs);
                    }
                    final Path folderModeOutDir = fileOutDir;

                    manifest.append(String.format("[%s] %,d bytes -> QR %d장 (hash: %s)\n",
                            plan.relPath(), plan.fileSize(), plan.totalChunks(), plan.fileHash().substring(0, 16)));

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
                    AtomicInteger remaining = new AtomicInteger(plan.totalChunks());
                    for (int i = 0; i < plan.totalChunks(); i++) {
                        final int chunkIndex = i;
                        pool.execute(() -> {
                            try {
                                if (failure.get() != null) {
                                    return;
                                }
                                if (effectiveCancelled.getAsBoolean()) {
                                    cancelledObserved.set(true);
                                    return;
                                }
                                writeChunk(planRef, chunkIndex, projectName, folderModeOutDir,
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

    // Generates one chunk's QR PNG. Safe to run concurrently: positional chunk reads, an atomic
    // folder counter (flat mode), idempotent directory creation, and concurrent output tracking.
    private void writeChunk(FileEncodingPlan plan,
                            int chunkIndex,
                            String projectName,
                            Path folderModeOutDir,
                            Path outPath,
                            AtomicInteger pngCounter,
                            Set<Path> createdDirs,
                            Queue<Path> createdFiles) throws Exception {
        int start = chunkIndex * chunkDataSize;
        // long math: (chunkIndex + 1) * chunkDataSize can exceed Integer.MAX_VALUE for payloads
        // near the 2GB encodedSize cap before Math.min clamps it.
        int end = (int) Math.min((long) (chunkIndex + 1) * chunkDataSize, plan.encodedSize());
        byte[] chunkData = plan.readChunk(start, end);
        int chunkIdx = chunkIndex + 1;

        byte[] payload = QrPayloadSupport.buildPayload(
                projectName, plan.relPath(), chunkIdx, plan.totalChunks(), plan.fileHash(), chunkData);

        String line1 = buildQrLabel(plan.fileName(), chunkIdx, plan.totalChunks());
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
        String qrFileName = buildQrFileName(prefix, chunkIdx, plan.totalChunks());
        Path qrFilePath = outDir.resolve(qrFileName);
        ImageIO.write(qrImage, "PNG", qrFilePath.toFile());
        createdFiles.add(qrFilePath);
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
                                String projectName,
                                EncodeListener listener) throws Exception {
        EncodeListener effectiveListener = listener != null ? listener : line -> { };
        List<String> lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
        Map<String, List<Integer>> failedFiles = ReencodeResultParser.parseFailedFiles(lines);

        if (failedFiles.isEmpty()) {
            return new ReencodeSummary(0, 0, 0);
        }

        Files.createDirectories(outPath);
        int totalQrCount = 0;
        int fileCount = 0;
        int errorCount = 0;
        int pngCounter = 0;

        for (Map.Entry<String, List<Integer>> entry : failedFiles.entrySet()) {
            String relPath = entry.getKey();
            List<Integer> missingChunks = entry.getValue();
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
                List<Integer> chunksToGenerate;
                if (missingChunks.isEmpty()) {
                    chunksToGenerate = new ArrayList<>();
                    for (int i = 1; i <= plan.totalChunks(); i++) {
                        chunksToGenerate.add(i);
                    }
                } else {
                    chunksToGenerate = missingChunks;
                }

                Path fileOutDir = outPath;

                effectiveListener.onLog(String.format("%n[FILE %d/%d] %s (총 %d청크 중 %d개 재생성)",
                        fileCount + 1, failedFiles.size(), plan.relPath(),
                        plan.totalChunks(), chunksToGenerate.size()));

                for (int chunkIdx : chunksToGenerate) {
                    if (chunkIdx < 1 || chunkIdx > plan.totalChunks()) {
                        effectiveListener.onLog(String.format("  [WARN] 청크 %d는 범위 밖 (총 %d) - 건너뜀", chunkIdx, plan.totalChunks()));
                        continue;
                    }

                    int start = (chunkIdx - 1) * chunkDataSize;
                    int end = (int) Math.min((long) chunkIdx * chunkDataSize, plan.encodedSize());
                    byte[] chunkData = plan.readChunk(start, end);

                    byte[] payload = QrPayloadSupport.buildPayload(
                            projectName,
                            plan.relPath(),
                            chunkIdx,
                            plan.totalChunks(),
                            plan.fileHash(),
                            chunkData
                    );

                    String line1 = buildQrLabel(plan.fileName(), chunkIdx, plan.totalChunks());
                    String line2 = plan.relPath();
                    BufferedImage qrImage = qrImageWriter.generateQrImage(payload, line1, line2);

                    if (!folderStructure) {
                        String folderName = String.format("%07d", (pngCounter / filesPerFolder) * filesPerFolder);
                        fileOutDir = outPath.resolve(folderName);
                        Files.createDirectories(fileOutDir);
                    }

                    // reencode never rebuilds the relDir tree, so output is always flat;
                    // use the path-derived prefix to keep names unique across files.
                    String qrFileName = buildQrFileName(plan.flatSafePrefix(), chunkIdx, plan.totalChunks());
                    Path qrFilePath = fileOutDir.resolve(qrFileName);
                    ImageIO.write(qrImage, "PNG", qrFilePath.toFile());

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
