package airbridge.packager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class PackagerRewriter {
    public static final String TARGET_EXT_ENTRY = "target-ext.txt";
    public static final String TARGET_ENTRY = "target.txt";

    private PackagerRewriter() {
    }

    public static Path rewriteToZip(
            Path packagePath,
            Set<String> targetExts,
            List<String> targetExtLines,
            List<String> targetLines,
            List<String> excludedEntryPatterns
    ) throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        String baseName = stripExtension(abs.getFileName().toString());
        Path output = abs.resolveSibling(baseName + ".zip");
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-pack-", ".zip");
        try (InputStream in = Files.newInputStream(abs);
             OutputStream out = Files.newOutputStream(temp)) {
            rewritePackStream(in, out, targetExts, targetExtLines, targetLines, excludedEntryPatterns);
        }
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        return output;
    }

    public static void rewriteInPlaceUnpack(Path packagePath, Set<String> targetExts) throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-unpack-", ".zip");
        try (InputStream in = Files.newInputStream(abs);
             OutputStream out = Files.newOutputStream(temp)) {
            rewriteStream(in, out, targetExts, RewriteMode.UNPACK);
        }
        Files.move(temp, abs, StandardCopyOption.REPLACE_EXISTING);
    }

    public static Path rewriteZipToJarIfManifest(Path packagePath) throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        String lowerName = abs.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".zip")) {
            return abs;
        }

        try (ZipFile zipFile = new ZipFile(abs.toFile())) {
            ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) {
                return abs;
            }

            Manifest manifest;
            try (InputStream in = zipFile.getInputStream(manifestEntry)) {
                manifest = new Manifest(in);
            }

            String baseName = stripExtension(abs.getFileName().toString());
            Path jarPath = abs.resolveSibling(baseName + ".jar");
            Path temp = Files.createTempFile(abs.getParent(), "airbridge-jar-", ".jar");
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
                Set<String> seen = new HashSet<>();
                seen.add("META-INF/");
                seen.add("META-INF/MANIFEST.MF");

                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if ("META-INF/".equals(name) || "META-INF/MANIFEST.MF".equals(name)) {
                        continue;
                    }
                    if (!seen.add(name)) {
                        continue;
                    }
                    JarEntry outEntry = new JarEntry(name);
                    outEntry.setTime(entry.getTime());
                    if (entry.getComment() != null) {
                        outEntry.setComment(entry.getComment());
                    }
                    outEntry.setMethod(entry.getMethod());
                    if (entry.isDirectory()) {
                        jos.putNextEntry(outEntry);
                        jos.closeEntry();
                        continue;
                    }
                    byte[] payload;
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        payload = readAllBytes(in);
                    }
                    writeJarEntry(jos, outEntry, payload);
                }
            }

            Files.move(temp, jarPath, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(abs);
            return jarPath;
        }
    }

    private static void rewriteStream(InputStream input, OutputStream output, Set<String> targetExts, RewriteMode mode)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input);
             ZipOutputStream zos = new ZipOutputStream(output)) {
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    String dirName = entry.getName();
                    if (seen.add(dirName)) {
                        ZipEntry outEntry = copyEntry(entry, dirName);
                        zos.putNextEntry(outEntry);
                        zos.closeEntry();
                    }
                    continue;
                }

                String originalName = entry.getName();
                if (mode == RewriteMode.UNPACK && isMetadataEntry(originalName)) {
                    drain(zis);
                    continue;
                }
                String newName = renameIfMatch(originalName, targetExts, mode);

                byte[] payload = readAllBytes(zis);
                if (PackagerInspector.isPackageName(originalName)) {
                    payload = rewriteNestedPackage(payload, targetExts, mode, List.of());
                }

                if (seen.add(newName)) {
                    ZipEntry outEntry = copyEntry(entry, newName);
                    writeEntry(zos, outEntry, payload);
                }
            }
        }
    }

    private static void rewritePackStream(
            InputStream input,
            OutputStream output,
            Set<String> targetExts,
            List<String> targetExtLines,
            List<String> targetLines,
            List<String> excludedEntryPatterns
    ) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input);
             ZipOutputStream zos = new ZipOutputStream(output)) {
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    String dirName = entry.getName();
                    if (seen.add(dirName)) {
                        ZipEntry outEntry = copyEntry(entry, dirName);
                        zos.putNextEntry(outEntry);
                        zos.closeEntry();
                    }
                    continue;
                }

                String originalName = entry.getName();
                if (isMetadataEntry(originalName)) {
                    drain(zis);
                    continue;
                }
                if (PackEntryFilters.matchesAny(originalName, excludedEntryPatterns)) {
                    drain(zis);
                    continue;
                }
                String newName = renameIfMatch(originalName, targetExts, RewriteMode.PACK);

                byte[] payload = readAllBytes(zis);
                if (PackagerInspector.isPackageName(originalName)) {
                    payload = rewriteNestedPackage(payload, targetExts, RewriteMode.PACK, excludedEntryPatterns);
                }

                if (seen.add(newName)) {
                    ZipEntry outEntry = copyEntry(entry, newName);
                    writeEntry(zos, outEntry, payload);
                }
            }

            writeTextEntry(zos, seen, TARGET_EXT_ENTRY, targetExtLines);
            writeTextEntry(zos, seen, TARGET_ENTRY, targetLines);
        }
    }

    private static byte[] rewriteNestedPackage(
            byte[] payload,
            Set<String> targetExts,
            RewriteMode mode,
            List<String> excludedEntryPatterns
    ) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (mode == RewriteMode.PACK) {
                rewritePackStream(in, out, targetExts, List.of(), List.of(), excludedEntryPatterns);
            } else {
                rewriteStream(in, out, targetExts, mode);
            }
            return out.toByteArray();
        }
    }

    private static ZipEntry copyEntry(ZipEntry original, String newName) {
        ZipEntry outEntry = new ZipEntry(newName);
        outEntry.setTime(original.getTime());
        if (original.getComment() != null) {
            outEntry.setComment(original.getComment());
        }
        outEntry.setMethod(original.getMethod());
        if (original.getMethod() == ZipEntry.STORED) {
            outEntry.setSize(original.getSize());
            outEntry.setCompressedSize(original.getCompressedSize());
            outEntry.setCrc(original.getCrc());
        }
        return outEntry;
    }

    private static void writeEntry(ZipOutputStream zos, ZipEntry entry, byte[] payload) throws IOException {
        if (entry.getMethod() == ZipEntry.STORED) {
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            entry.setCrc(crc.getValue());
        }
        zos.putNextEntry(entry);
        zos.write(payload);
        zos.closeEntry();
    }

    private static void writeJarEntry(JarOutputStream jos, JarEntry entry, byte[] payload) throws IOException {
        if (entry.getMethod() == ZipEntry.STORED) {
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            CRC32 crc = new CRC32();
            crc.update(payload);
            entry.setCrc(crc.getValue());
        }
        jos.putNextEntry(entry);
        jos.write(payload);
        jos.closeEntry();
    }

    private static void writeTextEntry(ZipOutputStream zos, Set<String> seen, String entryName, List<String> lines) throws IOException {
        if (!seen.add(entryName)) {
            return;
        }
        byte[] payload = String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(entryName);
        writeEntry(zos, entry, payload);
    }

    private static String renameIfMatch(String originalName, Set<String> targetExts, RewriteMode mode) {
        String fileName = Path.of(originalName).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        boolean extensionless = dot < 0 || dot == fileName.length() - 1;

        switch (mode) {
            case PACK:
                if (extensionless) {
                    return originalName + ".txt";
                }
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (targetExts.contains(ext)) {
                    return originalName + ".txt";
                }
                return originalName;
            case UNPACK:
                if (originalName.endsWith(".txt")) {
                    String candidate = originalName.substring(0, originalName.length() - 4);
                    String candidateFileName = Path.of(candidate).getFileName().toString();
                    int candidateDot = candidateFileName.lastIndexOf('.');
                    if (candidateDot < 0 || candidateDot == candidateFileName.length() - 1) {
                        return candidate;
                    }
                    String candidateExt = candidateFileName.substring(candidateDot + 1).toLowerCase(Locale.ROOT);
                    if (targetExts.contains(candidateExt)) {
                        return candidate;
                    }
                }
                return originalName;
            default:
                return originalName;
        }
    }

    private static boolean isMetadataEntry(String name) {
        return TARGET_EXT_ENTRY.equals(name) || TARGET_ENTRY.equals(name);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void drain(InputStream input) throws IOException {
        input.transferTo(OutputStream.nullOutputStream());
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        input.transferTo(out);
        return out.toByteArray();
    }

    private enum RewriteMode {
        PACK,
        UNPACK
    }
}
