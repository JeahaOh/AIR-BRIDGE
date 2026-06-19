package airbridge.receiver.capture;

import airbridge.common.qr.QrImageDecoder;

import java.awt.image.BufferedImage;

/**
 * Capture-side QR decode. Frames come from a noisy camera channel, so the strategy adds
 * grayscale and black-and-white renders and a couple of scale-ups; the decode machine itself
 * is shared with the receiver via {@link QrImageDecoder}. The decoded text is used only to
 * dedup frames by payload identity (the raw PNG is what gets saved).
 */
final class CaptureQrDecodeSupport {
    // Tuned for live camera frames: fewer scale-ups, color renders enabled.
    private static final QrImageDecoder.Strategy STRATEGY = new QrImageDecoder.Strategy(
            new double[]{1.5, 2.0},
            new double[]{0.9, 0.75, 0.6},
            new double[]{0.75},
            3,
            true
    );

    private CaptureQrDecodeSupport() {
    }

    static String decodeQrPayloadWithRetries(BufferedImage originalImage) throws Exception {
        return QrImageDecoder.decodeQrPayloadWithRetries(originalImage, STRATEGY);
    }
}
