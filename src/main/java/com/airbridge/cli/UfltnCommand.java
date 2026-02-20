package com.airbridge.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import picocli.CommandLine;

@CommandLine.Command(name = "ufltn", mixinStandardHelpOptions = true, description = "Unflatten target.txt file names")
public final class UfltnCommand implements Runnable {
    @CommandLine.Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public void run() {
        try {
            Path baseDir = resolveBaseDir(input);
            Path targetExtPath = baseDir.resolve("target-ext.txt");
            if (Files.notExists(targetExtPath)) {
                System.out.println("WARN target-ext.txt not found; aborting");
                return;
            }
            var targetExts = FltnCommand.readTargetExts(targetExtPath);

            com.airbridge.archive.ArchiveRewriter.rewriteInPlaceUnflatten(input, targetExts);

            Path targetPath = baseDir.resolve("target.txt");
            if (Files.notExists(targetPath)) {
                Files.createFile(targetPath);
                System.out.println("WARN target.txt not found; created empty file");
                return;
            }

            List<String> lines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
            List<String> restored = lines.stream()
                    .map(UfltnCommand::stripTxtSuffix)
                    .collect(Collectors.toList());
            Files.write(targetPath, restored, StandardCharsets.UTF_8);

            System.out.printf("Rewrote %d entries in %s%n", restored.size(), targetPath.toAbsolutePath());
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "ufltn failed", e);
        }
    }

    private static String stripTxtSuffix(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.length() < 4) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return trimmed.substring(0, trimmed.length() - 4);
        }
        return trimmed;
    }

    private static Path resolveBaseDir(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
