package airbridge.common;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BannerExecutionStrategyTest {

    @Command(name = "root", mixinStandardHelpOptions = true,
            subcommands = {Sub.class, Cap.class})
    static class Root implements Runnable {
        @Override
        public void run() {
        }
    }

    @Command(name = "sub")
    static class Sub implements Runnable {
        @Override
        public void run() {
        }
    }

    @Command(name = "cap")
    static class Cap implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    void printsBannerForResolvedSubcommand() {
        assertTrue(run("sub").contains("____"));
    }

    @Test
    void skipsBannerForOptedOutLeafCommand() {
        assertFalse(run("cap").contains("____"));
    }

    @Test
    void skipsBannerWhenHelpRequested() {
        assertFalse(run("--help").contains("____"));
        assertFalse(run("sub", "--help").contains("____"));
    }

    @Test
    void skipsBannerWhenNoSubcommandSelected() {
        assertFalse(run().contains("____"));
    }

    private static String run(String... args) {
        CommandLine commandLine = new CommandLine(new Root());
        // No BannerSupport.apply() here, so the only possible banner source is the strategy —
        // this isolates the strategy's print decision from the usage-header banner.
        commandLine.setExecutionStrategy(new BannerExecutionStrategy("air-bridge test", "cap"));
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            commandLine.execute(args);
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
