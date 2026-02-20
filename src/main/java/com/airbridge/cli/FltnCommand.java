package com.airbridge.cli;

import com.airbridge.archive.ArchiveInspector;
import com.airbridge.archive.ArchiveRewriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import picocli.CommandLine;

@CommandLine.Command(name = "fltn", mixinStandardHelpOptions = true, description = "Flatten archive file names")
public final class FltnCommand implements Runnable {
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
            Set<String> targetExts = readTargetExts(targetExtPath);

            List<String> flattened = ArchiveInspector.collectFlattenedNames(input, targetExts);
            Path zipOutput = ArchiveRewriter.rewriteToZip(input, targetExts);
            Path targetPath = baseDir.resolve("target.txt");
            Files.write(targetPath, flattened, StandardCharsets.UTF_8);

            System.out.printf("Wrote %d entries to %s%n", flattened.size(), targetPath.toAbsolutePath());
            System.out.printf("Saved flattened archive to %s%n", zipOutput.toAbsolutePath());
            System.out.println("Next: jar cfm airbridge-*.jar META-INF/MANIFEST.MF -C . .");
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "fltn failed", e);
        }
    }

    static Set<String> readTargetExts(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createFile(path);
            System.out.println("WARN target-ext.txt not found; created empty file");
            return Set.of();
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Set<String> exts = new HashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
            exts.add(normalized.toLowerCase(Locale.ROOT));
        }
        return exts;
    }

    private static Path resolveBaseDir(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
