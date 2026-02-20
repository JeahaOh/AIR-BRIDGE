package com.airbridge.cli;

import com.airbridge.archive.ArchiveInspector;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Properties;
import picocli.CommandLine;

@CommandLine.Command(name = "search", mixinStandardHelpOptions = true, description = "List unique extensions inside jar/zip (includes extensionless names)")
public final class SearchCommand implements Runnable {
    @CommandLine.Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public void run() {
        try {
            List<String> tokens = ArchiveInspector.collectUniqueExtensions(input);
            Set<String> filtered = filterTokens(tokens);
            Path baseDir = resolveBaseDir(input);
            Path targetExtPath = baseDir.resolve("target-ext.txt");
            Files.write(targetExtPath, filtered, StandardCharsets.UTF_8);
            filtered.forEach(System.out::println);
            System.out.printf("Wrote %d entries to %s%n", filtered.size(), targetExtPath.toAbsolutePath());
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "search failed", e);
        }
    }

    private static Set<String> filterTokens(List<String> tokens) {
        Set<String> excluded = loadExcludedTokens();
        Set<String> results = new java.util.TreeSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!excluded.contains(normalized)) {
                results.add(normalized);
            }
        }
        return results;
    }

    private static Set<String> loadExcludedTokens() {
        Set<String> fallback = Set.of(
                "class", "xml", "js", "jsp", "html", "css", "exe", "zip", "jar"
        );
        Properties props = new Properties();
        try (InputStream in = SearchCommand.class.getResourceAsStream("/ext/ext.properties")) {
            if (in == null) {
                return fallback;
            }
            props.load(in);
            String raw = props.getProperty("exts.exclude", "").trim();
            if (raw.isEmpty()) {
                return fallback;
            }
            Set<String> result = new java.util.TreeSet<>();
            String[] parts = raw.split(",");
            for (String part : parts) {
                String token = part.trim().toLowerCase(Locale.ROOT);
                if (!token.isEmpty()) {
                    result.add(token);
                }
            }
            return result.isEmpty() ? fallback : result;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Path resolveBaseDir(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
