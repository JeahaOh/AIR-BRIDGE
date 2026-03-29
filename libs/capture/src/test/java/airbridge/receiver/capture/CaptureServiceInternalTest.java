package airbridge.receiver.capture;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureServiceInternalTest {

    @TempDir
    Path tempDir;

    @Test
    void restoreResumeStateRestoresUniquePayloadsAndHighestSavedIndex() throws Exception {
        Path outDir = tempDir.resolve("out");
        Path imagesDir = outDir.resolve("captured-images");
        Files.createDirectories(imagesDir);

        writeQrPng(imagesDir.resolve("frame_000002.png"), "payload-A");
        writeQrPng(imagesDir.resolve("frame_000010.png"), "payload-A");
        writeQrPng(imagesDir.resolve("frame_000011.png"), "payload-B");
        Files.writeString(imagesDir.resolve("frame_000005.png"), "not-a-real-png");

        List<String> logs = new ArrayList<>();
        CaptureService service = new CaptureService(
                new CaptureOptions(outDir, 0, 1280, 720, 15.0d, 0L, 0, 2, 0L, 10L, true),
                new CaptureListener() {
                    @Override
                    public void onLog(String line) {
                        logs.add(line);
                    }
                }
        );

        invoke(service, "restoreResumeState", new Class<?>[]{Path.class}, imagesDir);

        AtomicInteger savedImageCounter = getField(service, "savedImageCounter", AtomicInteger.class);
        Set<String> seenPayloads = getField(service, "seenPayloads", Set.class);

        assertEquals(11, savedImageCounter.get());
        assertEquals(Set.of("payload-A", "payload-B"), seenPayloads);
        assertTrue(logs.stream().anyMatch(line -> line.contains("resume scan started")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("resume skipped unreadable image: frame_000005.png")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("resume scan finished images=4 restoredPayloads=2 nextImageIndex=12")));
    }

    @Test
    void buildManifestJsonIncludesCurrentMetricsAndEscapesStrings() throws Exception {
        Path outDir = tempDir.resolve("manifest-out");
        Path imagesDir = outDir.resolve("captured-images");
        CaptureService service = new CaptureService(
                new CaptureOptions(outDir, 7, 1920, 1080, 15.5d, 0L, 0, 4, 1000L, 30L, false),
                null
        );

        getField(service, "totalFrames", AtomicLong.class).set(101);
        getField(service, "analyzedFrames", AtomicLong.class).set(88);
        getField(service, "decodedFrames", AtomicLong.class).set(12);
        getField(service, "blackFramesSkipped", AtomicLong.class).set(5);
        getField(service, "decodeFailures", AtomicLong.class).set(3);
        getField(service, "rawQueueOfferRetries", AtomicLong.class).set(4);
        getField(service, "rawQueueHighWaterMark", AtomicLong.class).set(17);
        getField(service, "saveQueueHighWaterMark", AtomicLong.class).set(9);
        getField(service, "fingerprintNanos", AtomicLong.class).set(1_250_000L);
        getField(service, "decodeNanos", AtomicLong.class).set(2_500_000L);
        getField(service, "saveNanos", AtomicLong.class).set(3_750_000L);
        getField(service, "savedImageCounter", AtomicInteger.class).set(6);
        getField(service, "seenPayloads", Set.class).add("payload-1");
        getField(service, "seenPayloads", Set.class).add("payload-2");
        setField(service, "stopReason", "stop \"quoted\"\nnext");

        String manifest = (String) invoke(
                service,
                "buildManifestJson",
                new Class<?>[]{Path.class, Path.class, String.class, String.class},
                outDir,
                imagesDir,
                "2026-03-29T00:00:00Z",
                "2026-03-29T00:01:00Z"
        );

        assertTrue(manifest.contains("\"command\": \"capture\""));
        assertTrue(manifest.contains("\"outputDir\": \"" + escapeJson(outDir.toString()) + "\""));
        assertTrue(manifest.contains("\"capturedImagesDir\": \"" + escapeJson(imagesDir.toString()) + "\""));
        assertTrue(manifest.contains("\"deviceIndex\": 7"));
        assertTrue(manifest.contains("\"fps\": 15.5"));
        assertTrue(manifest.contains("\"uniquePayloads\": 2"));
        assertTrue(manifest.contains("\"savedImages\": 6"));
        assertTrue(manifest.contains("\"decodeFailures\": 3"));
        assertTrue(manifest.contains("\"fingerprintMillis\": 1.250"));
        assertTrue(manifest.contains("\"decodeMillis\": 2.500"));
        assertTrue(manifest.contains("\"saveMillis\": 3.750"));
        assertTrue(manifest.contains("\"stopReason\": \"stop \\\"quoted\\\"\\nnext\""));
    }

    private static void writeQrPng(Path path, String payload) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(writer.encode(payload, BarcodeFormat.QR_CODE, 320, 320, hints));
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
