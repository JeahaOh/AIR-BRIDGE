package airbridge.receiver;

import airbridge.sender.Sender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

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
                "--project-name", "TESTPROJ",
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
        assertTrue(Files.isDirectory(qrDir.resolve("nested-success")));
        assertTrue(Files.readString(restoredDir.resolve("_restore_result.txt"), StandardCharsets.UTF_8)
                .contains("O nested/sample.txt - OK"));
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }
}
