package airbridge.receiver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiverCliTest {
    @Test
    void helpPrintsBanner() {
        Result result = execute("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("air-bridge receiver - "));
        assertTrue(result.stdout().contains("Usage: receiver"));
        assertTrue(result.stdout().contains("gui"));
        assertTrue(result.stdout().contains("identify"));
    }

    @Test
    void versionPrintsBanner() {
        Result result = execute("--version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("air-bridge receiver - "));
        assertTrue(result.stdout().contains("____"));
    }

    @Test
    void noArgsLaunchesGuiByDefault() {
        assertTrue(Receiver.shouldLaunchGuiByDefault(new String[0]));
        assertTrue(Receiver.shouldLaunchGuiByDefault(new String[]{"--lang", "ko"}));
        assertTrue(Receiver.shouldLaunchGuiByDefault(new String[]{"--lang=en"}));
    }

    @Test
    void commandArgsStayCli() {
        assertFalse(Receiver.shouldLaunchGuiByDefault(new String[]{"--help"}));
        assertFalse(Receiver.shouldLaunchGuiByDefault(new String[]{"decode", "--help"}));
        assertFalse(Receiver.shouldLaunchGuiByDefault(new String[]{"capture"}));
        assertFalse(Receiver.shouldLaunchGuiByDefault(new String[]{"--lang"}));
    }

    private static Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = Receiver.newCommandLine().execute(args);
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
