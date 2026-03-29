package airbridge.packager;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

@Command(name = "pack", mixinStandardHelpOptions = true,
        description = "Append .txt suffix to target package entries")
public final class PackCommand implements Runnable {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public void run() {
        try {
            Path baseDir = resolveBaseDir(input);
            Path targetExtPath = baseDir.resolve("target-ext.txt");
            List<String> excludedEntryPatterns = PackEntryFilters.loadExcludePatterns();
            List<String> targetExtLines = Files.exists(targetExtPath)
                    ? Files.readAllLines(targetExtPath, StandardCharsets.UTF_8)
                    : inferTargetExtLines(input, excludedEntryPatterns);
            Set<String> targetExts = readTargetExts(targetExtLines);

            List<String> packed = PackagerInspector.collectPackedNames(input, targetExts, excludedEntryPatterns);
            Path zipOutput = PackagerRewriter.rewriteToZip(
                    input,
                    targetExts,
                    normalizeTargetExtLines(targetExtLines),
                    packed,
                    excludedEntryPatterns
            );

            System.out.printf("Embedded %d target extension(s) and %d target entry name(s) into %s%n",
                    targetExts.size(), packed.size(), zipOutput.toAbsolutePath());
            System.out.printf("Saved packed package to %s%n", zipOutput.toAbsolutePath());
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "pack failed", e);
        }
    }

    private static List<String> inferTargetExtLines(Path input, List<String> excludedEntryPatterns) throws IOException {
        List<String> tokens = PackagerInspector.collectUniqueExtensions(input, excludedEntryPatterns);
        Set<String> inferred = ExtensionTokens.filterIncluded(tokens);
        System.out.printf(
                "WARN target-ext.txt not found; inferred %d target extension(s) from package using /ext/ext.properties%n",
                inferred.size()
        );
        return new ArrayList<>(inferred);
    }

    static Set<String> readTargetExts(List<String> lines) {
        Set<String> exts = new HashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalized = trimmed.startsWith(".") ? trimmed.substring(1) : trimmed;
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (ExtensionTokens.isBlockedPackExtension(lower)) {
                continue;
            }
            exts.add(lower);
        }
        return exts;
    }

    private static List<String> normalizeTargetExtLines(List<String> lines) {
        return new ArrayList<>(new TreeSet<>(readTargetExts(lines)));
    }

    private static Path resolveBaseDir(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
