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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void encodeCreatesManifestAndDecodableQrFiles() throws Exception {
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
                List.of("txt"),
                List.of("build"),
                List.of(),
                null
        );

        assertEquals(1, summary.totalFileCount());
        assertEquals(sourceData.length, summary.totalOrigBytes());
        assertTrue(summary.totalQrCount() > 1);
        assertEquals(outDir.resolve("_manifest.txt"), summary.manifestPath());
        assertTrue(Files.exists(summary.manifestPath()));

        String manifest = Files.readString(summary.manifestPath(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("[docs/sample.txt]"));

        List<Path> qrFiles;
        try (Stream<Path> stream = Files.list(outDir.resolve("docs"))) {
            qrFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }

        assertEquals(summary.totalQrCount(), qrFiles.size());
        // The first PNG (sorted) is esi 0: the first systematic source symbol.
        QrPayloadSupport.ParsedPayload parsed = decodeQrPayload(qrFiles.getFirst());
        assertEquals("docs/sample.txt", parsed.relPath());
        assertEquals(0, parsed.esi());
    }

    @Test
    void encodeWithoutFolderStructureKeepsSameBasenameFilesDistinct() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Path fileA = srcDir.resolve("a/sample.txt");
        Path fileB = srcDir.resolve("b/sample.txt");
        Files.createDirectories(fileA.getParent());
        Files.createDirectories(fileB.getParent());
        Files.write(fileA, randomBytes(64, 101L));
        Files.write(fileB, randomBytes(64, 103L));

        Path outDir = tempDir.resolve("encoded");
        EncodeSummary summary = new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                40,
                false,
                false,
                false,
                500
        ).encode(srcDir, outDir, srcDir, List.of("txt"), List.of(), List.of(), null);

        assertEquals(2, summary.totalFileCount());

        List<Path> qrFiles;
        try (Stream<Path> stream = Files.walk(outDir)) {
            qrFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }

        // Both source files have basename sample.txt; without distinct names one would
        // overwrite the other and we would lose QR files.
        assertEquals(summary.totalQrCount(), qrFiles.size());
        long distinctNames = qrFiles.stream().map(p -> p.getFileName().toString()).distinct().count();
        assertEquals(qrFiles.size(), distinctNames);
    }

    @Test
    void encodeRejectsEncodeRootThatIsNotAncestorOfSource() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("sample.txt"), randomBytes(64, 201L));

        // encode-root is a sibling, not an ancestor: relativize would yield "../..".
        Path badRoot = tempDir.resolve("other/root");
        Files.createDirectories(badRoot);

        Path outDir = tempDir.resolve("encoded");
        EncodeService service = new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                40, false, false, false, 500);

        assertThrows(IllegalArgumentException.class, () -> service.encode(
                srcDir, outDir, badRoot,
                List.of("txt"), List.of(), List.of(), null));
        assertFalse(Files.exists(outDir));
    }

    @Test
    void reencodeRegeneratesFullSymbolStreamForFailedFiles() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("docs"));

        Path firstFile = srcDir.resolve("docs/first.txt");
        Path secondFile = srcDir.resolve("docs/second.txt");
        Files.write(firstFile, randomBytes(960, 11L));
        Files.write(secondFile, randomBytes(640, 13L));

        int chunkDataSize = 25;
        int firstTotalChunks;
        String firstFlatPrefix;
        int secondTotalChunks;
        String secondFlatPrefix;
        try (FileEncodingPlan firstPlan = FileEncodingPlan.fromSourceFile(firstFile, "docs/first.txt", false, false, chunkDataSize);
             FileEncodingPlan secondPlan = FileEncodingPlan.fromSourceFile(secondFile, "docs/second.txt", false, false, chunkDataSize)) {
            firstTotalChunks = firstPlan.totalChunks();
            firstFlatPrefix = firstPlan.flatSafePrefix();
            secondTotalChunks = secondPlan.totalChunks();
            secondFlatPrefix = secondPlan.flatSafePrefix();
        }
        assertTrue(firstTotalChunks >= 4);
        assertTrue(secondTotalChunks >= 1);

        Path resultPath = tempDir.resolve("restore/_restore_result.txt");
        Files.createDirectories(resultPath.getParent());
        Files.writeString(resultPath, String.join(System.lineSeparator(),
                "X docs/first.txt - INCOMPLETE (심볼 5/" + firstTotalChunks + " 소스, 복원 불가)",
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
        ).reencode(srcDir, outDir, srcDir, resultPath, null);

        assertEquals(2, summary.fileCount());   // first + second regenerated
        assertEquals(1, summary.errorCount());  // missing.txt absent

        List<String> outputNames;
        try (Stream<Path> stream = Files.list(outDir)) {
            outputNames = stream
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }

        assertEquals(summary.totalQrCount(), outputNames.size());
        // Each failed file is re-emitted as its full systematic stream plus a repair margin,
        // so at least one symbol per source symbol is present for each.
        long firstCount = outputNames.stream().filter(n -> n.startsWith(firstFlatPrefix)).count();
        long secondCount = outputNames.stream().filter(n -> n.startsWith(secondFlatPrefix)).count();
        assertTrue(firstCount >= firstTotalChunks);
        assertTrue(secondCount >= secondTotalChunks);
        assertEquals(firstCount + secondCount, summary.totalQrCount());
    }

    @Test
    void reencodeRejectsPathsOutsideSourceRoot() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("docs"));
        Path insideFile = srcDir.resolve("docs/inside.txt");
        Files.write(insideFile, randomBytes(320, 31L));
        int insideTotalChunks;
        try (FileEncodingPlan insidePlan = FileEncodingPlan.fromSourceFile(insideFile, "docs/inside.txt", false, false, 40)) {
            insideTotalChunks = insidePlan.totalChunks();
        }

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
        ).reencode(srcDir, outDir, srcDir, resultPath, null);

        assertEquals(1, summary.fileCount());
        assertEquals(1, summary.errorCount());
        // inside.txt is re-emitted as its full systematic stream plus repair margin.
        assertTrue(summary.totalQrCount() >= insideTotalChunks);
        assertFalse(Files.exists(outDir.resolve("escape.txt")));
    }

    @Test
    void cancelledEncodeRemovesFilesCreatedByCurrentRun() throws Exception {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("sample.txt"), randomBytes(2048, 41L));

        Path outDir = tempDir.resolve("encoded");
        AtomicInteger cancellationChecks = new AtomicInteger();

        assertThrows(java.util.concurrent.CancellationException.class, () -> new EncodeService(
                new QrImageWriter(450, 70, ErrorCorrectionLevel.M),
                30,
                false,
                false,
                false,
                500
        ).encode(
                srcDir,
                outDir,
                srcDir,
                List.of("txt"),
                List.of(),
                List.of(),
                null,
                () -> cancellationChecks.getAndIncrement() >= 2
        ));

        if (Files.exists(outDir)) {
            try (Stream<Path> stream = Files.walk(outDir)) {
                assertEquals(List.of(outDir), stream.sorted().toList());
            }
        }
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

    private static QrPayloadSupport.ParsedPayload decodeQrPayload(Path imagePath) throws Exception {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new MultiFormatReader().decode(bitmap, hints);
        return QrPayloadSupport.parsePayload(result.getText().getBytes(StandardCharsets.ISO_8859_1));
    }
}
