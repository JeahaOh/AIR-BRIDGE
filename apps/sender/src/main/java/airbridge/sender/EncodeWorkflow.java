package airbridge.sender;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class EncodeWorkflow {
    public static final String DEFAULT_PROJECT_NAME = SenderDefaults.DEFAULT_PROJECT_NAME;
    public static final int DEFAULT_CHUNK_DATA_SIZE = SenderDefaults.DEFAULT_CHUNK_DATA_SIZE;
    public static final int DEFAULT_QR_IMAGE_SIZE = SenderDefaults.DEFAULT_QR_IMAGE_SIZE;
    public static final ErrorCorrectionLevel DEFAULT_QR_ERROR_LEVEL = SenderDefaults.DEFAULT_QR_ERROR_LEVEL;
    public static final int DEFAULT_LABEL_HEIGHT = SenderDefaults.DEFAULT_LABEL_HEIGHT;
    public static final boolean DEFAULT_FOLDER_STRUCTURE = SenderDefaults.DEFAULT_FOLDER_STRUCTURE;
    public static final int DEFAULT_FILES_PER_FOLDER = SenderDefaults.DEFAULT_FILES_PER_FOLDER;
    public static final List<String> DEFAULT_TARGET_EXTENSIONS = SenderDefaults.DEFAULT_TARGET_EXTENSIONS;
    public static final List<String> DEFAULT_SKIP_DIRS = SenderDefaults.DEFAULT_SKIP_DIRS;

    public Result encode(Request request, Consumer<String> listener) throws Exception {
        return encode(request, listener, () -> false);
    }

    public Result encode(Request request, Consumer<String> listener, BooleanSupplier cancelled) throws Exception {
        Consumer<String> effectiveListener = listener != null ? listener : line -> { };
        BooleanSupplier effectiveCancelled = cancelled != null ? cancelled : () -> false;
        Request normalized = request.normalized();
        if (!Files.isDirectory(normalized.sourceDir())) {
            String message = "[ERROR] 소스 디렉토리가 존재하지 않습니다: " + normalized.sourceDir();
            effectiveListener.accept(message);
            return Result.inputMissing(normalized, message);
        }

        List<Path> sourceFiles = SourceCollector.collectSourceFiles(
                normalized.sourceDir(),
                normalized.targetExtensions(),
                normalized.skipDirs(),
                normalized.excludePaths()
        );
        if (sourceFiles.isEmpty()) {
            String message = "[WARN] 대상 소스파일이 없습니다: " + normalized.sourceDir();
            effectiveListener.accept(message);
            return Result.noSourceFiles(normalized, message);
        }

        try {
            EncodeSummary summary = new EncodeService(
                    new QrImageWriter(
                            normalized.qrImageSize(),
                            normalized.labelHeight(),
                            normalized.qrErrorLevel()
                    ),
                    normalized.chunkDataSize(),
                    normalized.convertXlsxToCsv(),
                    normalized.convertOfficeToText(),
                    normalized.folderStructure(),
                    normalized.filesPerFolder()
            ).encode(
                    normalized.sourceDir(),
                    normalized.outputDir(),
                    normalized.effectiveEncodeRoot(),
                    normalized.projectName(),
                    normalized.targetExtensions(),
                    normalized.skipDirs(),
                    normalized.excludePaths(),
                    effectiveListener::accept,
                    effectiveCancelled
            );
            return Result.completed(normalized, sourceFiles.size(), summary);
        } catch (CancellationException e) {
            String message = "[CANCELLED] encode cancelled";
            effectiveListener.accept(message);
            return Result.cancelled(normalized, sourceFiles.size(), message);
        }
    }

    public enum Status {
        INPUT_MISSING,
        NO_SOURCE_FILES,
        CANCELLED,
        COMPLETED
    }

    public record Request(
            Path sourceDir,
            Path outputDir,
            Path encodeRoot,
            String projectName,
            int chunkDataSize,
            int qrImageSize,
            ErrorCorrectionLevel qrErrorLevel,
            int labelHeight,
            boolean convertXlsxToCsv,
            boolean convertOfficeToText,
            boolean folderStructure,
            int filesPerFolder,
            List<String> targetExtensions,
            List<String> skipDirs,
            List<String> excludePaths
    ) {
        public static Request defaults(Path sourceDir, Path outputDir) {
            return new Request(
                    sourceDir,
                    outputDir,
                    null,
                    DEFAULT_PROJECT_NAME,
                    DEFAULT_CHUNK_DATA_SIZE,
                    DEFAULT_QR_IMAGE_SIZE,
                    DEFAULT_QR_ERROR_LEVEL,
                    DEFAULT_LABEL_HEIGHT,
                    false,
                    false,
                    DEFAULT_FOLDER_STRUCTURE,
                    DEFAULT_FILES_PER_FOLDER,
                    DEFAULT_TARGET_EXTENSIONS,
                    DEFAULT_SKIP_DIRS,
                    List.of()
            );
        }

        Request normalized() {
            requireMin("chunkDataSize", chunkDataSize, 1);
            requireMin("qrImageSize", qrImageSize, 1);
            requireMin("labelHeight", labelHeight, 0);
            requireMin("filesPerFolder", filesPerFolder, 1);
            if (sourceDir == null) {
                throw new IllegalArgumentException("sourceDir is required");
            }
            if (outputDir == null) {
                throw new IllegalArgumentException("outputDir is required");
            }
            return new Request(
                    sourceDir.toAbsolutePath().normalize(),
                    outputDir.toAbsolutePath().normalize(),
                    encodeRoot != null ? encodeRoot.toAbsolutePath().normalize() : null,
                    (projectName == null || projectName.isBlank()) ? DEFAULT_PROJECT_NAME : projectName,
                    chunkDataSize,
                    qrImageSize,
                    qrErrorLevel != null ? qrErrorLevel : DEFAULT_QR_ERROR_LEVEL,
                    labelHeight,
                    convertXlsxToCsv,
                    convertOfficeToText,
                    folderStructure,
                    filesPerFolder,
                    copyList(targetExtensions),
                    copyList(skipDirs),
                    copyList(excludePaths)
            );
        }

        Path effectiveEncodeRoot() {
            return encodeRoot != null ? encodeRoot : sourceDir;
        }

        private static List<String> copyList(List<String> values) {
            return values != null ? new ArrayList<>(values) : List.of();
        }

        private static void requireMin(String name, int actualValue, int minValue) {
            if (actualValue < minValue) {
                throw new IllegalArgumentException(
                        String.format("%s must be >= %d (was %d)", name, minValue, actualValue)
                );
            }
        }
    }

    public record Result(
            Status status,
            Path sourceDir,
            Path outputDir,
            int sourceFileCount,
            int totalQrCount,
            int totalFileCount,
            long totalOrigBytes,
            Path manifestPath,
            String message
    ) {
        private static Result inputMissing(Request request, String message) {
            return new Result(Status.INPUT_MISSING, request.sourceDir(), request.outputDir(), 0, 0, 0, 0, null, message);
        }

        private static Result noSourceFiles(Request request, String message) {
            return new Result(Status.NO_SOURCE_FILES, request.sourceDir(), request.outputDir(), 0, 0, 0, 0, null, message);
        }

        private static Result cancelled(Request request, int sourceFileCount, String message) {
            return new Result(Status.CANCELLED, request.sourceDir(), request.outputDir(), sourceFileCount, 0, 0, 0, null, message);
        }

        private static Result completed(Request request, int sourceFileCount, EncodeSummary summary) {
            return new Result(
                    Status.COMPLETED,
                    request.sourceDir(),
                    request.outputDir(),
                    sourceFileCount,
                    summary.totalQrCount(),
                    summary.totalFileCount(),
                    summary.totalOrigBytes(),
                    summary.manifestPath(),
                    "encode completed"
            );
        }
    }
}
