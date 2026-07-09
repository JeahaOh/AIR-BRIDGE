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
import java.util.ArrayList;
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

    /** Output of a pack rewrite: where the zip landed and the exact rename list it embeds. */
    public static final class PackResult {
        private final Path output;
        private final List<String> packedNames;

        PackResult(Path output, List<String> packedNames) {
            this.output = output;
            this.packedNames = List.copyOf(packedNames);
        }

        public Path output() {
            return output;
        }

        public List<String> packedNames() {
            return packedNames;
        }
    }

    /**
     * Packs {@code packagePath} into a sibling zip, appending ".txt" to target entries.
     * The embedded {@code target.txt} rename list is collected from the rewrite pass
     * itself (nested entries as {@code outer.jar!/inner}), so it always matches the
     * entries actually renamed in the emitted zip.
     */
    public static PackResult packToZip(
            Path packagePath,
            Set<String> targetExts,
            List<String> targetExtLines,
            List<String> excludedEntryPatterns
    ) throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        String baseName = stripExtension(abs.getFileName().toString());
        Path output = abs.resolveSibling(baseName + ".zip");
        if (output.equals(abs)) {
            // A .zip input would otherwise be replaced by its packed form, destroying the
            // original archive; a .jar input keeps its original next to the output.
            output = abs.resolveSibling(baseName + "-packed.zip");
        }
        // Central-directory name set for the collision rule; also rejects corrupt input
        // before any temp file is created.
        Set<String> originalNames = topLevelEntryNames(abs);
        List<String> renames = new ArrayList<>();
        Set<String> seenSourceNames = new HashSet<>();
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-pack-", ".zip");
        boolean moved = false;
        try {
            try (InputStream in = Files.newInputStream(abs);
                 OutputStream out = Files.newOutputStream(temp)) {
                rewritePackStream(in, out, targetExts, targetExtLines, excludedEntryPatterns,
                        originalNames, renames, "", true, seenSourceNames);
            }
            requireSequentiallyReadable(abs, originalNames, seenSourceNames);
            if (Files.exists(output)) {
                System.out.printf("WARN overwriting existing file %s%n", output);
            }
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
        return new PackResult(output, renames);
    }

    public static void rewriteInPlaceUnpack(Path packagePath, Set<String> targetExts) throws IOException {
        rewriteInPlaceUnpack(packagePath, targetExts, null);
    }

    /**
     * Reverses pack's renames. When {@code packedNames} (the embedded {@code target.txt}
     * rename list, nested entries as {@code outer.jar!/inner}) is present, only those exact
     * entries are un-renamed — a file that was genuinely named {@code *.txt} in the original
     * package keeps its name. A {@code null} list falls back to the extension heuristic for
     * packages produced before the list existed.
     */
    public static void rewriteInPlaceUnpack(Path packagePath, Set<String> targetExts, Set<String> packedNames)
            throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        Set<String> originalNames = topLevelEntryNames(abs);
        Set<String> seenSourceNames = new HashSet<>();
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-unpack-", ".zip");
        boolean moved = false;
        try {
            try (InputStream in = Files.newInputStream(abs);
                 OutputStream out = Files.newOutputStream(temp)) {
                rewriteUnpackStream(in, out, targetExts, packedNames, "", seenSourceNames, originalNames);
            }
            requireSequentiallyReadable(abs, originalNames, seenSourceNames);

            Path backup = Files.createTempFile(abs.getParent(), "airbridge-unpack-backup-", ".zip");
            boolean backupHoldsOriginal = false;

            boolean writeSuccess = false;
            try {
                Files.move(abs, backup, StandardCopyOption.REPLACE_EXISTING);
                backupHoldsOriginal = true;
                try {
                    Files.move(temp, abs, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, abs, StandardCopyOption.REPLACE_EXISTING);
                }
                writeSuccess = true;
            } finally {
                if (writeSuccess) {
                    Files.deleteIfExists(backup);
                    backupHoldsOriginal = false;
                } else if (backupHoldsOriginal) {
                    try {
                        Files.move(backup, abs, StandardCopyOption.REPLACE_EXISTING);
                        backupHoldsOriginal = false;
                    } catch (IOException rollbackFailed) {
                        System.err.printf("FATAL: Rollback failed during unpack recovery. Original backup saved at: %s. Error: %s%n",
                                backup.toAbsolutePath(), rollbackFailed.getMessage());
                        throw rollbackFailed;
                    }
                }
                if (!backupHoldsOriginal) {
                    Files.deleteIfExists(backup);
                }
            }
            moved = writeSuccess;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static Path rewriteZipToJarIfManifest(Path packagePath) throws IOException {
        Path abs = packagePath.toAbsolutePath().normalize();
        String lowerName = abs.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".zip")) {
            return abs;
        }

        String baseName = stripExtension(abs.getFileName().toString());
        Path jarPath = abs.resolveSibling(baseName + ".jar");
        Path temp = null;
        boolean moved = false;
        try {
            try (ZipFile zipFile = new ZipFile(abs.toFile())) {
                ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
                if (manifestEntry == null) {
                    return abs;
                }

                Manifest manifest;
                try (InputStream in = zipFile.getInputStream(manifestEntry)) {
                    manifest = new Manifest(in);
                }

                temp = Files.createTempFile(abs.getParent(), "airbridge-jar-", ".jar");
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
                        if (entry.getExtra() != null) {
                            outEntry.setExtra(entry.getExtra());
                        }
                        if (entry.getComment() != null) {
                            outEntry.setComment(entry.getComment());
                        }
                        outEntry.setMethod(entry.getMethod());
                        if (entry.isDirectory()) {
                            if (outEntry.getMethod() == ZipEntry.STORED) {
                                // STORED entries need explicit size/crc before putNextEntry;
                                // directories are always empty. Normally-built jars store their
                                // directory entries, so this path is the common one.
                                outEntry.setSize(0);
                                outEntry.setCompressedSize(0);
                                outEntry.setCrc(0);
                            }
                            jos.putNextEntry(outEntry);
                            jos.closeEntry();
                            continue;
                        }
                        if (outEntry.getMethod() == ZipEntry.STORED) {
                            // Central directory gives valid size/crc, so a STORED entry can be
                            // streamed without buffering it to compute them.
                            outEntry.setSize(entry.getSize());
                            outEntry.setCompressedSize(entry.getCompressedSize());
                            outEntry.setCrc(entry.getCrc());
                        }
                        jos.putNextEntry(outEntry);
                        try (InputStream in = zipFile.getInputStream(entry)) {
                            in.transferTo(jos);
                        }
                        jos.closeEntry();
                    }
                }
            }
            // The source ZipFile is closed here: replacing/deleting an open zip fails on
            // Windows, so the moves must happen outside the try-with-resources.
            if (Files.exists(jarPath)) {
                System.out.printf("WARN overwriting existing file %s%n", jarPath);
            }
            Files.move(temp, jarPath, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (temp != null && !moved) {
                Files.deleteIfExists(temp);
            }
        }
        Files.deleteIfExists(abs);
        return jarPath;
    }

    private static void rewritePackStream(
            InputStream input,
            OutputStream output,
            Set<String> targetExts,
            List<String> targetExtLines,
            List<String> excludedEntryPatterns,
            Set<String> originalNames,
            List<String> renames,
            String nestedPrefix,
            boolean topLevel,
            Set<String> seenSourceNames
    ) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input);
             ZipOutputStream zos = new ZipOutputStream(output)) {
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (seenSourceNames != null) {
                    seenSourceNames.add(entry.getName());
                }
                if (entry.isDirectory()) {
                    String dirName = entry.getName();
                    if (PackEntryFilters.matchesAnyDirectory(dirName, excludedEntryPatterns)) {
                        continue;
                    }
                    if (seen.add(dirName)) {
                        ZipEntry outEntry = copyEntry(entry, dirName);
                        zos.putNextEntry(outEntry);
                        zos.closeEntry();
                    }
                    continue;
                }

                String originalName = entry.getName();
                if (isMetadataEntry(originalName)) {
                    System.out.printf("WARN entry %s%s conflicts with packager metadata and was removed%n",
                            nestedPrefix, originalName);
                    continue;
                }
                if (PackEntryFilters.matchesAny(originalName, excludedEntryPatterns)) {
                    continue;
                }

                String newName = renameForPack(originalName, targetExts);
                if (!newName.equals(originalName)) {
                    if (isMetadataEntry(newName)) {
                        System.out.printf("WARN not renaming %s%s: %s is reserved for packager metadata%n",
                                nestedPrefix, originalName, newName);
                        newName = originalName;
                    } else if (originalNames.contains(newName)) {
                        System.out.printf("WARN not renaming %s%s: %s%s already exists in the archive%n",
                                nestedPrefix, originalName, nestedPrefix, newName);
                        newName = originalName;
                    } else if (EntryNames.hasLineBreak(nestedPrefix + newName)) {
                        // The recorded name (prefix + name) rides the line-based rename list; a
                        // line break anywhere in it — including an ancestor archive's name —
                        // would split the list entry, so leave this entry un-renamed.
                        System.out.printf("WARN not renaming %s%s: recorded name contains a line break%n",
                                nestedPrefix, originalName);
                        newName = originalName;
                    }
                }
                if (!seen.add(newName)) {
                    throw new IOException("Duplicate entry detected in archive: " + nestedPrefix + newName);
                }
                if (!newName.equals(originalName)) {
                    renames.add(nestedPrefix + newName);
                }

                if (PackagerInspector.isPackageName(originalName)) {
                    byte[] payload = readAllBytes(zis);
                    if (EntryNames.startsWithZipMagic(payload)) {
                        payload = rewriteNestedPack(payload, targetExts, excludedEntryPatterns,
                                renames, nestedPrefix + originalName + "!/");
                    } else if (!EntryNames.isEmptyZip(payload)) {
                        System.out.printf("WARN entry %s%s looks like an archive but is not; copied as-is%n",
                                nestedPrefix, originalName);
                    }
                    writeEntry(zos, copyEntry(entry, newName), payload);
                } else {
                    streamEntry(zos, copyEntry(entry, newName), zis);
                }
            }

            if (topLevel) {
                renames.sort(String::compareTo);
                writeTextEntry(zos, seen, TARGET_EXT_ENTRY, targetExtLines);
                writeTextEntry(zos, seen, TARGET_ENTRY, renames);
            }
        }
    }

    private static void rewriteUnpackStream(
            InputStream input,
            OutputStream output,
            Set<String> targetExts,
            Set<String> packedNames,
            String nestedPrefix,
            Set<String> seenSourceNames,
            Set<String> levelNames
    ) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input);
             ZipOutputStream zos = new ZipOutputStream(output)) {
            Set<String> seen = new HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (seenSourceNames != null) {
                    seenSourceNames.add(entry.getName());
                }
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
                    continue;
                }

                UnpackRename rename = renameForUnpack(originalName, targetExts, packedNames, nestedPrefix, levelNames);
                String newName = rename.newName;
                boolean archiveByName = PackagerInspector.isPackageName(originalName)
                        || PackagerInspector.isPackageName(newName);
                if (!archiveByName && rename.shapeCandidateName == null) {
                    if (!seen.add(newName)) {
                        // Same collision safety net as the buffered branch: never drop bytes on
                        // an un-rename collision; keep the entry under its original name if free.
                        if (!newName.equals(originalName) && seen.add(originalName)) {
                            System.out.printf("WARN %s%s not un-renamed to %s%s: name already exists; kept as-is%n",
                                    nestedPrefix, originalName, nestedPrefix, newName);
                            streamEntry(zos, copyEntry(entry, originalName), zis);
                            continue;
                        }
                        throw new IOException("Duplicate entry detected in archive: " + nestedPrefix + newName);
                    }
                    streamEntry(zos, copyEntry(entry, newName), zis);
                    continue;
                }

                byte[] payload = readAllBytes(zis);
                boolean zipPayload = EntryNames.startsWithZipMagic(payload);
                if (rename.shapeCandidateName != null && zipPayload
                        && hasRecordedNestedRenames(packedNames, nestedPrefix + rename.shapeCandidateName)) {
                    // Shape-compat un-rename of *.jar.txt/*.zip.txt exists only for OLD rename
                    // lists that recorded a nested archive's inner entries but not the archive's
                    // own rename. Two independent gates keep it off current-format packages and
                    // genuine files: renameForUnpack only offers a candidate that does NOT already
                    // exist at this level (else it would collide with a real sibling archive), and
                    // here the list must actually carry inner records under "<candidate>!/". A
                    // current-format list matches the archive rename exactly above; a genuine
                    // zip-shaped *.jar.txt has no inner records — neither reaches this line.
                    newName = rename.shapeCandidateName;
                }
                if (PackagerInspector.isPackageName(originalName) || PackagerInspector.isPackageName(newName)) {
                    if (zipPayload) {
                        // The rename list records nested entries against the un-renamed outer
                        // name (outer.jar!/inner), so the prefix passed down uses newName.
                        payload = rewriteNestedUnpack(payload, targetExts, packedNames,
                                nestedPrefix + newName + "!/");
                    } else if (!EntryNames.isEmptyZip(payload)) {
                        System.out.printf("WARN entry %s%s looks like an archive but is not; copied as-is%n",
                                nestedPrefix, newName);
                    }
                }
                if (!seen.add(newName)) {
                    // Never drop bytes on a rename collision: keep the entry under its original
                    // name if that is still free.
                    if (!newName.equals(originalName) && seen.add(originalName)) {
                        System.out.printf("WARN %s%s not un-renamed to %s%s: name already exists; kept as-is%n",
                                nestedPrefix, originalName, nestedPrefix, newName);
                        writeEntry(zos, copyEntry(entry, originalName), payload);
                        continue;
                    }
                    throw new IOException("Duplicate entry detected in archive: " + nestedPrefix + newName);
                }
                writeEntry(zos, copyEntry(entry, newName), payload);
            }
        }
    }

    /** True if the list has an inner-entry record under {@code archiveName + "!/"}. */
    private static boolean hasRecordedNestedRenames(Set<String> packedNames, String archiveName) {
        String prefix = archiveName + "!/";
        for (String recorded : packedNames) {
            if (recorded.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A zip whose central directory lists entries that the sequential ZipInputStream pass
     * never saw (a self-extractor preamble, or another zip concatenated in front so the
     * trailing EOCD wins) must fail loudly — otherwise pack emits a package missing those
     * entries and in-place unpack wipes them out of the input.
     */
    private static void requireSequentiallyReadable(Path abs, Set<String> centralDirectoryNames,
                                                    Set<String> seenSourceNames) throws IOException {
        if (!seenSourceNames.containsAll(centralDirectoryNames)) {
            throw new IOException("archive entries are not sequentially readable "
                    + "(leading garbage, preamble, or concatenated zip?): " + abs);
        }
    }

    private static byte[] rewriteNestedPack(
            byte[] payload,
            Set<String> targetExts,
            List<String> excludedEntryPatterns,
            List<String> renames,
            String nestedPrefix
    ) throws IOException {
        Set<String> originalNames = entryNames(payload);
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rewritePackStream(in, out, targetExts, List.of(), excludedEntryPatterns,
                    originalNames, renames, nestedPrefix, false, null);
            return out.toByteArray();
        }
    }

    private static byte[] rewriteNestedUnpack(
            byte[] payload,
            Set<String> targetExts,
            Set<String> packedNames,
            String nestedPrefix
    ) throws IOException {
        Set<String> levelNames = entryNames(payload);
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rewriteUnpackStream(in, out, targetExts, packedNames, nestedPrefix, null, levelNames);
            return out.toByteArray();
        }
    }

    private static String renameForPack(String originalName, Set<String> targetExts) {
        if (EntryNames.hasLineBreak(originalName)) {
            // The line-based target.txt rename list cannot record such a name; leave it
            // untouched so it round-trips as-is (unpack never touches non-.txt names).
            return originalName;
        }
        if (EntryNames.isExtensionless(originalName)) {
            return originalName + ".txt";
        }
        String ext = EntryNames.extensionOf(originalName);
        if (targetExts.contains(ext)) {
            return originalName + ".txt";
        }
        return originalName;
    }

    private static UnpackRename renameForUnpack(String originalName, Set<String> targetExts,
                                                Set<String> packedNames, String nestedPrefix,
                                                Set<String> levelNames) {
        if (!originalName.endsWith(".txt")) {
            return new UnpackRename(originalName, null);
        }
        String candidate = originalName.substring(0, originalName.length() - 4);
        if (EntryNames.lastSegment(candidate).isBlank()) {
            return new UnpackRename(originalName, null);
        }
        boolean candidateExtensionless = EntryNames.isExtensionless(candidate);
        String candidateExt = EntryNames.extensionOf(candidate);
        // With the embedded rename list, only entries pack actually renamed lose the
        // suffix; a file genuinely named *.txt in the original keeps its name. Lists
        // written before nested-archive renames were recorded miss *.jar.txt/*.zip.txt
        // entries, so those un-rename by shape — gated on the payload actually being a
        // zip and on the list carrying inner records (see rewriteUnpackStream) — as a
        // compatibility exception. Never offer a shape candidate that already exists at
        // this level: un-renaming onto a real sibling archive would collide and drop one.
        if (packedNames != null) {
            if (packedNames.contains(nestedPrefix + originalName)) {
                return new UnpackRename(candidate, null);
            }
            if (PackagerInspector.isPackageName(candidate) && targetExts.contains(candidateExt)
                    && !levelNames.contains(candidate)) {
                return new UnpackRename(originalName, candidate);
            }
            return new UnpackRename(originalName, null);
        }
        if (candidateExtensionless || targetExts.contains(candidateExt)) {
            return new UnpackRename(candidate, null);
        }
        return new UnpackRename(originalName, null);
    }

    private static final class UnpackRename {
        final String newName;
        final String shapeCandidateName;

        UnpackRename(String newName, String shapeCandidateName) {
            this.newName = newName;
            this.shapeCandidateName = shapeCandidateName;
        }
    }

    private static Set<String> topLevelEntryNames(Path packagePath) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(packagePath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static Set<String> entryNames(byte[] payload) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(payload))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static ZipEntry copyEntry(ZipEntry original, String newName) {
        ZipEntry outEntry = new ZipEntry(newName);
        outEntry.setTime(original.getTime());
        if (original.getExtra() != null) {
            // Local-header extra fields (extended timestamps, unix attrs) survive the
            // rewrite; central-directory entry comments are not visible to ZipInputStream
            // and are dropped.
            outEntry.setExtra(original.getExtra());
        }
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

    /**
     * Copies an entry's bytes straight from the source stream — no full-payload buffering.
     * For STORED entries the size/crc copied from the source header stay valid because the
     * payload is unchanged, and ZipInputStream verifies them while streaming.
     */
    private static void streamEntry(ZipOutputStream zos, ZipEntry entry, InputStream in) throws IOException {
        zos.putNextEntry(entry);
        in.transferTo(zos);
        zos.closeEntry();
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

    private static void writeTextEntry(ZipOutputStream zos, Set<String> seen, String entryName, List<String> lines) throws IOException {
        if (!seen.add(entryName)) {
            return;
        }
        // Fixed '\n' so packed bytes do not depend on the producing OS; readers accept
        // both LF and CRLF (older packs joined with the platform separator).
        byte[] payload = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry(entryName);
        writeEntry(zos, entry, payload);
    }

    private static boolean isMetadataEntry(String name) {
        return TARGET_EXT_ENTRY.equals(name) || TARGET_ENTRY.equals(name);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        input.transferTo(out);
        return out.toByteArray();
    }
}
