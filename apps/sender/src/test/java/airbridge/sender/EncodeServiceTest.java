package airbridge.sender;

import airbridge.common.QrPayloadSupport;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encodeCreatesManifestPrintHtmlAndDecodableQrFiles() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Path sourceFile = srcDir.resolve("docs/sample.txt");
        Files.createDirectories(sourceFile.getParent());
        byte[] sourceData = randomBytes(768, 7L);
        Files.write(sourceFile, sourceData);

        Path outDir = tempDir.resolve("encoded");
        EncodeService service = new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                40,
                false,
                false,
                true,
                500
        );

        EncodeSummary summary = service.encode(
                srcDir,
                outDir,
                srcDir,
                "TESTPROJ",
                List.of("txt"),
                List.of("build"),
                List.of(),
                true,
                null
        );

        assertEquals(1, summary.totalFileCount());
        assertEquals(sourceData.length, summary.totalOrigBytes());
        assertTrue(summary.totalQrCount() > 1);
        assertEquals(outDir.resolve("_manifest.txt"), summary.manifestPath());
        assertTrue(Files.exists(summary.manifestPath()));
        assertTrue(Files.exists(outDir.resolve("_print.html")));

        String manifest = Files.readString(summary.manifestPath(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("PROJECT: TESTPROJ"));
        assertTrue(manifest.contains("[docs/sample.txt]"));

        List<Path> qrFiles;
        try (Stream<Path> stream = Files.list(outDir.resolve("docs"))) {
            qrFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }

        assertEquals(summary.totalQrCount(), qrFiles.size());
        String decodedPayload = decodeQrText(qrFiles.getFirst());
        assertTrue(decodedPayload.startsWith("HDR" + QrPayloadSupport.HEADER_SEP));
        assertTrue(decodedPayload.contains("docs/sample.txt"));
    }

    @Test
    void reencodeRegeneratesOnlyRequestedChunksAndCountsMissingSources() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("docs"));

        Path firstFile = srcDir.resolve("docs/first.txt");
        Path secondFile = srcDir.resolve("docs/second.txt");
        Files.write(firstFile, randomBytes(960, 11L));
        Files.write(secondFile, randomBytes(640, 13L));

        int chunkDataSize = 25;
        FileEncodingPlan firstPlan = FileEncodingPlan.fromSourceFile(firstFile, "docs/first.txt", false, false, chunkDataSize);
        FileEncodingPlan secondPlan = FileEncodingPlan.fromSourceFile(secondFile, "docs/second.txt", false, false, chunkDataSize);
        assertTrue(firstPlan.totalChunks() >= 4);
        assertTrue(secondPlan.totalChunks() >= 1);

        Path resultPath = tempDir.resolve("restore/_restore_result.txt");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, String.join(System.lineSeparator(),
                "X docs/first.txt - INCOMPLETE (누락: [2, 4])",
                "X docs/second.txt - HASH_MISMATCH",
                "X docs/missing.txt - DECODE_ERROR"
        ), StandardCharsets.UTF_8);

        Path outDir = tempDir.resolve("reencoded");
        ReencodeSummary summary = new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                chunkDataSize,
                false,
                false,
                true,
                500
        ).reencode(srcDir, outDir, srcDir, resultPath, "TESTPROJ", null);

        assertEquals(2, summary.fileCount());
        assertEquals(1, summary.errorCount());
        assertEquals(2 + secondPlan.totalChunks(), summary.totalQrCount());

        List<String> outputNames;
        try (Stream<Path> stream = Files.list(outDir)) {
            outputNames = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertEquals(summary.totalQrCount(), outputNames.size());
        assertTrue(outputNames.contains(qrFileName(firstPlan.safePrefix(), 2, firstPlan.totalChunks())));
        assertTrue(outputNames.contains(qrFileName(firstPlan.safePrefix(), 4, firstPlan.totalChunks())));
        assertTrue(outputNames.contains(qrFileName(secondPlan.safePrefix(), 1, secondPlan.totalChunks())));
        assertFalse(outputNames.contains(qrFileName(firstPlan.safePrefix(), 1, firstPlan.totalChunks())));
    }

    @Test
    void reencodeRejectsPathsOutsideSourceRoot() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("docs"));
        Path insideFile = srcDir.resolve("docs/inside.txt");
        Files.write(insideFile, randomBytes(320, 31L));
        FileEncodingPlan insidePlan = FileEncodingPlan.fromSourceFile(insideFile, "docs/inside.txt", false, false, 40);

        Path escapedFile = tempDir.resolve("escape.txt");
        Files.write(escapedFile, randomBytes(512, 37L));

        Path resultPath = tempDir.resolve("restore/_restore_result.txt");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, String.join(System.lineSeparator(),
                "X ../escape.txt - HASH_MISMATCH",
                "X docs/inside.txt - DECODE_ERROR"
        ), StandardCharsets.UTF_8);

        Path outDir = tempDir.resolve("reencoded");
        ReencodeSummary summary = new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                40,
                false,
                false,
                true,
                500
        ).reencode(srcDir, outDir, srcDir, resultPath, "TESTPROJ", null);

        assertEquals(1, summary.fileCount());
        assertEquals(1, summary.errorCount());
        assertEquals(insidePlan.totalChunks(), summary.totalQrCount());
        assertFalse(Files.exists(outDir.resolve("escape.txt")));
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static String qrFileName(String safePrefix, int chunkIdx, int totalChunks) {
        int width = Math.max(3, String.valueOf(totalChunks).length());
        return String.format(Locale.ROOT, "%s_%0" + width + "dof%0" + width + "d.png",
                safePrefix, chunkIdx, totalChunks);
    }

    private static String decodeQrText(Path imagePath) throws Exception {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new MultiFormatReader().decode(bitmap, hints);
        return result.getText();
    }
}
