package airbridge.receiver;

import airbridge.common.CodecSupport;
import airbridge.common.RelativePathSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DecodeService {
    private static final int DECODE_TASK_MAX_ATTEMPTS = 3;
    private static final long DECODE_RETRY_DELAY_MS = 200L;
    // QR file name shape produced by encode: <prefix>_NNNofNNN.png
    private static final Pattern QR_FILE_NAME_PATTERN =
            Pattern.compile("^(.+)_(\\d+)of(\\d+)\\.png$", Pattern.CASE_INSENSITIVE);

    private final int decodeWorkers;

    DecodeService(int decodeWorkers) {
        this.decodeWorkers = Math.max(1, decodeWorkers);
    }

    DecodeSummary decode(Path srcPath, Path outPath, List<Path> qrFiles, DecodeListener listener) throws Exception {
        DecodeListener effectiveListener = listener != null ? listener : line -> { };
        Files.createDirectories(outPath);
        Map<String, FileChunks> fileChunkMap = new LinkedHashMap<>();
        Set<String> finalizedPaths = new HashSet<>();
        Set<String> restoredPaths = new HashSet<>();
        List<String> reportLines = new ArrayList<>();
        int restoredCount = 0;
        int hashMismatchCount = 0;
        int decodeErrorCount = 0;

        ExecutorService decodeExecutor = Executors.newFixedThreadPool(decodeWorkers);
        ExecutorCompletionService<QrDecodeTaskResult> completionService = new ExecutorCompletionService<>(decodeExecutor);
        // File-name identities (dir + prefix + declared total) of files already restored.
        // Workers consult it before decoding: with repair overhead a file's surplus frames
        // (~1/3 of all PNGs at the default 0.5) need no PNG read + QR decode once it restored.
        Set<String> restoredFileKeys = ConcurrentHashMap.newKeySet();

        try {
            for (int i = 0; i < qrFiles.size(); i++) {
                int index = i;
                Path qrFile = qrFiles.get(i);
                completionService.submit(() -> {
                    String key = qrFileNameKey(qrFile);
                    if (key != null && restoredFileKeys.contains(key)) {
                        return QrDecodeTaskResult.skippedRestored(index, qrFile);
                    }
                    return QrDecodeSupport.decodeTask(index, qrFile, DECODE_TASK_MAX_ATTEMPTS, DECODE_RETRY_DELAY_MS);
                });
            }

            for (int completed = 0; completed < qrFiles.size(); completed++) {
                QrDecodeTaskResult result;
                try {
                    result = completionService.take().get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    Path unknownPath = qrFiles.get(Math.min(completed, qrFiles.size() - 1));
                    effectiveListener.onLog(String.format("[QR %d/%d] %s", completed + 1, qrFiles.size(), srcPath.relativize(unknownPath)));
                    effectiveListener.onLog(String.format("  [WARN] QR decode 실패: %s", QrDecodeSupport.formatDecodeThrowable(cause)));
                    reportLines.add("! " + srcPath.relativize(unknownPath) + " - QR_READ_ERROR");
                    decodeErrorCount++;
                    continue;
                }

                effectiveListener.onLog(String.format("[QR %d/%d] %s",
                        result.index + 1, qrFiles.size(), srcPath.relativize(result.qrFile)));

                if (result.skippedRestored) {
                    effectiveListener.onLog("  [SKIP] 이미 복원된 파일의 잉여 QR — decode 생략 및 파일 삭제");
                    deleteDecodedQrFiles(List.of(result.qrFile));
                    continue;
                }

                if (result.error != null) {
                    effectiveListener.onLog(String.format("  [WARN] QR decode 실패: %s",
                            QrDecodeSupport.formatDecodeThrowable(result.error)));
                    reportLines.add("! " + srcPath.relativize(result.qrFile) + " - QR_READ_ERROR");
                    decodeErrorCount++;
                    continue;
                }

                if (result.attempts > 1) {
                    effectiveListener.onLog(String.format("  [RECOVERED] retry success after %d attempts", result.attempts));
                }

                QrDecodedChunk chunk = result.chunk;
                String normalizedRelPath;
                try {
                    normalizedRelPath = RelativePathSupport.normalizeRelativePath(chunk.relPath);
                } catch (IllegalArgumentException e) {
                    effectiveListener.onLog(String.format("  [WARN] 잘못된 상대 경로: %s", e.getMessage()));
                    reportLines.add("! " + srcPath.relativize(result.qrFile) + " - INVALID_REL_PATH");
                    decodeErrorCount++;
                    continue;
                }

                // The file was already restored (or failed terminally) when it completed earlier;
                // ignore late/duplicate chunks for it so it is not resurrected or reprocessed.
                // Surplus PNGs of a successfully restored file are deleted too; left behind
                // they would read as a bogus INCOMPLETE file on the next decode run.
                if (finalizedPaths.contains(normalizedRelPath)) {
                    if (restoredPaths.contains(normalizedRelPath)) {
                        deleteDecodedQrFiles(List.of(result.qrFile));
                    }
                    continue;
                }

                FileChunks fileChunks = fileChunkMap.get(normalizedRelPath);
                try {
                    // Construction validates k/symbolSize (LtDecoder rejects k < 1 etc.), so a
                    // frame-shaped payload with impossible fields must fail this one chunk, not
                    // abort the whole decode run.
                    if (fileChunks == null) {
                        fileChunks = new FileChunks(normalizedRelPath, chunk.k, chunk.gzipLen,
                                chunk.symbolData.length, chunk.hash16);
                    }
                    fileChunks.addChunk(chunk, result.qrFile);
                } catch (Exception e) {
                    effectiveListener.onLog(String.format("  [WARN] QR decode 실패: %s",
                            QrDecodeSupport.formatDecodeException(e)));
                    reportLines.add("! " + srcPath.relativize(result.qrFile) + " - QR_READ_ERROR");
                    decodeErrorCount++;
                    continue;
                }
                fileChunkMap.put(normalizedRelPath, fileChunks);

                // Restore as soon as a file has all its chunks, then drop it from the map so the
                // accumulated chunk strings are freed. This bounds total decode memory to the
                // chunks of files still in progress, instead of the whole transfer at once.
                if (fileChunks.isComplete()) {
                    switch (restoreCompletedFile(fileChunks, outPath, reportLines, effectiveListener)) {
                        case RESTORED -> {
                            restoredCount++;
                            restoredPaths.add(normalizedRelPath);
                            for (Path consumed : fileChunks.qrFiles()) {
                                String key = qrFileNameKey(consumed);
                                if (key != null) {
                                    restoredFileKeys.add(key);
                                }
                            }
                        }
                        case HASH_MISMATCH -> hashMismatchCount++;
                        case DECODE_ERROR, INVALID_PATH -> decodeErrorCount++;
                    }
                    fileChunkMap.remove(normalizedRelPath);
                    finalizedPaths.add(normalizedRelPath);
                }
            }
        } finally {
            decodeExecutor.shutdownNow();
        }

        // Anything still buffered never collected enough distinct symbols to decode.
        int incompleteCount = 0;
        for (FileChunks fileChunks : fileChunkMap.values()) {
            reportLines.add("X " + fileChunks.relPath + " - INCOMPLETE (심볼 "
                    + fileChunks.receivedCount() + "/" + fileChunks.k + " 소스, 복원 불가)");
            effectiveListener.onLog(String.format("  [INCOMPLETE] %s - 심볼 %d/%d (소스), 복원 불가",
                    fileChunks.relPath, fileChunks.receivedCount(), fileChunks.k));
            incompleteCount++;
        }

        Path reportPath = outPath.resolve("_restore_result.txt");
        Files.write(reportPath, String.join(System.lineSeparator(), reportLines).getBytes(StandardCharsets.UTF_8));
        return new DecodeSummary(reportPath, restoredCount, incompleteCount, hashMismatchCount, decodeErrorCount);
    }

    private enum RestoreOutcome { RESTORED, HASH_MISMATCH, DECODE_ERROR, INVALID_PATH }

    // Never throws for a single file's failure: an IO error while restoring one file must not
    // abort the run — the remaining files still decode and the report is still written.
    private RestoreOutcome restoreCompletedFile(FileChunks fileChunks,
                                                Path outPath,
                                                List<String> reportLines,
                                                DecodeListener listener) {
        Path restoredFile;
        try {
            restoredFile = RelativePathSupport.resolveUnderRoot(outPath, fileChunks.relPath);
        } catch (IllegalArgumentException e) {
            reportLines.add("X " + fileChunks.relPath + " - INVALID_PATH");
            listener.onLog(String.format("  [INVALID_PATH] %s - %s", fileChunks.relPath, e.getMessage()));
            return RestoreOutcome.INVALID_PATH;
        }

        // Stream gzip -> a temp file in the destination directory, computing the hash in the
        // same pass, so memory does not scale with file size. Only move the temp file into
        // place once the hash matches, so a bad payload never lands at the target.
        Path tempFile = null;
        try {
            Path parent = restoredFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path stagingDir = (parent != null) ? parent : outPath;
            tempFile = Files.createTempFile(stagingDir, ".airbridge-restore-", ".part");
            String actualHash16 = CodecSupport.decompressToFile(fileChunks.encodedStream(), tempFile)
                    .substring(0, 16);

            if (!actualHash16.equals(fileChunks.hash16)) {
                Files.deleteIfExists(tempFile);
                reportLines.add("X " + fileChunks.relPath + " - HASH_MISMATCH");
                listener.onLog(String.format("  [HASH_MISMATCH] %s - expected=%s actual=%s",
                        fileChunks.relPath, fileChunks.hash16, actualHash16));
                return RestoreOutcome.HASH_MISMATCH;
            }

            Files.move(tempFile, restoredFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            deleteQuietly(tempFile);
            reportLines.add("X " + fileChunks.relPath + " - DECODE_ERROR");
            listener.onLog(String.format("  [DECODE_ERROR] %s - %s", fileChunks.relPath, e.getMessage()));
            return RestoreOutcome.DECODE_ERROR;
        }

        List<Path> deletedFiles = deleteDecodedQrFiles(fileChunks.qrFiles());
        reportLines.add("O " + fileChunks.relPath + " - OK" + formatDeletedFiles(deletedFiles));
        listener.onLog(String.format("  [RESTORED] %s", fileChunks.relPath));
        return RestoreOutcome.RESTORED;
    }

    // File identity derived from the QR file name: same directory + same prefix + same
    // declared symbol total can only be frames of the same source file (encode derives the
    // prefix uniquely per file within its output dir). Null for foreign file names, which
    // are then never skipped.
    private static String qrFileNameKey(Path qrFile) {
        Matcher matcher = QR_FILE_NAME_PATTERN.matcher(qrFile.getFileName().toString());
        if (!matcher.matches()) {
            return null;
        }
        Path parent = qrFile.getParent();
        return (parent != null ? parent.toString() : "") + '\u0000' + matcher.group(1)
                + '\u0000' + matcher.group(3);
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException ignored) {
            // best-effort cleanup of the staging temp file
        }
    }

    private static List<Path> deleteDecodedQrFiles(List<Path> qrFiles) {
        List<Path> deletedFiles = new ArrayList<>();
        for (Path qrFile : qrFiles) {
            try {
                if (Files.deleteIfExists(qrFile)) {
                    deletedFiles.add(qrFile);
                }
            } catch (Exception e) {
                // Best-effort cleanup: deletion failure must not fail a restored file.
            }
        }
        return deletedFiles;
    }

    private static String formatDeletedFiles(List<Path> deletedFiles) {
        if (deletedFiles.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<>();
        for (Path path : deletedFiles) {
            paths.add(path.toString());
        }
        return " (deleted: " + paths + ")";
    }
}
