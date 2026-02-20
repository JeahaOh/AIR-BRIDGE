package com.airbridge.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

public final class ArchiveInspector {
    private ArchiveInspector() {
    }

    public static List<String> collectFlattenedNames(Path archivePath, Set<String> targetExts) throws IOException {
        if (!isArchiveName(archivePath.getFileName().toString())) {
            throw new IllegalArgumentException("Input must be a .jar or .zip: " + archivePath);
        }

        List<String> results = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isArchiveName(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] payload = readAllBytes(in);
                        collectFromZipStream(new ByteArrayInputStream(payload), name + "!/", targetExts, results);
                    }
                    continue;
                }
                if (matchesExtension(name, targetExts)) {
                    results.add(name + ".txt");
                    continue;
                }
                if (isExtensionless(name)) {
                    results.add(name + ".txt");
                }
            }
        }

        results.sort(String::compareTo);
        return results;
    }

    public static List<String> collectUniqueExtensions(Path archivePath) throws IOException {
        if (!isArchiveName(archivePath.getFileName().toString())) {
            throw new IllegalArgumentException("Input must be a .jar or .zip: " + archivePath);
        }

        Set<String> results = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isArchiveName(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] payload = readAllBytes(in);
                        collectExtensionsFromZipStream(new ByteArrayInputStream(payload), results);
                    }
                    continue;
                }
                addExtensionToken(name, results);
            }
        }

        return new ArrayList<>(results);
    }

    private static void collectFromZipStream(InputStream input, String prefix, Set<String> targetExts, List<String> results)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isArchiveName(name)) {
                    byte[] payload = readAllBytes(zis);
                    collectFromZipStream(new ByteArrayInputStream(payload), prefix + name + "!/", targetExts, results);
                    continue;
                }
                if (matchesExtension(name, targetExts)) {
                    results.add(prefix + name + ".txt");
                    continue;
                }
                if (isExtensionless(name)) {
                    results.add(prefix + name + ".txt");
                }
                drain(zis);
            }
        }
    }

    private static void collectExtensionsFromZipStream(InputStream input, Set<String> results) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isArchiveName(name)) {
                    byte[] payload = readAllBytes(zis);
                    collectExtensionsFromZipStream(new ByteArrayInputStream(payload), results);
                    continue;
                }
                addExtensionToken(name, results);
                drain(zis);
            }
        }
    }

    private static void addExtensionToken(String name, Set<String> results) {
        String fileName = Path.of(name).getFileName().toString();
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

    private static boolean matchesExtension(String name, Set<String> targetExts) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return targetExts.contains(ext);
    }

    private static boolean isExtensionless(String name) {
        String fileName = Path.of(name).getFileName().toString();
        if (fileName.isBlank()) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1;
    }

    public static boolean isArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static void drain(InputStream input) throws IOException {
        input.transferTo(OutputStream.nullOutputStream());
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        input.transferTo(out);
        return out.toByteArray();
    }
}
