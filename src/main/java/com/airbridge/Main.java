package com.airbridge;

import com.airbridge.cli.Root;
import picocli.CommandLine;

public final class Main {
    public static final String VERSION = "1.0.0";

    private Main() {
    }

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Root());
        cli.setExecutionExceptionHandler((exception, commandLine, parseResult) -> {
            exception.printStackTrace(System.err);
            return commandLine.getCommandSpec().exitCodeOnExecutionException();
        });

        int exit;
        try {
            exit = cli.execute(args);
        } catch (NoClassDefFoundError e) {
            // Fallback for broken/mixed runtime classpaths so the root issue is still visible.
            e.printStackTrace(System.err);
            exit = 1;
        }
        System.exit(exit);
    }
}
