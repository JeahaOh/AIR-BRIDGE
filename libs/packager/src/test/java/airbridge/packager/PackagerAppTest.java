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
}
