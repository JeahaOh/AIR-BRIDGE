package airbridge.packager;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Command(name = "unpack", mixinStandardHelpOptions = true,
        description = "Remove .txt suffix from packed package entries")
public final class UnpackCommand implements Callable<Integer> {
    @Option(names = "--in", required = true, description = "Input jar/zip path")
    Path input;

    @Override
    public Integer call() {
        try {
            Path abs = PackagerCli.requireExistingPackage(input);
            List<String> targetExtLines;
            List<String> packedLines;
            try (ZipFile zipFile = new ZipFile(abs.toFile())) {
                targetExtLines = readEmbeddedTextFile(zipFile, PackagerRewriter.TARGET_EXT_ENTRY);
                packedLines = readEmbeddedTextFile(zipFile, PackagerRewriter.TARGET_ENTRY);
            }
            if (targetExtLines == null) {
                System.out.println("WARN embedded target-ext.txt not found; aborting");
                return 1;
            }
            Set<String> targetExts = PackCommand.readTargetExts(targetExtLines);

            // The embedded rename list makes un-renaming exact; without it (older packs)
            // rewriteInPlaceUnpack falls back to the extension heuristic.
            Set<String> packedNames = packedLines == null ? null : parsePackedNames(packedLines);
            if (packedNames == null) {
                System.out.println("WARN embedded target.txt not found; using extension heuristic for un-rename");
            }

            PackagerRewriter.rewriteInPlaceUnpack(abs, targetExts, packedNames);
            Path output = PackagerRewriter.rewriteZipToJarIfManifest(abs);
            System.out.printf("Unpacked %s using embedded %s%n",
                    output.toAbsolutePath().normalize(), PackagerRewriter.TARGET_EXT_ENTRY);
            return 0;
        } catch (IllegalArgumentException | IOException e) {
            return PackagerCli.fail("unpack", e);
        }
    }

    /**
     * Rename-list lines are exact entry names — no trimming, or names with leading or
     * trailing spaces would never match. Only a trailing '\r' is stripped, for lists
     * written by older packs that joined lines with the platform separator.
     */
    private static Set<String> parsePackedNames(List<String> lines) {
        Set<String> names = new HashSet<>();
        for (String line : lines) {
            String name = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static List<String> readEmbeddedTextFile(ZipFile zipFile, String entryName) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        // Read eagerly: BufferedReader.lines() would wrap a mid-inflate ZipException from a
        // corrupt entry in an UncheckedIOException, which escapes the command's IOException
        // handler and prints a stack trace.
        byte[] bytes;
        try (InputStream in = zipFile.getInputStream(entry)) {
            bytes = in.readAllBytes();
        }
        List<String> lines = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }
}
