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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QrDecodeSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void collectQrImageFilesReturnsSortedPngFilesOnly() throws Exception {
        Files.createDirectories(tempDir.resolve("nested"));
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "PNG", tempDir.resolve("b.png").toFile());
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "PNG", tempDir.resolve("nested/a.png").toFile());
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "JPG", tempDir.resolve("ignored.jpg").toFile());

        List<Path> files = QrDecodeSupport.collectQrImageFiles(tempDir);

        assertEquals(List.of(tempDir.resolve("b.png"), tempDir.resolve("nested/a.png")).stream().sorted().toList(), files);
    }

    @Test
    void decodeTaskReturnsChunkForValidQrImage() throws Exception {
        byte[] sourceData = "receiver decode smoke".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String encoded = CodecSupport.compressAndEncode(sourceData);
        String hash16 = CodecSupport.sha256Hex(sourceData).substring(0, 16);
        Path qrFile = tempDir.resolve("valid.png");
        writeQrFile(qrFile, QrPayloadSupport.buildPayload("TESTPROJ", "docs/sample.txt", 1, 1, hash16, encoded));

        QrDecodeTaskResult result = QrDecodeSupport.decodeTask(0, qrFile, 1, 0);

        assertNull(result.error);
        assertNotNull(result.chunk);
        assertEquals(0, result.index);
        assertEquals(qrFile, result.qrFile);
        assertEquals(1, result.attempts);
        assertEquals("TESTPROJ", result.chunk.project);
        assertEquals("docs/sample.txt", result.chunk.relPath);
        assertEquals(1, result.chunk.chunkIdx);
        assertEquals(1, result.chunk.totalChunks);
        assertEquals(hash16, result.chunk.hash16);
        assertEquals(encoded, result.chunk.chunkData);
    }

    @Test
    void decodeTaskReturnsFailureForInvalidImage() throws Exception {
        Path invalid = tempDir.resolve("invalid.png");
        ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "PNG", invalid.toFile());

        QrDecodeTaskResult result = QrDecodeSupport.decodeTask(3, invalid, 1, 0);

        assertNotNull(result.error);
        assertNull(result.chunk);
        assertEquals(3, result.index);
        assertEquals(invalid, result.qrFile);
        assertEquals(1, result.attempts);
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
