package com.airbridge.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ArchiveRewriter {
    private ArchiveRewriter() {
    }

    public static void rewriteInPlace(Path archivePath, Set<String> targetExts) throws IOException {
        Path abs = archivePath.toAbsolutePath().normalize();
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-fltn-", ".zip");
        try (InputStream in = Files.newInputStream(abs);
             OutputStream out = Files.newOutputStream(temp)) {
            rewriteStream(in, out, targetExts, RewriteMode.FLATTEN);
        }
        Files.move(temp, abs, StandardCopyOption.REPLACE_EXISTING);
    }

    public static Path rewriteToZip(Path archivePath, Set<String> targetExts) throws IOException {
        Path abs = archivePath.toAbsolutePath().normalize();
        String baseName = stripExtension(abs.getFileName().toString());
        Path output = abs.resolveSibling(baseName + ".zip");
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-fltn-", ".zip");
        try (InputStream in = Files.newInputStream(abs);
             OutputStream out = Files.newOutputStream(temp)) {
            rewriteStream(in, out, targetExts, RewriteMode.FLATTEN);
        }
        Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
        return output;
    }

    public static void rewriteInPlaceUnflatten(Path archivePath, Set<String> targetExts) throws IOException {
        Path abs = archivePath.toAbsolutePath().normalize();
        Path temp = Files.createTempFile(abs.getParent(), "airbridge-ufltn-", ".zip");
        try (InputStream in = Files.newInputStream(abs);
             OutputStream out = Files.newOutputStream(temp)) {
            rewriteStream(in, out, targetExts, RewriteMode.UNFLATTEN);
        }
        Files.move(temp, abs, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void rewriteStream(InputStream input, OutputStream output, Set<String> targetExts, RewriteMode mode)
            throws IOException {
        try (ZipInputStream zis = new ZipInputStream(input);
             ZipOutputStream zos = new ZipOutputStream(output)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    String dirName = entry.getName();
                    if (seen.add(dirName)) {
                        ZipEntry outEntry = copyEntry(entry, dirName);
                        zos.putNextEntry(outEntry);
                        zos.closeEntry();
                    } else {
                        System.out.println("WARN duplicate entry skipped: " + dirName);
                    }
                    continue;
                }

                String originalName = entry.getName();
                String newName = renameIfMatch(originalName, targetExts, mode);

                byte[] payload = readAllBytes(zis);
                if (ArchiveInspector.isArchiveName(originalName)) {
                    payload = rewriteNestedArchive(payload, targetExts, mode);
                }

                if (seen.add(newName)) {
                    ZipEntry outEntry = copyEntry(entry, newName);
                    writeEntry(zos, outEntry, payload);
                } else {
                    System.out.println("WARN duplicate entry skipped: " + newName);
                }
            }
        }
    }

    private static byte[] rewriteNestedArchive(byte[] payload, Set<String> targetExts, RewriteMode mode) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rewriteStream(in, out, targetExts, mode);
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
            CRC32 crc32 = new CRC32();
            crc32.update(payload);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc32.getValue());
        }
        zos.putNextEntry(entry);
        zos.write(payload);
        zos.closeEntry();
    }

    private static String renameIfMatch(String name, Set<String> targetExts, RewriteMode mode) {
        return switch (mode) {
            case FLATTEN -> maybeAppendTxt(name, targetExts);
            case UNFLATTEN -> maybeStripTxt(name, targetExts);
        };
    }

    private static String maybeAppendTxt(String name, Set<String> targetExts) {
        String fileName = Path.of(name).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return name + ".txt";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (targetExts.contains(ext)) {
            return name + ".txt";
        }
        return name;
    }

    private static String maybeStripTxt(String name, Set<String> targetExts) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt")) {
            return name;
        }
        String base = name.substring(0, name.length() - 4);
        String fileName = Path.of(base).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return base;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return targetExts.contains(ext) ? base : name;
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        input.transferTo(out);
        return out.toByteArray();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    private enum RewriteMode {
        FLATTEN,
        UNFLATTEN
    }
}
