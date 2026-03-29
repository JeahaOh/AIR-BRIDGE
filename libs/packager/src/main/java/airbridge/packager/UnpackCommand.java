package airbridge.packager;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Command(name = "unpack", mixinStandardHelpOptions = true,
        description = "Remove .txt suffix from packed package entries")
public final class UnpackCommand implements Runnable {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public void run() {
        try {
            List<String> targetExtLines = readEmbeddedTextFile(input, PackagerRewriter.TARGET_EXT_ENTRY);
            if (targetExtLines == null) {
                System.out.println("WARN embedded target-ext.txt not found; aborting");
                return;
            }
            Set<String> targetExts = PackCommand.readTargetExts(targetExtLines);

            PackagerRewriter.rewriteInPlaceUnpack(input, targetExts);
            Path output = PackagerRewriter.rewriteZipToJarIfManifest(input);
            System.out.printf("Unpacked %s using embedded %s%n",
                    output.toAbsolutePath().normalize(), PackagerRewriter.TARGET_EXT_ENTRY);
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "unpack failed", e);
        }
    }

    private static List<String> readEmbeddedTextFile(Path packagePath, String entryName) throws IOException {
        try (ZipFile zipFile = new ZipFile(packagePath.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zipFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.toList());
            }
        }
    }
}
