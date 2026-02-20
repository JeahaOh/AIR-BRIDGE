package com.airbridge.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(name = "help", mixinStandardHelpOptions = true, description = "Show help and ensure target files exist")
public final class HelpCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(new Root(), System.out);
    }
}
