package airbridge.receiver;

import airbridge.common.CodecSupport;
import airbridge.common.QrPayloadSupport;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecodeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void decodeRestoresFileAndMovesQrFilesToSuccessDirectory() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path batchDir = inputDir.resolve("batch");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(batchDir);

        byte[] sourceData = randomBytes(640, 17L);
        List<Path> qrFiles = writeQrChunks(batchDir, "TESTPROJ", "docs/sample.bin", sourceData, 35, null, List.of());

        DecodeSummary summary = new DecodeService(1)
                .decode(inputDir, outputDir, QrDecodeSupport.collectQrImageFiles(inputDir), null);

        assertEquals(outputDir.resolve("_restore_result.txt"), summary.reportPath());
        assertEquals(1, summary.restoredCount());
        assertEquals(0, summary.incompleteCount());
        assertEquals(0, summary.hashMismatchCount());
        assertEquals(0, summary.decodeErrorCount());
        assertArrayEquals(sourceData, Files.readAllBytes(outputDir.resolve("docs/sample.bin")));

        Path successDir = inputDir.resolve("batch-success");
        assertTrue(Files.isDirectory(successDir));
        try (Stream<Path> stream = Files.list(successDir)) {
            assertEquals(qrFiles.size(), stream.filter(path -> path.getFileName().toString().endsWith(".png")).count());
        }
        try (Stream<Path> stream = Files.list(batchDir)) {
            assertEquals(0, stream.filter(path -> path.getFileName().toString().endsWith(".png")).count());
        }

        String report = Files.readString(summary.reportPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("O docs/sample.bin - OK"));
    }

    @Test
    void decodeReportsIncompleteHashMismatchAndQrReadErrors() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path batchDir = inputDir.resolve("batch");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(batchDir);

        writeQrChunks(batchDir, "TESTPROJ", "docs/lost.bin", randomBytes(320, 19L), 40, null, List.of(1));
        writeQrChunks(batchDir, "TESTPROJ", "docs/bad.bin", randomBytes(220, 23L), 1000, "deadbeefdeadbeef", List.of());
        ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "PNG", batchDir.resolve("invalid.png").toFile());

        DecodeSummary summary = new DecodeService(1)
                .decode(inputDir, outputDir, QrDecodeSupport.collectQrImageFiles(inputDir), null);

        assertEquals(0, summary.restoredCount());
        assertEquals(1, summary.incompleteCount());
        assertEquals(1, summary.hashMismatchCount());
        assertEquals(1, summary.decodeErrorCount());
        assertFalse(Files.exists(outputDir.resolve("docs/lost.bin")));
        assertFalse(Files.exists(outputDir.resolve("docs/bad.bin")));

        String report = Files.readString(summary.reportPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("! batch/invalid.png - QR_READ_ERROR"));
        assertTrue(report.contains("X docs/lost.bin - INCOMPLETE"));
        assertTrue(report.contains("X docs/bad.bin - HASH_MISMATCH"));
    }

    @Test
    void decodeRejectsRelativePathTraversalFromPayload() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path batchDir = inputDir.resolve("batch");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(batchDir);

        writeQrChunks(batchDir, "TESTPROJ", "../escape.bin", randomBytes(320, 29L), 1000, null, List.of());

        DecodeSummary summary = new DecodeService(1)
                .decode(inputDir, outputDir, QrDecodeSupport.collectQrImageFiles(inputDir), null);

        assertEquals(0, summary.restoredCount());
        assertEquals(1, summary.decodeErrorCount());
        assertFalse(Files.exists(tempDir.resolve("escape.bin")));
        assertFalse(Files.exists(outputDir.resolve("../escape.bin").normalize()));

        String report = Files.readString(summary.reportPath(), StandardCharsets.UTF_8);
        assertTrue(report.contains("! batch/___escape_bin-001.png - INVALID_REL_PATH"));
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static List<Path> writeQrChunks(Path dir,
                                            String project,
                                            String relPath,
                                            byte[] data,
                                            int chunkDataSize,
                                            String forcedHash16,
                                            List<Integer> missingChunks) throws Exception {
        String encoded = CodecSupport.compressAndEncode(data);
        String hash16 = forcedHash16 != null ? forcedHash16 : CodecSupport.sha256Hex(data).substring(0, 16);
        int totalChunks = Math.max(1, (int) Math.ceil((double) encoded.length() / chunkDataSize));
        List<Path> paths = new ArrayList<>();

        for (int index = 0; index < totalChunks; index++) {
            int chunkIdx = index + 1;
            if (missingChunks.contains(chunkIdx)) {
                continue;
            }
            int start = index * chunkDataSize;
            int end = Math.min(encoded.length(), start + chunkDataSize);
            String payload = QrPayloadSupport.buildPayload(
                    project,
                    relPath,
                    chunkIdx,
                    totalChunks,
                    hash16,
                    encoded.substring(start, end)
            );

            String prefix = relPath.replace('/', '_').replace('.', '_');
            Path qrFile = dir.resolve(String.format("%s-%03d.png", prefix, chunkIdx));
            writeQrFile(qrFile, payload);
            paths.add(qrFile);
        }

        return paths;
    }

    private static void writeQrFile(Path path, String payload) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);

        var matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 420, 420, hints);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ImageIO.write(image, "PNG", path.toFile());
    }
}
