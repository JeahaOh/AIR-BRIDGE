package airbridge.receiver.capture;

import java.nio.file.Path;

public record CaptureSummary(
        Path outputDir,
        Path capturedImagesDir,
        Path manifestPath,
        String startedAt,
        String finishedAt,
        String stopReason,
        long totalFrames,
        long analyzedFrames,
        long decodedFrames,
        long uniquePayloads,
        long savedImages,
        long blackFramesSkipped,
        long decodeFailures
) {
}
