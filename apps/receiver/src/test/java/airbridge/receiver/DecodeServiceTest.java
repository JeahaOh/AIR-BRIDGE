package airbridge.receiver;

import airbridge.common.CodecSupport;
import airbridge.common.QrPayloadSupport;
import airbridge.common.fountain.LtFountain;
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
        List<Path> qrFiles = writeQrChunks(batchDir, "docs/sample.bin", sourceData, 35, null, List.of());

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
    void decodeRestoresOnceAndIgnoresDuplicateChunksAfterCompletion() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path batch1 = inputDir.resolve("batch1");
        Path batch2 = inputDir.resolve("batch2");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(batch1);
        Files.createDirectories(batch2);

        byte[] sourceData = randomBytes(900, 53L);
        // Two independent copies of the same file's chunks: once the first copy completes the
        // file it is restored and evicted, so the second copy must be ignored, not re-restored
        // or reported as an error.
        writeQrChunks(batch1, "docs/dup.bin", sourceData, 30, null, List.of());
        writeQrChunks(batch2, "docs/dup.bin", sourceData, 30, null, List.of());

        DecodeSummary summary = new DecodeService(4)
                .decode(inputDir, outputDir, QrDecodeSupport.collectQrImageFiles(inputDir), null);

        assertEquals(1, summary.restoredCount());
        assertEquals(0, summary.incompleteCount());
        assertEquals(0, summary.hashMismatchCount());
        assertEquals(0, summary.decodeErrorCount());
        assertArrayEquals(sourceData, Files.readAllBytes(outputDir.resolve("docs/dup.bin")));
    }

    @Test
    void decodeReportsIncompleteHashMismatchAndQrReadErrors() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path batchDir = inputDir.resolve("batch");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(batchDir);

        writeQrChunks(batchDir, "docs/lost.bin", randomBytes(320, 19L), 40, null, List.of(1));
        writeQrChunks(batchDir, "docs/bad.bin", randomBytes(220, 23L), 1000, "deadbeefdeadbeef", List.of());
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

        writeQrChunks(batchDir, "../escape.bin", randomBytes(320, 29L), 1000, null, List.of());

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

    // Writes the systematic fountain symbols (one per source symbol, no repair) for `data`.
    // `missingSymbols` lists 1-based symbol labels to omit; since no repair symbols are
    // emitted, dropping any source symbol leaves the file undecodable (INCOMPLETE).
    private static List<Path> writeQrChunks(Path dir,
                                            String relPath,
                                            byte[] data,
                                            int chunkDataSize,
                                            String forcedHash16,
                                            List<Integer> missingSymbols) throws Exception {
        byte[] encoded = CodecSupport.compress(data);
        String hash16 = forcedHash16 != null ? forcedHash16 : CodecSupport.sha256Hex(data).substring(0, 16);
        int k = Math.max(1, (int) Math.ceil((double) encoded.length / chunkDataSize));
        byte[][] source = splitIntoSymbols(encoded, k, chunkDataSize);
        List<Path> paths = new ArrayList<>();

        for (int esi = 0; esi < k; esi++) {
            int label = esi + 1;
            if (missingSymbols.contains(label)) {
                continue;
            }
            byte[] symbol = LtFountain.encodeSymbol(esi, k, source); // systematic: source[esi]
            byte[] payload = QrPayloadSupport.buildPayload(relPath, hash16, k, encoded.length, esi, symbol);

            String prefix = relPath.replace('/', '_').replace('.', '_');
            Path qrFile = dir.resolve(String.format("%s-%03d.png", prefix, label));
            writeQrFile(qrFile, payload);
            paths.add(qrFile);
        }

        return paths;
    }

    private static byte[][] splitIntoSymbols(byte[] data, int k, int symbolSize) {
        byte[][] symbols = new byte[k][];
        for (int i = 0; i < k; i++) {
            byte[] sym = new byte[symbolSize];
            int start = i * symbolSize;
            int n = Math.min(symbolSize, data.length - start);
            if (n > 0) {
                System.arraycopy(data, start, sym, 0, n);
            }
            symbols[i] = sym;
        }
        return symbols;
    }

    private static void writeQrFile(Path path, byte[] payload) throws Exception {
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);

        String content = new String(payload, StandardCharsets.ISO_8859_1);
        var matrix = writer.encode(content, BarcodeFormat.QR_CODE, 420, 420, hints);
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ImageIO.write(image, "PNG", path.toFile());
    }
}
