package airbridge.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenderCliTest {
    @TempDir
    Path tempDir;

    @Test
    void helpPrintsBanner() {
        Result result = execute("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("air-bridge sender - "));
        assertTrue(result.stdout().contains("Usage: sender"));
    }

    @Test
    void versionPrintsBanner() {
        Result result = execute("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("air-bridge sender - "));
        assertTrue(result.stdout().contains("____"));
    }

    @Test
    void encodeRejectsTooSmallNumericOptions() throws Exception {
        Path sourceDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(sourceDir.resolve("sample.txt"), "hello", StandardCharsets.UTF_8);
        Path outDir = tempDir.resolve("out");

        assertInvalidNumericOption(sourceDir, outDir, "--chunk-data-size=0", "--chunk-data-size must be >= 1");
        assertInvalidNumericOption(sourceDir, outDir, "--files-per-folder=0", "--files-per-folder must be >= 1");
        assertInvalidNumericOption(sourceDir, outDir, "--qr-image-size=0", "--qr-image-size must be >= 1");
        assertInvalidNumericOption(sourceDir, outDir, "--label-height=-1", "--label-height must be >= 0");
    }

    private void assertInvalidNumericOption(Path sourceDir, Path outDir, String option, String expectedMessage) {
        Result result = execute(
                "encode",
                "--in=" + sourceDir,
                "--out=" + outDir,
                option
        );

        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains(expectedMessage));
    }

    private static Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = Sender.newCommandLine().execute(args);
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
