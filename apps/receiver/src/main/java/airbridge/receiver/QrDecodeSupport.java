package airbridge.receiver;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import airbridge.common.QrPayloadSupport;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class QrDecodeSupport {
    private QrDecodeSupport() {
    }

    static List<Path> collectQrImageFiles(Path rootDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.endsWith(".png")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files);
        return files;
    }

    static QrDecodeTaskResult decodeTask(int index, Path qrFile, int maxAttempts, long retryDelayMs) {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return QrDecodeTaskResult.success(index, qrFile, decodeChunk(qrFile), attempt);
            } catch (Throwable t) {
                lastError = t;
                if (attempt >= maxAttempts || !isRetryableDecodeFailure(t)) {
                    break;
                }
                prepareDecodeRetry(t, attempt, qrFile, maxAttempts, retryDelayMs);
            }
        }
        return QrDecodeTaskResult.failure(index, qrFile, lastError, maxAttempts);
    }

    static String decodeQrPayloadWithRetries(BufferedImage originalImage) throws Exception {
        List<Map<DecodeHintType, ?>> hintsList = buildDecodeHintsList();
        Exception lastException = null;

        String decoded = tryDecodeCandidateVariants(originalImage, hintsList);
        if (decoded != null) {
            return decoded;
        }

        double[] scales = {1.5, 2.0, 3.0};
        for (double scale : scales) {
            BufferedImage scaled = scaleImage(originalImage, scale);
            try {
                decoded = tryDecodeCandidateVariants(scaled, hintsList);
                if (decoded != null) {
                    return decoded;
                }
            } finally {
                if (scaled != null) {
                    scaled.flush();
                }
            }
        }

        double[] centeredRatios = {0.9, 0.8, 0.7, 0.6, 0.5};
        for (double ratio : centeredRatios) {
            BufferedImage crop = cropCentered(originalImage, ratio, ratio);
            try {
                decoded = tryDecodeCandidateVariants(crop, hintsList);
                if (decoded != null) {
                    return decoded;
                }
            } finally {
                if (crop != null) {
                    crop.flush();
                }
            }
        }

        double[] gridRatios = {0.85, 0.7, 0.55};
        for (double ratio : gridRatios) {
            decoded = tryDecodeGridCrops(originalImage, ratio, ratio, 3, hintsList);
            if (decoded != null) {
                return decoded;
            }
        }

        throw NotFoundException.getNotFoundInstance();
    }

    static String formatDecodeException(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        if (e instanceof NotFoundException) {
            return "QR 코드를 찾지 못했습니다";
        }
        if (e instanceof ChecksumException) {
            return "QR 체크섬 검증에 실패했습니다";
        }
        if (e instanceof FormatException) {
            return "QR 포맷 해석에 실패했습니다";
        }
        return e.getClass().getSimpleName();
    }

    static String formatDecodeThrowable(Throwable t) {
        if (t instanceof Exception e) {
            return formatDecodeException(e);
        }
        String message = t.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message;
        }
        return t.getClass().getSimpleName();
    }

    private static QrDecodedChunk decodeChunk(Path qrFile) throws Exception {
        BufferedImage image = ImageIO.read(qrFile.toFile());
        if (image == null) {
            throw new IOException("이미지를 읽을 수 없습니다");
        }
        try {
            String payload = decodeQrPayloadWithRetries(image);
            return parsePayload(payload);
        } finally {
            image.flush();
        }
    }

    private static boolean isRetryableDecodeFailure(Throwable t) {
        if (t instanceof OutOfMemoryError) {
            return true;
        }
        String message = t.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("heap space");
    }

    private static void prepareDecodeRetry(Throwable t, int attempt, Path qrFile, int maxAttempts, long retryDelayMs) {
        if (t instanceof OutOfMemoryError) {
            System.gc();
        }
        System.out.printf("  [RETRY %d/%d] %s - %s%n",
                attempt + 1,
                maxAttempts,
                qrFile.getFileName(),
                formatDecodeThrowable(t));
        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String decodeQrText(BufferedImage image, Map<DecodeHintType, ?> hints)
            throws NotFoundException, ChecksumException, FormatException {
        BufferedImageLuminanceSource source = new BufferedImageLuminanceSource(image);
        List<BinaryBitmap> bitmaps = Arrays.asList(
                new BinaryBitmap(new HybridBinarizer(source)),
                new BinaryBitmap(new GlobalHistogramBinarizer(source))
        );

        for (BinaryBitmap bitmap : bitmaps) {
            MultiFormatReader reader = new MultiFormatReader();
            try {
                Result result = reader.decode(bitmap, hints);
                if (result != null && result.getText() != null && !result.getText().isEmpty()) {
                    return result.getText();
                }
            } finally {
                reader.reset();
            }
        }

        throw NotFoundException.getNotFoundInstance();
    }

    private static List<Map<DecodeHintType, ?>> buildDecodeHintsList() {
        Map<DecodeHintType, Object> normalHints = new EnumMap<>(DecodeHintType.class);
        normalHints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        normalHints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));

        Map<DecodeHintType, Object> tryHarderHints = new EnumMap<>(normalHints);
        tryHarderHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        return Arrays.asList(normalHints, tryHarderHints);
    }

    private static String tryDecodeCandidateVariants(BufferedImage source,
                                                     List<Map<DecodeHintType, ?>> hintsList) throws Exception {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }

        int[] rotations = {0, 90, 180, 270};
        for (int rotation : rotations) {
            BufferedImage candidate = rotation == 0 ? source : rotateImage(source, rotation);
            try {
                for (Map<DecodeHintType, ?> hints : hintsList) {
                    try {
                        return decodeQrText(candidate, hints);
                    } catch (NotFoundException | ChecksumException | FormatException ignored) {
                    }
                }
            } finally {
                if (rotation != 0 && candidate != null) {
                    candidate.flush();
                }
            }
        }
        return null;
    }

    private static String tryDecodeGridCrops(BufferedImage source,
                                             double widthRatio,
                                             double heightRatio,
                                             int gridSize,
                                             List<Map<DecodeHintType, ?>> hintsList) throws Exception {
        int cropWidth = Math.max(1, (int) Math.round(source.getWidth() * widthRatio));
        int cropHeight = Math.max(1, (int) Math.round(source.getHeight() * heightRatio));
        int maxX = Math.max(0, source.getWidth() - cropWidth);
        int maxY = Math.max(0, source.getHeight() - cropHeight);

        for (int gy = 0; gy < gridSize; gy++) {
            for (int gx = 0; gx < gridSize; gx++) {
                int x = (gridSize == 1) ? 0 : (maxX * gx) / (gridSize - 1);
                int y = (gridSize == 1) ? 0 : (maxY * gy) / (gridSize - 1);
                BufferedImage crop = copyBufferedImage(source.getSubimage(x, y, cropWidth, cropHeight));
                try {
                    String decoded = tryDecodeCandidateVariants(crop, hintsList);
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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage rotateImage(BufferedImage source, int degrees) {
        if (degrees % 360 == 0) {
            return source;
        }

        double radians = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        int dstWidth = (int) Math.floor(srcWidth * cos + srcHeight * sin);
        int dstHeight = (int) Math.floor(srcHeight * cos + srcWidth * sin);

        BufferedImage rotated = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, dstWidth, dstHeight);
        g.translate((dstWidth - srcWidth) / 2.0, (dstHeight - srcHeight) / 2.0);
        g.rotate(radians, srcWidth / 2.0, srcHeight / 2.0);
        g.drawRenderedImage(source, null);
        g.dispose();
        return rotated;
    }

    private static BufferedImage copyBufferedImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static QrDecodedChunk parsePayload(String payload) {
        String[] payloadParts = payload.split(QrPayloadSupport.HEADER_SEP, 3);
        if (payloadParts.length != 3 || !"HDR".equals(payloadParts[0])) {
            throw new IllegalArgumentException("지원하지 않는 페이로드 형식");
        }

        String[] fields = payloadParts[1].split(QrPayloadSupport.FIELD_SEP, -1);
        if (fields.length != 5) {
            throw new IllegalArgumentException("헤더 필드 수가 올바르지 않습니다");
        }

        return new QrDecodedChunk(
                fields[0],
                fields[1],
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]),
                fields[4],
                payloadParts[2]
        );
    }
}
