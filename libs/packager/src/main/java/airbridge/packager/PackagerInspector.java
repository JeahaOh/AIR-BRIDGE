package airbridge.packager;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class PackagerInspector {
    private PackagerInspector() {
    }

    public static List<String> collectUniqueExtensions(Path packagePath) throws IOException {
        return collectUniqueExtensions(packagePath, List.of());
    }

    public static List<String> collectUniqueExtensions(Path packagePath, List<String> excludedEntryPatterns) throws IOException {
        if (!isPackageName(packagePath.getFileName().toString())) {
            throw new IllegalArgumentException("Input must be a .jar or .zip: " + packagePath);
        }

        Set<String> results = new TreeSet<>();
        try (ZipFile zip = new ZipFile(packagePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (PackEntryFilters.matchesAny(name, excludedEntryPatterns)) {
                    continue;
                }
                if (isPackageName(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        PushbackInputStream pin = new PushbackInputStream(in, 4);
                        if (startsWithZipMagic(pin)) {
                            collectExtensionsFromZipStream(pin, excludedEntryPatterns, results);
                        } else {
                            // Named like an archive but not one: token it like a plain file.
                            addExtensionToken(name, results);
                        }
                    }
                    continue;
                }
                addExtensionToken(name, results);
            }
        }

        return new ArrayList<>(results);
    }

    /**
     * Scans a nested archive straight off the parent stream — no full-payload buffering.
     * The parent stream ends at the entry boundary, which is exactly where the nested
     * central directory stops the inner ZipInputStream.
     */
    private static void collectExtensionsFromZipStream(InputStream input, List<String> excludedEntryPatterns, Set<String> results)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new CloseShieldInputStream(input))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (PackEntryFilters.matchesAny(name, excludedEntryPatterns)) {
                    continue;
                }
                if (isPackageName(name)) {
                    PushbackInputStream pin = new PushbackInputStream(zis, 4);
                    if (startsWithZipMagic(pin)) {
                        collectExtensionsFromZipStream(pin, excludedEntryPatterns, results);
                    } else {
                        addExtensionToken(name, results);
                    }
                    continue;
                }
                addExtensionToken(name, results);
            }
        }
    }

    private static void addExtensionToken(String name, Set<String> results) {
        String fileName = EntryNames.lastSegment(name);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            if (!fileName.isBlank()) {
                results.add(fileName.toLowerCase(Locale.ROOT));
            }
            return;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        results.add(ext);
    }

    public static boolean isPackageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static boolean startsWithZipMagic(PushbackInputStream in) throws IOException {
        byte[] head = new byte[4];
        int read = 0;
        while (read < head.length) {
            int n = in.read(head, read, head.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (read > 0) {
            in.unread(head, 0, read);
        }
        return read == 4
                && head[0] == 0x50 && head[1] == 0x4B
                && head[2] == 0x03 && head[3] == 0x04;
    }

    /**
     * Lets the inner ZipInputStream be closed (releasing its Inflater) without closing
     * the enclosing entry stream mid-scan.
     */
    private static final class CloseShieldInputStream extends FilterInputStream {
        CloseShieldInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // keep the underlying stream open
        }
    }
}
