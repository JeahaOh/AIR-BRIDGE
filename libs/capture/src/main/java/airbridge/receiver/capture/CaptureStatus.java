package airbridge.receiver.capture;

public record CaptureStatus(
        long totalFrames,
        long analyzedFrames,
        long decodedFrames,
        long uniquePayloads,
        long savedImages,
        long blackFramesSkipped,
        long decodeFailures,
        String stopReason
) {
}
