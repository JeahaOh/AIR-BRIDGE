package airbridge.receiver;

import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import airbridge.common.QrPayloadSupport;
import airbridge.common.qr.QrImageDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class QrDecodeSupport {
    // Tuned for clean re-reads of saved PNGs: more scale-ups and crops, no color renders.
    private static final QrImageDecoder.Strategy STRATEGY = new QrImageDecoder.Strategy(
            new double[]{1.5, 2.0, 3.0},
            new double[]{0.9, 0.8, 0.7, 0.6, 0.5},
            new double[]{0.85, 0.7, 0.55},
            3,
            false
    );

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
            String payload = QrImageDecoder.decodeQrPayloadWithRetries(image, STRATEGY);
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

    private static QrDecodedChunk parsePayload(String payload) {
        // The decode pipeline returns the payload as an ISO-8859-1 string (one char per byte);
        // recover the original bytes losslessly before parsing the binary frame.
        byte[] bytes = payload.getBytes(StandardCharsets.ISO_8859_1);
        QrPayloadSupport.ParsedPayload parsed = QrPayloadSupport.parsePayload(bytes);
        return new QrDecodedChunk(
                parsed.relPath(),
                parsed.hash16(),
                parsed.k(),
                parsed.gzipLen(),
                parsed.esi(),
                parsed.symbolData()
        );
    }
}
