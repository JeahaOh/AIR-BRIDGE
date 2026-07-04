package airbridge.packager;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "identify", mixinStandardHelpOptions = true,
        description = "List unique extensions inside jar/zip")
public final class IdentifyCommand implements Callable<Integer> {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public Integer call() {
        try {
            Path abs = PackagerCli.requireExistingPackage(input);
            List<String> tokens = PackagerInspector.collectUniqueExtensions(abs);
            Set<String> filtered = ExtensionTokens.filterIncluded(tokens);
            Path targetExtPath = resolveBaseDir(abs).resolve("target-ext.txt");
            Files.write(targetExtPath, filtered, StandardCharsets.UTF_8);
            filtered.forEach(System.out::println);
            System.out.printf("Wrote %d entries to %s%n", filtered.size(), targetExtPath.toAbsolutePath());
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            return PackagerCli.fail("identify", e);
        }
    }

    private static Path resolveBaseDir(Path input) {
        Path parent = input.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
