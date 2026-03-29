package airbridge.receiver.capture;

import java.nio.file.Path;

public record CaptureOptions(
        Path outputDir,
        int deviceIndex,
        int width,
        int height,
        double fps,
        long durationSeconds,
        int maxPayloads,
        int decodeWorkers,
        long statusIntervalMs,
        long sameSignalSeconds,
        boolean resume
) {
    public CaptureOptions {
        outputDir = outputDir.toAbsolutePath().normalize();
        width = Math.max(1, width);
        height = Math.max(1, height);
        fps = Math.max(0.1d, fps);
        durationSeconds = Math.max(0L, durationSeconds);
        maxPayloads = Math.max(0, maxPayloads);
        decodeWorkers = Math.max(1, decodeWorkers);
        statusIntervalMs = Math.max(0L, statusIntervalMs);
        sameSignalSeconds = Math.max(1L, sameSignalSeconds);
    }
}
