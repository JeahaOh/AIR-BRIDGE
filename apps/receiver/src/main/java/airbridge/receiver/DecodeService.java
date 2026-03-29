package airbridge.receiver;

import airbridge.common.CodecSupport;
import airbridge.common.RelativePathSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DecodeService {
    private static final int DECODE_TASK_MAX_ATTEMPTS = 3;
    private static final long DECODE_RETRY_DELAY_MS = 200L;

    private final int decodeWorkers;

    DecodeService(int decodeWorkers) {
        this.decodeWorkers = Math.max(1, decodeWorkers);
    }

    DecodeSummary decode(Path srcPath, Path outPath, List<Path> qrFiles, DecodeListener listener) throws Exception {
        DecodeListener effectiveListener = listener != null ? listener : line -> { };
        Files.createDirectories(outPath);
        Map<String, FileChunks> fileChunkMap = new LinkedHashMap<>();
        List<String> reportLines = new ArrayList<>();
        int decodeErrorCount = 0;

        ExecutorService decodeExecutor = Executors.newFixedThreadPool(decodeWorkers);
        ExecutorCompletionService<QrDecodeTaskResult> completionService = new ExecutorCompletionService<>(decodeExecutor);

        try {
            for (int i = 0; i < qrFiles.size(); i++) {
                int index = i;
                Path qrFile = qrFiles.get(i);
                completionService.submit(() -> QrDecodeSupport.decodeTask(index, qrFile, DECODE_TASK_MAX_ATTEMPTS, DECODE_RETRY_DELAY_MS));
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

                FileChunks fileChunks = fileChunkMap.get(normalizedRelPath);
                if (fileChunks == null) {
                    fileChunks = new FileChunks(chunk.project, normalizedRelPath, chunk.totalChunks, chunk.hash16);
                }

                try {
                    fileChunks.addChunk(chunk, result.qrFile);
                    fileChunkMap.put(normalizedRelPath, fileChunks);
                } catch (Exception e) {
                    effectiveListener.onLog(String.format("  [WARN] QR decode 실패: %s",
                            QrDecodeSupport.formatDecodeException(e)));
                    reportLines.add("! " + srcPath.relativize(result.qrFile) + " - QR_READ_ERROR");
                    decodeErrorCount++;
                }
            }
        } finally {
            decodeExecutor.shutdownNow();
        }

        int restoredCount = 0;
        int incompleteCount = 0;
        int hashMismatchCount = 0;

        for (FileChunks fileChunks : fileChunkMap.values()) {
            List<Integer> missingChunks = fileChunks.findMissingChunks();
            if (!missingChunks.isEmpty()) {
                reportLines.add("X " + fileChunks.relPath + " - INCOMPLETE (누락: " + missingChunks + ")");
                effectiveListener.onLog(String.format("  [INCOMPLETE] %s - 누락 %s", fileChunks.relPath, missingChunks));
                incompleteCount++;
                continue;
            }

            byte[] restoredData;
            try {
                restoredData = CodecSupport.decodeAndDecompress(fileChunks.joinEncodedData());
            } catch (Exception e) {
                reportLines.add("X " + fileChunks.relPath + " - DECODE_ERROR");
                effectiveListener.onLog(String.format("  [DECODE_ERROR] %s - %s", fileChunks.relPath, e.getMessage()));
                decodeErrorCount++;
                continue;
            }

            String actualHash16 = CodecSupport.sha256Hex(restoredData).substring(0, 16);
            if (!actualHash16.equals(fileChunks.hash16)) {
                reportLines.add("X " + fileChunks.relPath + " - HASH_MISMATCH");
                effectiveListener.onLog(String.format("  [HASH_MISMATCH] %s - expected=%s actual=%s",
                        fileChunks.relPath, fileChunks.hash16, actualHash16));
                hashMismatchCount++;
                continue;
            }

            Path restoredFile;
            try {
                restoredFile = RelativePathSupport.resolveUnderRoot(outPath, fileChunks.relPath);
            } catch (IllegalArgumentException e) {
                reportLines.add("X " + fileChunks.relPath + " - INVALID_PATH");
                effectiveListener.onLog(String.format("  [INVALID_PATH] %s - %s", fileChunks.relPath, e.getMessage()));
                decodeErrorCount++;
                continue;
            }
            Path parent = restoredFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(restoredFile, restoredData);
            List<Path> movedTargets = moveDecodedQrFilesToSuccess(fileChunks.qrFiles());

            reportLines.add("O " + fileChunks.relPath + " - OK" + formatMovedTargets(movedTargets));
            effectiveListener.onLog(String.format("  [RESTORED] %s", fileChunks.relPath));
            restoredCount++;
        }

        Path reportPath = outPath.resolve("_restore_result.txt");
        Files.write(reportPath, String.join(System.lineSeparator(), reportLines).getBytes(StandardCharsets.UTF_8));
        return new DecodeSummary(reportPath, restoredCount, incompleteCount, hashMismatchCount, decodeErrorCount);
    }

    private static List<Path> moveDecodedQrFilesToSuccess(List<Path> qrFiles) {
        List<Path> movedTargets = new ArrayList<>();
        for (Path qrFile : qrFiles) {
            try {
                Path parent = qrFile.getParent();
                if (parent == null) {
                    continue;
                }

                Path successDir = buildSuccessDir(parent);
                Files.createDirectories(successDir);
                Path target = successDir.resolve(qrFile.getFileName());
                Files.move(qrFile, target, StandardCopyOption.REPLACE_EXISTING);
                movedTargets.add(target);
            } catch (Exception e) {
                // decode 진행은 유지하고 이동 실패만 로그 문자열에 남긴다.
            }
        }
        return movedTargets;
    }

    private static Path buildSuccessDir(Path sourceDir) {
        Path parent = sourceDir.getParent();
        Path dirName = sourceDir.getFileName();
        if (parent == null || dirName == null) {
            return sourceDir.resolve("success");
        }
        return parent.resolve(dirName + "-success");
    }

    private static String formatMovedTargets(List<Path> movedTargets) {
        if (movedTargets.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<>();
        for (Path path : movedTargets) {
            paths.add(path.toString());
        }
        return " (moved: " + paths + ")";
    }
}
