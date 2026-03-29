package airbridge.packager;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Command(name = "identify", mixinStandardHelpOptions = true,
        description = "List unique extensions inside jar/zip")
public final class IdentifyCommand implements Runnable {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public void run() {
        try {
            List<String> tokens = PackagerInspector.collectUniqueExtensions(input);
            Set<String> filtered = ExtensionTokens.filterIncluded(tokens);
            Path baseDir = resolveBaseDir(input);
            Path targetExtPath = baseDir.resolve("target-ext.txt");
            Files.write(targetExtPath, filtered, StandardCharsets.UTF_8);
            filtered.forEach(System.out::println);
            System.out.printf("Wrote %d entries to %s%n", filtered.size(), targetExtPath.toAbsolutePath());
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "identify failed", e);
        }
    }

    private static Path resolveBaseDir(Path input) {
        Path abs = input.toAbsolutePath().normalize();
        Path parent = abs.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
