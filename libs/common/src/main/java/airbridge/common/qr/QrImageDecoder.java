package airbridge.common.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Shared QR-image decode machine for both the receiver (clean re-read of PNGs) and the
 * capture pipeline (noisy camera frames). Returns the decoded QR text; binary payload framing
 * is parsed by callers via {@code QrPayloadSupport}.
 *
 * <p>The retry skeleton is identical for both callers — try the original, then scaled-up
 * variants, then centered crops, then grid crops — and each step tries rotations 0/90/180/270
 * with two binarizers and the normal/try-harder hint sets. The differences (which scales/crops
 * to try, and whether to also try grayscale/black-and-white renders) are supplied per caller
 * through {@link Strategy}, so behavior is preserved exactly while the primitives and the
 * (ISO-8859-1) decode hints live in one place.
 */
public final class QrImageDecoder {
    private QrImageDecoder() {
    }

    /**
     * Tuning knobs that differ between callers. {@code scales}/{@code centeredRatios}/
     * {@code gridRatios} are tried in order; {@code gridSize} is the NxN crop grid; and
     * {@code colorVariants} adds grayscale and black-and-white renders per rotation.
     */
    public record Strategy(double[] scales,
                           double[] centeredRatios,
                           double[] gridRatios,
                           int gridSize,
                           boolean colorVariants) {
    }

    /** Tries every variant the {@code strategy} allows, throwing {@link NotFoundException} if none decode. */
    public static String decodeQrPayloadWithRetries(BufferedImage original, Strategy strategy) throws Exception {
        List<Map<DecodeHintType, ?>> hintsList = buildDecodeHintsList();

        String decoded = tryDecodeCandidateVariants(original, hintsList, strategy.colorVariants());
        if (decoded != null) {
            return decoded;
        }

        for (double scale : strategy.scales()) {
            BufferedImage scaled = scaleImage(original, scale);
            try {
                decoded = tryDecodeCandidateVariants(scaled, hintsList, strategy.colorVariants());
                if (decoded != null) {
                    return decoded;
                }
            } finally {
                scaled.flush();
            }
        }

        for (double ratio : strategy.centeredRatios()) {
            BufferedImage crop = cropCentered(original, ratio, ratio);
            try {
                decoded = tryDecodeCandidateVariants(crop, hintsList, strategy.colorVariants());
                if (decoded != null) {
                    return decoded;
                }
            } finally {
                crop.flush();
            }
        }

        for (double ratio : strategy.gridRatios()) {
            decoded = tryDecodeGridCrops(original, ratio, ratio, strategy.gridSize(), hintsList, strategy.colorVariants());
            if (decoded != null) {
                return decoded;
            }
        }

        throw NotFoundException.getNotFoundInstance();
    }

    private static List<Map<DecodeHintType, ?>> buildDecodeHintsList() {
        Map<DecodeHintType, Object> normalHints = new EnumMap<>(DecodeHintType.class);
        // ISO-8859-1 recovers the payload bytes 1:1 (QR 8-bit byte mode, no ECI).
        normalHints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        normalHints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));

        Map<DecodeHintType, Object> tryHarderHints = new EnumMap<>(normalHints);
        tryHarderHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        return Arrays.asList(normalHints, tryHarderHints);
    }

    private static String tryDecodeCandidateVariants(BufferedImage source,
                                                     List<Map<DecodeHintType, ?>> hintsList,
                                                     boolean colorVariants) throws Exception {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }

        int[] rotations = {0, 90, 180, 270};
        for (int rotation : rotations) {
            BufferedImage candidate = rotation == 0 ? source : rotateImage(source, rotation);
            try {
                String decoded = tryDecodeImage(candidate, hintsList);
                if (decoded != null) {
                    return decoded;
                }

                if (colorVariants) {
                    BufferedImage gray = toGray(candidate);
                    try {
                        decoded = tryDecodeImage(gray, hintsList);
                        if (decoded != null) {
                            return decoded;
                        }
                    } finally {
                        gray.flush();
                    }

                    BufferedImage bw = toBlackAndWhite(candidate);
                    try {
                        decoded = tryDecodeImage(bw, hintsList);
                        if (decoded != null) {
                            return decoded;
                        }
                    } finally {
                        bw.flush();
                    }
                }
            } finally {
                if (rotation != 0) {
                    candidate.flush();
                }
            }
        }
        return null;
    }

    private static String tryDecodeImage(BufferedImage image, List<Map<DecodeHintType, ?>> hintsList) {
        // One luminance source and one BinaryBitmap per binarizer for all hint sets: the
        // bitmap caches its black matrix, so the (expensive) binarization is not recomputed
        // for the try-harder pass. Every binarizer/hints combination is still attempted.
        BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
        List<BinaryBitmap> bitmaps = Arrays.asList(
                new BinaryBitmap(new HybridBinarizer(source)),
                new BinaryBitmap(new GlobalHistogramBinarizer(source))
        );

        for (BinaryBitmap bitmap : bitmaps) {
            for (Map<DecodeHintType, ?> hints : hintsList) {
                MultiFormatReader reader = new MultiFormatReader();
                try {
                    Result result = reader.decode(bitmap, hints);
                    if (result != null && result.getText() != null && !result.getText().isEmpty()) {
                        return result.getText();
                    }
                } catch (ReaderException ignored) {
                } finally {
                    reader.reset();
                }
            }
        }
        return null;
    }

    private static String tryDecodeGridCrops(BufferedImage source,
                                             double widthRatio,
                                             double heightRatio,
                                             int gridSize,
                                             List<Map<DecodeHintType, ?>> hintsList,
                                             boolean colorVariants) throws Exception {
        int cropWidth = Math.max(1, (int) Math.round(source.getWidth() * widthRatio));
        int cropHeight = Math.max(1, (int) Math.round(source.getHeight() * heightRatio));
        int maxX = Math.max(0, source.getWidth() - cropWidth);
        int maxY = Math.max(0, source.getHeight() - cropHeight);

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                int x = gridSize == 1 ? maxX / 2 : (int) Math.round(maxX * (gx / (double) (gridSize - 1)));
                int y = gridSize == 1 ? maxY / 2 : (int) Math.round(maxY * (gy / (double) (gridSize - 1)));
                BufferedImage subImage = source.getSubimage(
                        x, y,
                        Math.min(cropWidth, source.getWidth() - x),
                        Math.min(cropHeight, source.getHeight() - y)
                );
                BufferedImage crop = copyBufferedImage(subImage);
                try {
                    String decoded = tryDecodeCandidateVariants(crop, hintsList, colorVariants);
                    if (decoded != null) {
                        return decoded;
                    }
                } finally {
                    crop.flush();
                }
            }
        }
        return null;
    }

    private static BufferedImage cropCentered(BufferedImage source, double widthRatio, double heightRatio) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * widthRatio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * heightRatio));
        int x = Math.max(0, (source.getWidth() - width) / 2);
        int y = Math.max(0, (source.getHeight() - height) / 2);
        BufferedImage subImage = source.getSubimage(
                x, y,
                Math.min(width, source.getWidth() - x),
                Math.min(height, source.getHeight() - y)
        );
        return copyBufferedImage(subImage);
    }

    private static BufferedImage scaleImage(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage rotateImage(BufferedImage source, int degrees) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = degrees == 90 || degrees == 270;
        BufferedImage rotated = new BufferedImage(
                swap ? height : width,
                swap ? width : height,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = rotated.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(rotated.getWidth() / 2.0, rotated.getHeight() / 2.0);
        g.rotate(Math.toRadians(degrees));
        g.translate(-width / 2.0, -height / 2.0);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rotated;
    }

    private static BufferedImage toGray(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return gray;
    }

    private static BufferedImage toBlackAndWhite(BufferedImage source) {
        BufferedImage bw = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = bw.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, bw.getWidth(), bw.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return bw;
    }

    private static BufferedImage copyBufferedImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, copy.getWidth(), copy.getHeight());
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }
}
