package airbridge.receiver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class DecodeWorkflow {
    private final int decodeWorkers;

    public DecodeWorkflow(int decodeWorkers) {
        this.decodeWorkers = Math.max(1, decodeWorkers);
    }

    public Result decode(Path sourceDir, Path outputDir, Consumer<String> listener) throws Exception {
        Consumer<String> effectiveListener = listener != null ? listener : line -> { };
        Path srcPath = sourceDir.toAbsolutePath().normalize();
        Path outPath = outputDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(srcPath)) {
            String message = "[ERROR] QR 입력 디렉토리가 존재하지 않습니다: " + srcPath;
            effectiveListener.accept(message);
            return Result.inputMissing(srcPath, outPath, message);
        }

        List<Path> qrFiles = QrDecodeSupport.collectQrImageFiles(srcPath);
        if (qrFiles.isEmpty()) {
            String message = "[WARN] 대상 QR PNG 파일이 없습니다: " + srcPath;
            effectiveListener.accept(message);
            return Result.noQrFiles(srcPath, outPath, message);
        }

        DecodeSummary summary = new DecodeService(decodeWorkers)
                .decode(srcPath, outPath, qrFiles, effectiveListener::accept);
        return Result.completed(srcPath, outPath, qrFiles.size(), summary);
    }

    public enum Status {
        INPUT_MISSING,
        NO_QR_FILES,
        COMPLETED
    }

    public record Result(
            Status status,
            Path sourceDir,
            Path outputDir,
            int qrFileCount,
            Path reportPath,
            int restoredCount,
            int incompleteCount,
            int hashMismatchCount,
            int decodeErrorCount,
            String message
    ) {
        private static Result inputMissing(Path sourceDir, Path outputDir, String message) {
            return new Result(Status.INPUT_MISSING, sourceDir, outputDir, 0, null, 0, 0, 0, 0, message);
        }

        private static Result noQrFiles(Path sourceDir, Path outputDir, String message) {
            return new Result(Status.NO_QR_FILES, sourceDir, outputDir, 0, null, 0, 0, 0, 0, message);
        }

        private static Result completed(Path sourceDir, Path outputDir, int qrFileCount, DecodeSummary summary) {
            return new Result(
                    Status.COMPLETED,
                    sourceDir,
                    outputDir,
                    qrFileCount,
                    summary.reportPath(),
                    summary.restoredCount(),
                    summary.incompleteCount(),
                    summary.hashMismatchCount(),
                    summary.decodeErrorCount(),
                    "decode completed"
            );
        }
    }
}
