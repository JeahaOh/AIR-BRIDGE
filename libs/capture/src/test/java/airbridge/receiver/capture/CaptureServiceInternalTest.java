package airbridge.receiver.capture;

import airbridge.common.QrPayloadSupport;
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
import java.nio.charset.StandardCharsets;
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
        Set<?> seenPayloads = getField(service, "seenPayloads", Set.class);

        assertEquals(11, savedImageCounter.get());
        // The dedupe set stores payload digests, not the payload strings themselves.
        assertEquals(2, seenPayloads.size());
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
        // One decodable single-symbol file plus one foreign payload, to pin the new fields.
        invoke(service, "trackCompletion", new Class<?>[]{String.class}, fountainPayload("m.txt", 1, 0));
        invoke(service, "trackCompletion", new Class<?>[]{String.class}, "not-a-fountain-frame");

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
        assertTrue(manifest.contains("\"observedFiles\": 1"));
        assertTrue(manifest.contains("\"decodableFiles\": 1"));
        assertTrue(manifest.contains("\"unparsedPayloads\": 1"));
        assertTrue(manifest.contains("\"decodeFailures\": 3"));
        assertTrue(manifest.contains("\"fingerprintMillis\": 1.250"));
        assertTrue(manifest.contains("\"decodeMillis\": 2.500"));
        assertTrue(manifest.contains("\"saveMillis\": 3.750"));
        assertTrue(manifest.contains("\"stopReason\": \"stop \\\"quoted\\\"\\nnext\""));
    }

    @Test
    void trackCompletionAnnouncesDecodableFilesAndPushesStatus() throws Exception {
        List<String> logs = new ArrayList<>();
        List<CaptureStatus> statuses = new ArrayList<>();
        CaptureService service = new CaptureService(
                new CaptureOptions(tempDir.resolve("done-out"), 0, 1280, 720, 15.0d, 0L, 0, 2, 0L, 10L, false),
                new CaptureListener() {
                    @Override
                    public void onLog(String line) {
                        logs.add(line);
                    }

                    @Override
                    public void onStatus(CaptureStatus status) {
                        statuses.add(status);
                    }
                }
        );
        Class<?>[] sig = {String.class};

        invoke(service, "trackCompletion", sig, fountainPayload("dir/file.txt", 2, 0));
        assertTrue(logs.isEmpty(), "no announcement before the file is decodable");
        assertTrue(statuses.isEmpty());

        invoke(service, "trackCompletion", sig, fountainPayload("dir/file.txt", 2, 1));
        assertTrue(logs.stream().anyMatch(line ->
                line.contains("[CAPTURE][DONE] dir/file.txt") && line.contains("decode로 복원 가능")));
        assertTrue(logs.stream().anyMatch(line ->
                line.contains("관측된 파일 1개 모두 복원 가능") && line.contains("slide를 정지해도 됩니다")));
        assertEquals(1, statuses.size(), "status is pushed immediately, without waiting a status interval");
        assertEquals(1, statuses.get(0).observedFiles());
        assertEquals(1, statuses.get(0).decodableFiles());

        // A newly observed, still-incomplete file must not re-trigger the all-decodable banner.
        logs.clear();
        invoke(service, "trackCompletion", sig, fountainPayload("later.txt", 2, 0));
        assertTrue(logs.isEmpty());
    }

    @Test
    void recommendedDwellMsScalesWithObservedUniqueRate() throws Exception {
        CaptureService service = new CaptureService(
                new CaptureOptions(tempDir.resolve("o"), 0, 1280, 720, 15.0d, 0L, 0, 2, 0L, 10L, false),
                null
        );
        Class<?>[] sig = {long.class, long.class};

        // Too little captured to advise.
        assertEquals(-1L, (long) (Long) invoke(service, "recommendedDwellMs", sig, 1L, 1000L));
        assertEquals(-1L, (long) (Long) invoke(service, "recommendedDwellMs", sig, 0L, 5000L));

        // 10 unique symbols in 5000ms -> 500ms/symbol * 1.3 safety = 650ms.
        assertEquals(650L, (long) (Long) invoke(service, "recommendedDwellMs", sig, 10L, 5000L));
        // Faster capture -> shorter recommended dwell.
        assertTrue((long) (Long) invoke(service, "recommendedDwellMs", sig, 50L, 5000L)
                < (long) (Long) invoke(service, "recommendedDwellMs", sig, 10L, 5000L));
    }

    // Builds the ISO-8859-1 payload string of one all-zero fountain symbol frame.
    private static String fountainPayload(String relPath, int k, int esi) {
        byte[] frame = QrPayloadSupport.buildPayload(relPath, "0123456789abcdef", k, k * 8, esi, new byte[8]);
        return new String(frame, StandardCharsets.ISO_8859_1);
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
