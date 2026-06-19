package airbridge.receiver.capture;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public interface CaptureListener {
    default void onLog(String line) {
    }

    /**
     * Called once the camera is open and the capture loop is about to start grabbing frames,
     * i.e. the moment the receiver is ready to receive. Listeners use this to show a clear
     * "now start the slides" signal.
     */
    default void onReady() {
    }

    default void onPreviewFrame(BufferedImage image) {
    }

    default void onStatus(CaptureStatus status) {
    }

    default void onSavedImage(Path imagePath, String payload, int savedCount) {
    }

    default void onFinished(CaptureSummary summary) {
    }
}
