package airbridge.receiver;

import airbridge.sender.Sender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void senderAndReceiverCommandsRoundTripNestedFile() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path sourceFile = sourceDir.resolve("nested/sample.txt");
        Files.createDirectories(sourceFile.getParent());
        byte[] sourceData = randomBytes(768, 29L);
        Files.write(sourceFile, sourceData);

        Path qrDir = tempDir.resolve("qr");
        Path restoredDir = tempDir.resolve("restored");

        int encodeExit = new CommandLine(new Sender()).execute(
                "encode",
                "--in", sourceDir.toString(),
                "--out", qrDir.toString(),
                "--target-extensions", "txt",
                "--chunk-data-size", "40",
                "--qr-image-size", "420",
                "--label-height", "60"
        );
        int decodeExit = new CommandLine(new Receiver()).execute(
                "decode",
                "--in", qrDir.toString(),
                "--out", restoredDir.toString(),
                "--decode-workers", "1"
        );

        assertEquals(0, encodeExit);
        assertEquals(0, decodeExit);
        assertArrayEquals(sourceData, Files.readAllBytes(restoredDir.resolve("nested/sample.txt")));
        assertTrue(!Files.exists(qrDir.resolve("nested-success")));
        assertEquals(0, countPngFiles(qrDir));
        assertTrue(Files.readString(restoredDir.resolve("_restore_result.txt"), StandardCharsets.UTF_8)
                .contains("O nested/sample.txt - OK"));
    }

    @Test
    void encodeRootSetsPayloadRelativePathAboveTheInputDirectory() throws Exception {
        // --in is a subdirectory; --encode-root is its ancestor, so the QR payload's relative
        // path (and therefore the restored location) is taken from the encode-root, not --in.
        Path encodeRoot = tempDir.resolve("root");
        Path inputDir = encodeRoot.resolve("project");
        Path sourceFile = inputDir.resolve("sub/file.txt");
        Files.createDirectories(sourceFile.getParent());
        byte[] sourceData = randomBytes(512, 71L);
        Files.write(sourceFile, sourceData);

        Path qrDir = tempDir.resolve("qr");
        Path restoredDir = tempDir.resolve("restored");

        int encodeExit = new CommandLine(new Sender()).execute(
                "encode",
                "--in", inputDir.toString(),
                "--out", qrDir.toString(),
                "--encode-root", encodeRoot.toString(),
                "--target-extensions", "txt",
                "--chunk-data-size", "60"
        );
        int decodeExit = new CommandLine(new Receiver()).execute(
                "decode",
                "--in", qrDir.toString(),
                "--out", restoredDir.toString(),
                "--decode-workers", "1"
        );

        assertEquals(0, encodeExit);
        assertEquals(0, decodeExit);
        // Restored under project/sub/file.txt (relative to encode-root), not just sub/file.txt.
        assertArrayEquals(sourceData, Files.readAllBytes(restoredDir.resolve("project/sub/file.txt")));
        assertEquals(0, countPngFiles(qrDir));
        assertTrue(Files.readString(restoredDir.resolve("_restore_result.txt"), StandardCharsets.UTF_8)
                .contains("O project/sub/file.txt - OK"));
    }

    @Test
    void decodeRecoversWhenSomeQrFramesAreLost() throws Exception {
        // The point of the fountain transfer: dropped frames on the one-way channel still
        // reconstruct, as long as enough distinct symbols survive (no specific frame is required).
        Path sourceDir = tempDir.resolve("src");
        Path sourceFile = sourceDir.resolve("nested/sample.txt");
        Files.createDirectories(sourceFile.getParent());
        byte[] sourceData = randomBytes(4096, 83L);
        Files.write(sourceFile, sourceData);

        Path qrDir = tempDir.resolve("qr");
        Path restoredDir = tempDir.resolve("restored");

        int encodeExit = new CommandLine(new Sender()).execute(
                "encode",
                "--in", sourceDir.toString(),
                "--out", qrDir.toString(),
                "--target-extensions", "txt",
                "--chunk-data-size", "200",
                "--qr-image-size", "600",
                "--label-height", "60"
        );
        assertEquals(0, encodeExit);

        // Drop ~1 in 8 of the emitted QR PNGs to simulate camera-channel losses.
        List<Path> pngs;
        try (Stream<Path> stream = Files.walk(qrDir)) {
            pngs = stream.filter(p -> p.toString().endsWith(".png")).sorted().toList();
        }
        assertTrue(pngs.size() > 10, "expected several symbols incl. repair, got " + pngs.size());
        int dropped = 0;
        for (int i = 0; i < pngs.size(); i++) {
            if (i % 8 == 0) {
                Files.delete(pngs.get(i));
                dropped++;
            }
        }
        assertTrue(dropped > 0);

        int decodeExit = new CommandLine(new Receiver()).execute(
                "decode",
                "--in", qrDir.toString(),
                "--out", restoredDir.toString(),
                "--decode-workers", "1"
        );

        assertEquals(0, decodeExit);
        assertArrayEquals(sourceData, Files.readAllBytes(restoredDir.resolve("nested/sample.txt")),
                "lost " + dropped + "/" + pngs.size() + " frames but should still restore");
        assertEquals(0, countPngFiles(qrDir));
        assertTrue(Files.readString(restoredDir.resolve("_restore_result.txt"), StandardCharsets.UTF_8)
                .contains("O nested/sample.txt - OK"));
    }

    private static long countPngFiles(Path root) throws Exception {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png")).count();
        }
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
