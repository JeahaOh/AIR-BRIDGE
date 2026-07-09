package airbridge.packager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagerAppTest {

    @TempDir
    Path tempDir;

    @Test
    void rootHelpReturnsZero() {
        assertEquals(0, new CommandLine(new PackagerApp()).execute("--help"));
    }

    @Test
    void identifyHelpReturnsZero() {
        assertEquals(0, new CommandLine(new PackagerApp()).execute("identify", "--help"));
    }

    @Test
    void packHelpReturnsZero() {
        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--help"));
    }

    @Test
    void unpackHelpReturnsZero() {
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--help"));
    }

    @Test
    void packFallsBackToExtPropertiesWhenTargetExtMissing() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of(
                "assets/blob.dat", text("dat"),
                "config/settings.xml", text("xml"),
                "bin/run", text("run"),
                "BOOT-INF/classes/App.class", text("class")
        ));

        assertFalse(Files.exists(tempDir.resolve("target-ext.txt")));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("sample.zip");
        assertTrue(Files.exists(packed));
        assertLinesMatch(
                List.of("assets/blob.dat.txt", "bin/run.txt"),
                readZipTextEntry(packed, "target.txt")
        );
        assertLinesMatch(
                List.of("dat", "run"),
                readZipTextEntry(packed, "target-ext.txt")
        );
    }

    @Test
    void packExcludesConfiguredOsSpecificEntriesFromOutputZip() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of(
                "__MACOSX/._logo.png", text("mac"),
                ".DS_Store", text("ds-store"),
                "Thumbs.db", text("thumbs"),
                ".idea/workspace.xml", text("idea"),
                "assets/blob.dat", text("dat")
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("sample.zip");
        List<String> names = listZipEntries(packed);
        assertFalse(names.contains("__MACOSX/._logo.png"));
        assertFalse(names.contains(".DS_Store"));
        assertFalse(names.contains(".DS_Store.txt"));
        assertFalse(names.contains("Thumbs.db"));
        assertFalse(names.contains(".idea/workspace.xml"));
        assertTrue(names.contains("assets/blob.dat.txt"));
    }

    @Test
    void packSkipsBlockedImageExtensionsEvenWhenTargetExtListsThem() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of(
                "assets/logo.png", text("png"),
                "assets/photo.jpg", text("jpg"),
                "assets/blob.dat", text("dat")
        ));
        Files.writeString(tempDir.resolve("target-ext.txt"), "png\njpg\ndat\n", StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("sample.zip");
        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("assets/logo.png"));
        assertTrue(names.contains("assets/photo.jpg"));
        assertTrue(names.contains("assets/blob.dat.txt"));
        assertFalse(names.contains("assets/logo.png.txt"));
        assertFalse(names.contains("assets/photo.jpg.txt"));
        assertLinesMatch(List.of("assets/blob.dat.txt"), readZipTextEntry(packed, "target.txt"));
        assertLinesMatch(List.of("dat"), readZipTextEntry(packed, "target-ext.txt"));
    }

    @Test
    void unpackUsesEmbeddedMetadataAndRemovesIt() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of(
                "assets/blob.dat", text("dat"),
                "config/settings.xml", text("xml"),
                "bin/run", text("run")
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("sample.zip");
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("assets/blob.dat"));
        assertTrue(names.contains("config/settings.xml"));
        assertTrue(names.contains("bin/run"));
        assertFalse(names.contains("assets/blob.dat.txt"));
        assertFalse(names.contains("bin/run.txt"));
        assertFalse(names.contains("target.txt"));
        assertFalse(names.contains("target-ext.txt"));
    }

    @Test
    void unpackConvertsJarLikeZipBackToJar() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createJar(input, Map.of(
                "assets/blob.dat", text("dat"),
                "bin/run", text("run")
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("sample.zip");
        assertTrue(Files.exists(packed));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        Path unpackedJar = tempDir.resolve("sample.jar");
        assertTrue(Files.exists(unpackedJar));
        assertFalse(Files.exists(packed));

        try (JarFile jarFile = new JarFile(unpackedJar.toFile())) {
            assertNotNull(jarFile.getManifest());
            assertNotNull(jarFile.getEntry("assets/blob.dat"));
            assertNotNull(jarFile.getEntry("bin/run"));
            assertTrue(jarFile.getEntry("target.txt") == null);
            assertTrue(jarFile.getEntry("target-ext.txt") == null);
            assertEquals("dat", readJarEntryText(jarFile, "assets/blob.dat"));
            assertEquals("run", readJarEntryText(jarFile, "bin/run"));
        }
    }

    @Test
    void packAndUnpackRewriteNestedPackages() throws Exception {
        Path nestedJar = tempDir.resolve("nested.jar");
        createZip(nestedJar, Map.of(
                "assets/blob.dat", text("nested-dat"),
                "bin/run", text("nested-run"),
                "config/settings.xml", text("nested-xml")
        ));

        Path input = tempDir.resolve("outer.jar");
        createZip(input, Map.of(
                "assets/root.dat", text("root-dat"),
                "lib/nested.jar", Files.readAllBytes(nestedJar)
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        Path packed = tempDir.resolve("outer.zip");
        byte[] packedNestedBytes = readZipEntryBytes(packed, "lib/nested.jar");
        List<String> packedNestedNames = listZipEntries(new ByteArrayInputStream(packedNestedBytes));
        assertTrue(packedNestedNames.contains("assets/blob.dat.txt"));
        assertTrue(packedNestedNames.contains("bin/run.txt"));
        assertFalse(packedNestedNames.contains("config/settings.xml.txt"));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        Path unpackedOuter = tempDir.resolve("outer.zip");
        byte[] unpackedNestedBytes = readZipEntryBytes(unpackedOuter, "lib/nested.jar");
        List<String> unpackedNestedNames = listZipEntries(new ByteArrayInputStream(unpackedNestedBytes));
        assertTrue(unpackedNestedNames.contains("assets/blob.dat"));
        assertTrue(unpackedNestedNames.contains("bin/run"));
        assertTrue(unpackedNestedNames.contains("config/settings.xml"));
        assertFalse(unpackedNestedNames.contains("target.txt"));
        assertFalse(unpackedNestedNames.contains("target-ext.txt"));
    }

    @Test
    void packRewritesJarInJarInJarRecursively() throws Exception {
        // level 3 (innermost)
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of(
                "deep/blob.dat", text("inner-dat"),
                "deep/run", text("inner-run"),
                "deep/keep.xml", text("inner-xml")
        ));
        // level 2
        Path midJar = tempDir.resolve("mid.jar");
        createZip(midJar, Map.of(
                "lib/inner.jar", Files.readAllBytes(innerJar),
                "mid/blob.dat", text("mid-dat")
        ));
        // level 1 (outer)
        Path outer = tempDir.resolve("outer.jar");
        createZip(outer, Map.of(
                "lib/mid.jar", Files.readAllBytes(midJar),
                "assets/root.dat", text("root-dat")
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", outer.toString()));

        Path packed = tempDir.resolve("outer.zip");
        byte[] midBytes = readZipEntryBytes(packed, "lib/mid.jar");
        byte[] innerBytes = readNestedEntryBytes(midBytes, "lib/inner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(innerBytes));

        // The innermost (level-3) entries must be packed too.
        assertTrue(innerNames.contains("deep/blob.dat.txt"), "innermost .dat not packed: " + innerNames);
        assertTrue(innerNames.contains("deep/run.txt"), "innermost extensionless not packed: " + innerNames);
        assertFalse(innerNames.contains("deep/keep.xml.txt"), "xml should be excluded: " + innerNames);
    }

    @Test
    void packRewritesStoredNestedJarsRecursively() throws Exception {
        // Fat-jar style: nested jars stored uncompressed (STORED), like Spring Boot.
        Path innerJar = tempDir.resolve("inner.jar");
        createJar(innerJar, Map.of(
                "deep/blob.dat", text("inner-dat"),
                "deep/run", text("inner-run")
        ));
        Path midJar = tempDir.resolve("mid.jar");
        createStoredJar(midJar, Map.of("lib/inner.jar", Files.readAllBytes(innerJar)));
        Path outer = tempDir.resolve("outer.jar");
        createStoredJar(outer, Map.of("lib/mid.jar", Files.readAllBytes(midJar)));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", outer.toString()));

        Path packed = tempDir.resolve("outer.zip");
        byte[] midBytes = readNestedEntryBytes(readZipEntryBytes(packed, "lib/mid.jar"), "lib/inner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(midBytes));
        assertTrue(innerNames.contains("deep/blob.dat.txt"), "innermost STORED .dat not packed: " + innerNames);
        assertTrue(innerNames.contains("deep/run.txt"), "innermost STORED extensionless not packed: " + innerNames);
    }

    @Test
    void packThenUnpackRestoresJarInJarInJar() throws Exception {
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of("deep/blob.dat", text("inner-dat"), "deep/run", text("inner-run")));
        Path midJar = tempDir.resolve("mid.jar");
        createZip(midJar, Map.of("lib/inner.jar", Files.readAllBytes(innerJar)));
        Path outer = tempDir.resolve("outer.jar");
        createZip(outer, Map.of("lib/mid.jar", Files.readAllBytes(midJar)));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", outer.toString()));
        Path packed = tempDir.resolve("outer.zip");
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        // After unpack, the innermost entries must be restored to their original names/content.
        byte[] midBytes = readNestedEntryBytes(readZipEntryBytes(packed, "lib/mid.jar"), "lib/inner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(midBytes));
        assertTrue(innerNames.contains("deep/blob.dat"), "innermost .dat not restored: " + innerNames);
        assertTrue(innerNames.contains("deep/run"), "innermost extensionless not restored: " + innerNames);
        assertFalse(innerNames.contains("deep/blob.dat.txt"), "still packed after unpack: " + innerNames);
    }

    @Test
    void unpackKeepsGenuineTxtFilesUsingEmbeddedRenameList() throws Exception {
        // A file that was really named *.txt in the original package is untouched by pack
        // (txt is never a pack target), so unpack must not strip its extension either. The
        // embedded target.txt rename list is what tells the two cases apart.
        Path pkg = tempDir.resolve("app.jar");
        createZip(pkg, Map.of(
                "docs/readme.txt", text("genuine text file"),
                "conf/settings.cfg", text("packed file")));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("cfg"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", pkg.toString()));
        Path packed = tempDir.resolve("app.zip");
        List<String> packedNames = listZipEntries(packed);
        assertTrue(packedNames.contains("docs/readme.txt"), "genuine txt renamed by pack: " + packedNames);
        assertTrue(packedNames.contains("conf/settings.cfg.txt"), "target ext not packed: " + packedNames);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        List<String> unpackedNames = listZipEntries(packed);
        assertTrue(unpackedNames.contains("docs/readme.txt"),
                "genuine txt lost its extension on unpack: " + unpackedNames);
        assertFalse(unpackedNames.contains("docs/readme"),
                "genuine txt was stripped on unpack: " + unpackedNames);
        assertTrue(unpackedNames.contains("conf/settings.cfg"), "packed entry not restored: " + unpackedNames);
    }

    @Test
    void unpackRecursesIntoRenamedNestedArchives() throws Exception {
        // When target-ext.txt lists "jar", pack renames nested archives to .jar.txt AND packs
        // their contents. Unpack must reverse BOTH: un-rename the archive and recurse to restore
        // its inner entries.
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of("deep/blob.dat", text("inner-dat")));
        Path outer = tempDir.resolve("outer.jar");
        createZip(outer, Map.of("lib/inner.jar", Files.readAllBytes(innerJar)));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("jar", "dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", outer.toString()));
        Path packed = tempDir.resolve("outer.zip");
        // pack renamed the nested archive and packed its contents
        byte[] packedInner = readZipEntryBytes(packed, "lib/inner.jar.txt");
        assertTrue(listZipEntries(new ByteArrayInputStream(packedInner)).contains("deep/blob.dat.txt"));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        byte[] restoredInner = readZipEntryBytes(packed, "lib/inner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(restoredInner));
        assertTrue(innerNames.contains("deep/blob.dat"), "inner entry not restored on unpack: " + innerNames);
        assertFalse(innerNames.contains("deep/blob.dat.txt"), "inner entry still packed after unpack: " + innerNames);
    }

    private static void createStoredJar(Path path, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                byte[] data = e.getValue();
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
                jos.putNextEntry(entry);
                jos.write(data);
                jos.closeEntry();
            }
        }
    }

    private static byte[] readNestedEntryBytes(byte[] zipBytes, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return zis.readAllBytes();
                }
            }
        }
        throw new AssertionError("nested entry not found: " + entryName);
    }

    @Test
    void identifyFindsExtensionsInsideJarInJarInJar() throws Exception {
        Path inner = tempDir.resolve("inner.jar");
        createZip(inner, Map.of("deep/only.xyz", text("x")));
        Path mid = tempDir.resolve("mid.jar");
        createZip(mid, Map.of("lib/inner.jar", Files.readAllBytes(inner)));
        Path outer = tempDir.resolve("outer.jar");
        createZip(outer, Map.of("lib/mid.jar", Files.readAllBytes(mid)));

        List<String> exts = PackagerInspector.collectUniqueExtensions(outer);
        assertTrue(exts.contains("xyz"), "deep-only extension not found by identify: " + exts);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void createZip(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
    }

    private static void createJar(Path path, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "sample.Main");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jos.putNextEntry(new ZipEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
    }

    private static List<String> listZipEntries(Path path) throws IOException {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
            Collections.sort(names);
            return names;
        }
    }

    private static List<String> listZipEntries(InputStream input) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input)) {
            List<String> names = new ArrayList<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            Collections.sort(names);
            return names;
        }
    }

    private static List<String> readZipTextEntry(Path zipPath, String entryName) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            assertNotNull(entry, "Missing zip entry: " + entryName);
            try (InputStream in = zipFile.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .filter(line -> !line.isBlank())
                        .toList();
            }
        }
    }

    private static byte[] readZipEntryBytes(Path zipPath, String entryName) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zipFile.getEntry(entryName);
            assertNotNull(entry, "Missing zip entry: " + entryName);
            try (InputStream in = zipFile.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static String readJarEntryText(JarFile jarFile, String entryName) throws IOException {
        try (InputStream in = jarFile.getInputStream(jarFile.getEntry(entryName))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // --- non-zip payloads named like archives -------------------------------------------

    @Test
    void packAndUnpackPassThroughFakeArchiveEntries() throws Exception {
        byte[] fakeJar = text("this is not a zip at all");
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "resources/template.jar", fakeJar,
                "assets/blob.dat", text("dat")
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("jar", "dat"), StandardCharsets.UTF_8);

        String packOut = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(packOut.contains("looks like an archive but is not"), packOut);

        Path packed = tempDir.resolve("app.zip");
        // renamed by name, but the payload was copied verbatim instead of being rewritten
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                fakeJar, readZipEntryBytes(packed, "resources/template.jar.txt"));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                fakeJar, readZipEntryBytes(packed, "resources/template.jar"));
    }

    @Test
    void unpackKeepsGenuineJarTxtFileWhenRenameListIsPresent() throws Exception {
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of("deep/blob.dat", text("inner-dat")));
        byte[] genuineText = text("genuine text, not an archive");
        Path input = tempDir.resolve("outer.jar");
        createZip(input, Map.of(
                "lib/inner.jar", Files.readAllBytes(innerJar),
                "notes/build.jar.txt", genuineText
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("jar", "dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("outer.zip");
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("notes/build.jar.txt"), "genuine jar.txt lost its name: " + names);
        assertFalse(names.contains("notes/build.jar"), "genuine jar.txt was un-renamed: " + names);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                genuineText, readZipEntryBytes(packed, "notes/build.jar.txt"));
        byte[] restoredInner = readZipEntryBytes(packed, "lib/inner.jar");
        assertTrue(listZipEntries(new ByteArrayInputStream(restoredInner)).contains("deep/blob.dat"));
    }

    // --- rename collisions and reserved names -------------------------------------------

    @Test
    void packKeepsBothEntriesOnRenameCollision() throws Exception {
        byte[] cfgData = text("REAL CFG DATA");
        byte[] txtData = text("GENUINE TXT");
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "conf/a.cfg", cfgData,
                "conf/a.cfg.txt", txtData
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("cfg"), StandardCharsets.UTF_8);

        String packOut = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(packOut.contains("WARN not renaming conf/a.cfg"), packOut);

        Path packed = tempDir.resolve("app.zip");
        org.junit.jupiter.api.Assertions.assertArrayEquals(cfgData, readZipEntryBytes(packed, "conf/a.cfg"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(txtData, readZipEntryBytes(packed, "conf/a.cfg.txt"));
        assertFalse(readZipTextEntry(packed, "target.txt").contains("conf/a.cfg.txt"),
                "suppressed rename must not be recorded");

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(cfgData, readZipEntryBytes(packed, "conf/a.cfg"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(txtData, readZipEntryBytes(packed, "conf/a.cfg.txt"));
    }

    @Test
    void packPreservesUserFilesWhoseRenameWouldHijackMetadataNames() throws Exception {
        byte[] targetData = text("USER DATA IN plain target");
        byte[] targetExtData = text("USER DATA IN plain target-ext");
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "target", targetData,
                "target-ext", targetExtData,
                "conf/c.cfg", text("cfg data")
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("cfg"), StandardCharsets.UTF_8);

        String packOut = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(packOut.contains("reserved for packager metadata"), packOut);

        Path packed = tempDir.resolve("app.zip");
        // the metadata entries hold the real lists, not user bytes
        assertLinesMatch(List.of("conf/c.cfg.txt"), readZipTextEntry(packed, "target.txt"));
        assertLinesMatch(List.of("cfg"), readZipTextEntry(packed, "target-ext.txt"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(targetData, readZipEntryBytes(packed, "target"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(targetExtData, readZipEntryBytes(packed, "target-ext"));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("target"), names.toString());
        assertTrue(names.contains("target-ext"), names.toString());
        assertTrue(names.contains("conf/c.cfg"), names.toString());
        assertFalse(names.contains("target.txt"), names.toString());
        assertFalse(names.contains("target-ext.txt"), names.toString());
    }

    @Test
    void packWarnsWhenInputCarriesEntriesNamedLikeMetadata() throws Exception {
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "target.txt", text("USER DATA IN target.txt"),
                "assets/blob.dat", text("dat")
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat"), StandardCharsets.UTF_8);

        String packOut = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(packOut.contains("conflicts with packager metadata"), packOut);

        Path packed = tempDir.resolve("app.zip");
        // pack's own metadata replaced the user entry, and no phantom rename line exists
        assertLinesMatch(List.of("assets/blob.dat.txt"), readZipTextEntry(packed, "target.txt"));
    }

    @Test
    void packFailsOnDuplicateEntries() throws Exception {
        byte[] first = text("FIRST COPY");
        byte[] second = text("SECOND COPY");
        Path input = tempDir.resolve("app.jar");
        Files.write(input, rawStoredZip(List.of(
                Map.entry("dup.bin", first),
                Map.entry("dup.bin", second)
        )));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("bin"), StandardCharsets.UTF_8);

        String packErr = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(packErr.contains("Duplicate entry detected"), packErr);
    }

    // --- hostile and awkward entry names ------------------------------------------------

    @Test
    void entryNamesIllegalOnWindowsRoundTrip() throws Exception {
        // ':' '*' '?' are legal in zip entry names but illegal in Windows paths; entry
        // names must never be routed through java.nio.file.Path.
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "weird/a:b.dat", text("colon"),
                "weird/q*r", text("star-extensionless"),
                "weird/who?.cfg", text("question")
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat", "cfg"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("app.zip");
        List<String> packedNames = listZipEntries(packed);
        assertTrue(packedNames.contains("weird/a:b.dat.txt"), packedNames.toString());
        assertTrue(packedNames.contains("weird/q*r.txt"), packedNames.toString());
        assertTrue(packedNames.contains("weird/who?.cfg.txt"), packedNames.toString());

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("weird/a:b.dat"), names.toString());
        assertTrue(names.contains("weird/q*r"), names.toString());
        assertTrue(names.contains("weird/who?.cfg"), names.toString());
    }

    @Test
    void spaceAndNewlineBearingEntryNamesRoundTrip() throws Exception {
        byte[] spaced = text("leading space");
        byte[] newlined = text("newline name");
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                " conf/x.dat", spaced,
                "we\nird.dat", newlined
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("app.zip");
        List<String> packedNames = listZipEntries(packed);
        assertTrue(packedNames.contains(" conf/x.dat.txt"), packedNames.toString());
        // CR/LF-bearing names cannot ride the line-based rename list, so they are never renamed
        assertTrue(packedNames.contains("we\nird.dat"), packedNames.toString());

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));
        org.junit.jupiter.api.Assertions.assertArrayEquals(spaced, readZipEntryBytes(packed, " conf/x.dat"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(newlined, readZipEntryBytes(packed, "we\nird.dat"));
    }

    // --- exclude patterns on directory entries ------------------------------------------

    @Test
    void packDropsExcludedDirectoryEntries() throws Exception {
        Path input = tempDir.resolve("app.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(input))) {
            for (String dir : List.of("__MACOSX/", ".idea/", "assets/")) {
                zos.putNextEntry(new ZipEntry(dir));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("assets/blob.dat"));
            zos.write(text("dat"));
            zos.closeEntry();
        }

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        List<String> names = listZipEntries(tempDir.resolve("app.zip"));
        assertFalse(names.contains("__MACOSX/"), names.toString());
        assertFalse(names.contains(".idea/"), names.toString());
        assertTrue(names.contains("assets/"), names.toString());
        assertTrue(names.contains("assets/blob.dat.txt"), names.toString());
    }

    // --- metadata hygiene ----------------------------------------------------------------

    @Test
    void packedNestedArchivesCarryNoMetadataEntries() throws Exception {
        Path nestedJar = tempDir.resolve("nested.jar");
        createZip(nestedJar, Map.of("assets/blob.dat", text("nested-dat")));
        Path input = tempDir.resolve("outer.jar");
        createZip(input, Map.of("lib/nested.jar", Files.readAllBytes(nestedJar)));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        byte[] nestedBytes = readZipEntryBytes(tempDir.resolve("outer.zip"), "lib/nested.jar");
        List<String> nestedNames = listZipEntries(new ByteArrayInputStream(nestedBytes));
        assertFalse(nestedNames.contains("target.txt"), nestedNames.toString());
        assertFalse(nestedNames.contains("target-ext.txt"), nestedNames.toString());
        assertTrue(nestedNames.contains("assets/blob.dat.txt"), nestedNames.toString());
    }

    @Test
    void packedMetadataUsesLfSeparatorsOnly() throws Exception {
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "a/x.dat", text("x"),
                "b/y.dat", text("y")
        ));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        byte[] listBytes = readZipEntryBytes(tempDir.resolve("app.zip"), "target.txt");
        String content = new String(listBytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("\n"), content);
        assertFalse(content.contains("\r"), "packed metadata must not depend on the producing OS");
    }

    // --- backward compatibility with older packs ----------------------------------------

    @Test
    void unpackFallsBackToExtensionHeuristicWithoutRenameList() throws Exception {
        // Hand-built "old" package: embedded target-ext.txt but no target.txt rename list.
        Path packed = tempDir.resolve("old.zip");
        createZip(packed, Map.of(
                "target-ext.txt", text("dat"),
                "assets/blob.dat.txt", text("dat-data"),
                "bin/run.txt", text("run-data"),
                "docs/readme.txt", text("readme-data")
        ));

        String out = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString())));
        assertTrue(out.contains("WARN embedded target.txt not found"), out);

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("assets/blob.dat"), names.toString());
        assertTrue(names.contains("bin/run"), names.toString());
        // documented legacy limitation: without the rename list, a genuine .txt whose
        // stem is extensionless is un-renamed by the heuristic
        assertTrue(names.contains("docs/readme"), names.toString());
        assertFalse(names.contains("target-ext.txt"), names.toString());
    }

    @Test
    void unpackAppliesShapeCompatForOldRenameListsMissingNestedArchives() throws Exception {
        // Mid-vintage lists recorded nested entries but not the nested archive's own rename.
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of("deep/blob.dat.txt", text("inner-dat")));
        Path packed = tempDir.resolve("old.zip");
        createZip(packed, Map.of(
                "target-ext.txt", text("jar\ndat"),
                "target.txt", text("assets/root.dat.txt\nlib/inner.jar!/deep/blob.dat.txt"),
                "assets/root.dat.txt", text("root-dat"),
                "lib/inner.jar.txt", Files.readAllBytes(innerJar)
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("assets/root.dat"), names.toString());
        assertTrue(names.contains("lib/inner.jar"), "shape-compat un-rename did not fire: " + names);
        byte[] inner = readZipEntryBytes(packed, "lib/inner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(inner));
        assertTrue(innerNames.contains("deep/blob.dat"), "nested entries not restored: " + innerNames);
    }

    @Test
    void unpackAcceptsCrlfJoinedMetadataFromOlderPacks() throws Exception {
        Path packed = tempDir.resolve("old.zip");
        createZip(packed, Map.of(
                "target-ext.txt", text("dat\r\n"),
                "target.txt", text("conf/x.dat.txt\r\nassets/y.dat.txt\r\n"),
                "conf/x.dat.txt", text("x-data"),
                "assets/y.dat.txt", text("y-data"),
                "docs/readme.txt", text("readme")
        ));

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("conf/x.dat"), names.toString());
        assertTrue(names.contains("assets/y.dat"), names.toString());
        assertTrue(names.contains("docs/readme.txt"), "genuine txt must survive with the list present: " + names);
    }

    // --- CLI behavior ---------------------------------------------------------------------

    @Test
    void unpackReturnsNonZeroWhenEmbeddedMetadataMissing() throws Exception {
        Path plain = tempDir.resolve("plain.zip");
        createZip(plain, Map.of("assets/blob.dat.txt", text("dat")));
        byte[] before = Files.readAllBytes(plain);

        String out = captureStdout(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("unpack", "--in", plain.toString())));
        assertTrue(out.contains("WARN embedded target-ext.txt not found; aborting"), out);
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(plain),
                "aborted unpack must leave the input untouched");
    }

    @Test
    void commandsFailWithOneLineErrorsForPredictableMistakes() throws Exception {
        Path missing = tempDir.resolve("nope.jar");
        Path wrongExt = tempDir.resolve("archive.tar");
        Files.write(wrongExt, text("tar-ish"));
        Path corrupt = tempDir.resolve("corrupt.zip");
        Files.write(corrupt, text("garbage bytes, no zip structure"));

        for (String command : List.of("identify", "pack", "unpack")) {
            String err = captureStderr(() ->
                    assertEquals(1, new CommandLine(new PackagerApp()).execute(command, "--in", missing.toString())));
            assertTrue(err.contains(command + " failed: input not found"), command + " -> " + err);
            assertFalse(err.contains("at java."), "stack trace leaked: " + err);
        }
        String err = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("pack", "--in", wrongExt.toString())));
        assertTrue(err.contains("Input must be a .jar or .zip"), err);
        err = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("unpack", "--in", corrupt.toString())));
        assertTrue(err.contains("unpack failed:"), err);
        assertFalse(err.contains("at java."), "stack trace leaked: " + err);
    }

    @Test
    void identifyWritesTargetExtNextToInput() throws Exception {
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of(
                "assets/blob.dat", text("dat"),
                "bin/run", text("run"),
                "BOOT-INF/classes/App.class", text("class")
        ));
        Path targetExt = tempDir.resolve("target-ext.txt");
        Files.write(targetExt, List.of("stale"), StandardCharsets.UTF_8);

        String out = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("identify", "--in", input.toString())));

        assertLinesMatch(List.of("dat", "run"), Files.readAllLines(targetExt, StandardCharsets.UTF_8));
        assertTrue(out.contains("Wrote 2 entries"), out);
    }

    @Test
    void packWarnsBeforeOverwritingExistingSiblingZip() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of("assets/blob.dat", text("dat")));
        Path existing = tempDir.resolve("sample.zip");
        Files.write(existing, text("unrelated pre-existing bytes"));

        String out = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(out.contains("WARN overwriting existing file"), out);
        assertTrue(listZipEntries(existing).contains("assets/blob.dat.txt"));
    }

    // --- sequential-readability guard -----------------------------------------------------

    @Test
    void unpackRefusesArchivesWithLeadingPreambleInsteadOfWipingThem() throws Exception {
        Path input = tempDir.resolve("sample.jar");
        createZip(input, Map.of("assets/blob.dat", text("dat")));
        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("sample.zip");

        // Self-extractor-style preamble: readable via the central directory (ZipFile),
        // invisible to the sequential ZipInputStream pass.
        byte[] zipBytes = Files.readAllBytes(packed);
        byte[] withPreamble = new byte[zipBytes.length + 8];
        System.arraycopy(text("PREAMBLE"), 0, withPreamble, 0, 8);
        System.arraycopy(zipBytes, 0, withPreamble, 8, zipBytes.length);
        Path preambled = tempDir.resolve("preambled.zip");
        Files.write(preambled, withPreamble);

        String err = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("unpack", "--in", preambled.toString())));
        assertTrue(err.contains("not sequentially readable"), err);
        org.junit.jupiter.api.Assertions.assertArrayEquals(withPreamble, Files.readAllBytes(preambled),
                "guarded unpack must leave the input untouched");
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("airbridge-")),
                    "temp files must not leak");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void unpackKeepsGenuineZipShapedJarTxtSiblingOfRealJar(boolean realJarFirst) throws Exception {
        // Regression: shape-compat un-rename must NOT fire on current-format packages. A real
        // a.jar plus a genuine (zip-shaped) a.jar.txt, with jar as a target ext: pack suppresses
        // the a.jar -> a.jar.txt collision, and unpack must keep BOTH — not un-rename a.jar.txt
        // onto a.jar and drop one. The real a.jar carries recorded inner renames, so the guard
        // must reject the candidate purely because a.jar already exists at this level — which
        // makes the outcome independent of which entry the stream reads first.
        Path r = tempDir.resolve("real.jar");
        createZip(r, Map.of("inside/x.dat", text("real-inner")));
        byte[] realJar = Files.readAllBytes(r);
        Path g = tempDir.resolve("genuine.jar");
        createZip(g, Map.of("whatever/y.dat", text("genuine-inner")));
        byte[] genuineZipShaped = Files.readAllBytes(g);

        Path input = tempDir.resolve("app.jar");
        List<Map.Entry<String, byte[]>> entries = realJarFirst
                ? List.of(Map.entry("a.jar", realJar), Map.entry("a.jar.txt", genuineZipShaped))
                : List.of(Map.entry("a.jar.txt", genuineZipShaped), Map.entry("a.jar", realJar));
        createOrderedZip(input, entries);
        Files.write(tempDir.resolve("target-ext.txt"), List.of("jar", "dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("app.zip");
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("a.jar"), "real a.jar dropped (order realJarFirst=" + realJarFirst + "): " + names);
        assertTrue(names.contains("a.jar.txt"),
                "genuine zip-shaped a.jar.txt dropped/renamed (order realJarFirst=" + realJarFirst + "): " + names);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                genuineZipShaped, readZipEntryBytes(packed, "a.jar.txt"));
    }

    private static void createOrderedZip(Path path, List<Map.Entry<String, byte[]>> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
    }

    @Test
    void nestedArchiveWithLineBreakInNameRoundTripsWithoutCorruptingTargetList() throws Exception {
        // Regression: the CR/LF rename guard must consider the recorded (prefixed) name, not
        // just the leaf. An archive entry whose own name contains '\n' must not have its inner
        // entries renamed, since the recorded "outer!/inner" line would split the list.
        Path innerJar = tempDir.resolve("inner.jar");
        createZip(innerJar, Map.of("config.cfg", text("cfg-data"), "deep/run", text("run-data")));
        Path input = tempDir.resolve("outer.jar");
        createZip(input, Map.of("in\ner.jar", Files.readAllBytes(innerJar)));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("cfg", "jar"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("outer.zip");
        // the embedded list must not contain a broken line from the CR/LF name
        for (String line : readZipTextEntry(packed, "target.txt")) {
            assertFalse(line.startsWith("er.jar"), "target.txt split on a line break: " + line);
        }
        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        byte[] restoredInner = readZipEntryBytes(packed, "in\ner.jar");
        List<String> innerNames = listZipEntries(new ByteArrayInputStream(restoredInner));
        assertTrue(innerNames.contains("config.cfg"), "inner entry corrupted: " + innerNames);
        assertTrue(innerNames.contains("deep/run"), "inner entry corrupted: " + innerNames);
    }

    @Test
    void unpackRefusesConcatenatedZipInsteadOfWipingTrailingContent() throws Exception {
        // Regression: two valid zips concatenated. ZipFile reads the trailing EOCD (zip B) so
        // metadata/central-directory names come from B, but the sequential pass sees only zip A.
        // The guard must catch this partial divergence and abort, not wipe B in place.
        Path a = tempDir.resolve("a.jar");
        createZip(a, Map.of("decoy.dat", text("decoy")));
        Path bInput = tempDir.resolve("b.jar");
        createZip(bInput, Map.of("important.cfg", text("IMPORTANT PAYLOAD")));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("cfg", "dat"), StandardCharsets.UTF_8);
        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", bInput.toString()));

        byte[] concat;
        {
            byte[] aBytes = Files.readAllBytes(a);
            byte[] bPacked = Files.readAllBytes(tempDir.resolve("b.zip"));
            concat = new byte[aBytes.length + bPacked.length];
            System.arraycopy(aBytes, 0, concat, 0, aBytes.length);
            System.arraycopy(bPacked, 0, concat, aBytes.length, bPacked.length);
        }
        Path victim = tempDir.resolve("victim.zip");
        Files.write(victim, concat);

        String err = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("unpack", "--in", victim.toString())));
        assertTrue(err.contains("not sequentially readable"), err);
        org.junit.jupiter.api.Assertions.assertArrayEquals(concat, Files.readAllBytes(victim),
                "guarded unpack must leave the concatenated input untouched");
    }

    @Test
    void packDoesNotOrphanChildrenOfNameOnlyExcludedDirectories() throws Exception {
        // ".Trash-*" is a name-only exclude pattern. Dropping the ".Trash-1000/" directory entry
        // while keeping its children would orphan the subtree; keep the whole subtree instead.
        Path input = tempDir.resolve("app.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(input))) {
            for (String dir : List.of(".Trash-1000/", "assets/")) {
                zos.putNextEntry(new ZipEntry(dir));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry(".Trash-1000/cache.dat"));
            zos.write(text("trash"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("assets/blob.dat"));
            zos.write(text("dat"));
            zos.closeEntry();
        }
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat"), StandardCharsets.UTF_8);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));

        List<String> names = listZipEntries(tempDir.resolve("app.zip"));
        // either both the dir entry and its child survive, or neither — never an orphaned child
        boolean dirKept = names.contains(".Trash-1000/");
        boolean childKept = names.contains(".Trash-1000/cache.dat.txt");
        assertEquals(dirKept, childKept, "orphaned subtree: dir=" + dirKept + " child=" + childKept + " " + names);
        assertTrue(names.contains("assets/blob.dat.txt"), names.toString());
    }

    @Test
    void unpackReportsCorruptMetadataEntryAsOneLineNotStackTrace() throws Exception {
        // A packed zip whose target.txt DEFLATE stream is corrupt: reading it must surface a
        // one-line error, not an UncheckedIOException stack trace.
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of("assets/blob.dat", text("dat")));
        Files.write(tempDir.resolve("target-ext.txt"), List.of("dat"), StandardCharsets.UTF_8);
        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("app.zip");

        // Corrupt the compressed bytes of the target.txt entry in place.
        byte[] bytes = Files.readAllBytes(packed);
        byte[] needle = "target.txt".getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(bytes, needle, 0);
        assertTrue(at >= 0, "target.txt name not found in zip");
        // flip bytes a little past the local header name to damage the deflate stream
        for (int i = at + needle.length + 8; i < Math.min(bytes.length, at + needle.length + 24); i++) {
            bytes[i] ^= 0x5A;
        }
        Files.write(packed, bytes);

        String err = captureStderr(() -> {
            int code = new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString());
            assertEquals(1, code);
        });
        assertFalse(err.contains("at java."), "stack trace leaked: " + err);
        assertTrue(err.contains("unpack failed:"), err);
    }

    @Test
    void unpackFastPathUnRenameCollisionKeepsBothEntries() throws Exception {
        // A hand-built packed zip whose target.txt records "a.dat.txt" while a plain "a.dat"
        // also exists. On unpack, "a.dat.txt" un-renames to "a.dat" and collides on the
        // streaming (non-archive) fast path; the entry must be kept under its original name,
        // not silently dropped.
        byte[] plain = text("PLAIN A.DAT");
        byte[] renamed = text("PACKED A.DAT CONTENT");
        Path packed = tempDir.resolve("hand.zip");
        createOrderedZip(packed, List.of(
                Map.entry("target-ext.txt", text("dat")),
                Map.entry("target.txt", text("a.dat.txt")),
                Map.entry("a.dat", plain),
                Map.entry("a.dat.txt", renamed)
        ));

        String out = captureStdout(() ->
                assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString())));

        List<String> names = listZipEntries(packed);
        assertTrue(names.contains("a.dat"), names.toString());
        assertTrue(names.contains("a.dat.txt"), "fast-path un-rename collision dropped an entry: " + names);
        // both payloads survive; the un-renamed one falls back to its original name
        List<byte[]> payloads = List.of(readZipEntryBytes(packed, "a.dat"), readZipEntryBytes(packed, "a.dat.txt"));
        assertTrue(payloads.stream().anyMatch(p -> java.util.Arrays.equals(p, plain)), "plain a.dat lost");
        assertTrue(payloads.stream().anyMatch(p -> java.util.Arrays.equals(p, renamed)), "packed content lost");
    }

    @Test
    void unpackDoesNotOverwriteExistingUserBakFile() throws Exception {
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of("assets/blob.dat", text("dat")));
        assertEquals(0, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString()));
        Path packed = tempDir.resolve("app.zip");
        Path existingBackup = tempDir.resolve("app.zip.bak");
        byte[] userBackup = text("user backup must survive");
        Files.write(existingBackup, userBackup);

        assertEquals(0, new CommandLine(new PackagerApp()).execute("unpack", "--in", packed.toString()));

        org.junit.jupiter.api.Assertions.assertArrayEquals(userBackup, Files.readAllBytes(existingBackup));
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith("airbridge-unpack-backup-")),
                    "successful unpack must not leak temporary backups");
        }
    }

    @Test
    void packReportsMalformedLocalTargetExtAsItsOwnError() throws Exception {
        Path input = tempDir.resolve("app.jar");
        createZip(input, Map.of("assets/blob.dat", text("dat")));
        // Invalid UTF-8 bytes in the user-edited target-ext.txt.
        Files.write(tempDir.resolve("target-ext.txt"), new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFF});

        String err = captureStderr(() ->
                assertEquals(1, new CommandLine(new PackagerApp()).execute("pack", "--in", input.toString())));
        assertTrue(err.contains("target-ext.txt is not valid UTF-8"),
                "malformed target-ext.txt should get its own message, got: " + err);
        assertFalse(err.contains("archive entry names are not valid UTF-8"),
                "must not borrow the archive-entry-name hint: " + err);
        assertFalse(err.contains("at java."), "stack trace leaked: " + err);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String captureStdout(Runnable action) {
        java.io.PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String captureStderr(Runnable action) {
        java.io.PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * Minimal STORED-only zip writer. Unlike ZipOutputStream it happily writes duplicate
     * entry names, which the zip format permits and append-style tools produce.
     */
    private static byte[] rawStoredZip(List<Map.Entry<String, byte[]>> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<int[]> centralMeta = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] data = e.getValue();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            int offset = out.size();
            writeIntLe(out, 0x04034b50);
            writeShortLe(out, 20);              // version needed
            writeShortLe(out, 0);               // flags
            writeShortLe(out, 0);               // method STORED
            writeShortLe(out, 0);               // mod time
            writeShortLe(out, 0x21);            // mod date 1980-01-01
            writeIntLe(out, (int) crc.getValue());
            writeIntLe(out, data.length);
            writeIntLe(out, data.length);
            writeShortLe(out, name.length);
            writeShortLe(out, 0);               // extra len
            out.writeBytes(name);
            out.writeBytes(data);
            centralMeta.add(new int[] { offset, (int) crc.getValue(), data.length });
        }
        int centralOffset = out.size();
        for (int i = 0; i < entries.size(); i++) {
            byte[] name = entries.get(i).getKey().getBytes(StandardCharsets.UTF_8);
            int[] meta = centralMeta.get(i);
            writeIntLe(out, 0x02014b50);
            writeShortLe(out, 20);              // version made by
            writeShortLe(out, 20);              // version needed
            writeShortLe(out, 0);               // flags
            writeShortLe(out, 0);               // method
            writeShortLe(out, 0);               // mod time
            writeShortLe(out, 0x21);            // mod date
            writeIntLe(out, meta[1]);
            writeIntLe(out, meta[2]);
            writeIntLe(out, meta[2]);
            writeShortLe(out, name.length);
            writeShortLe(out, 0);               // extra len
            writeShortLe(out, 0);               // comment len
            writeShortLe(out, 0);               // disk number
            writeShortLe(out, 0);               // internal attrs
            writeIntLe(out, 0);                 // external attrs
            writeIntLe(out, meta[0]);           // local header offset
            out.writeBytes(name);
        }
        int centralSize = out.size() - centralOffset;
        writeIntLe(out, 0x06054b50);
        writeShortLe(out, 0);                   // disk number
        writeShortLe(out, 0);                   // central dir start disk
        writeShortLe(out, entries.size());
        writeShortLe(out, entries.size());
        writeIntLe(out, centralSize);
        writeIntLe(out, centralOffset);
        writeShortLe(out, 0);                   // comment len
        return out.toByteArray();
    }

    private static void writeShortLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
