package airbridge.packager;

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
import java.util.concurrent.Callable;

@Command(name = "pack", mixinStandardHelpOptions = true,
        description = "Append .txt suffix to target package entries")
public final class PackCommand implements Callable<Integer> {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public Integer call() {
        try {
            Path abs = PackagerCli.requireExistingPackage(input);
            Path targetExtPath = resolveBaseDir(abs).resolve("target-ext.txt");
            List<String> excludedEntryPatterns = PackEntryFilters.loadExcludePatterns();
            List<String> targetExtLines;
            if (Files.exists(targetExtPath)) {
                try {
                    targetExtLines = Files.readAllLines(targetExtPath, StandardCharsets.UTF_8);
                } catch (java.nio.charset.CharacterCodingException e) {
                    throw new IOException("target-ext.txt is not valid UTF-8: " + targetExtPath, e);
                }
            } else {
                targetExtLines = inferTargetExtLines(abs, excludedEntryPatterns);
            }
            Set<String> targetExts = readTargetExts(targetExtLines);

            PackagerRewriter.PackResult result = PackagerRewriter.packToZip(
                    abs,
                    targetExts,
                    normalizeTargetExtLines(targetExtLines),
                    excludedEntryPatterns
            );

            System.out.printf("Embedded %d target extension(s) and %d target entry name(s) into %s%n",
                    targetExts.size(), result.packedNames().size(), result.output().toAbsolutePath());
            System.out.printf("Saved packed package to %s%n", result.output().toAbsolutePath());
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            return PackagerCli.fail("pack", e);
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
        Path parent = input.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
