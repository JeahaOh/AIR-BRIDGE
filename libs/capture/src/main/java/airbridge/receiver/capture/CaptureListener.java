package airbridge.receiver.capture;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

public interface CaptureListener {
    default void onLog(String line) {
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
