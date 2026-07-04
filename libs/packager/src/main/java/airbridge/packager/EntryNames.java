package airbridge.packager;

import java.util.Locale;

/**
 * String-only helpers for zip entry names. Entry names are '/'-separated and may contain
 * characters that are illegal in host filesystem paths (':' '*' '?' on Windows), so they
 * must never be routed through java.nio.file.Path.
 */
final class EntryNames {
    private EntryNames() {
    }

    static String lastSegment(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return slash < 0 ? entryName : entryName.substring(slash + 1);
    }

    static boolean isExtensionless(String entryName) {
        String fileName = lastSegment(entryName);
        if (fileName.isBlank()) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1;
    }

    /** Lowercased extension of the last segment, or "" when there is none. */
    static String extensionOf(String entryName) {
        String fileName = lastSegment(entryName);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Names with CR/LF cannot round-trip through the line-based target.txt rename list. */
    static boolean hasLineBreak(String entryName) {
        return entryName.indexOf('\n') >= 0 || entryName.indexOf('\r') >= 0;
    }

    /** Local-file-header magic PK\x03\x04 — the payload is a rewritable zip stream. */
    static boolean startsWithZipMagic(byte[] payload) {
        return payload.length >= 4
                && payload[0] == 0x50 && payload[1] == 0x4B
                && payload[2] == 0x03 && payload[3] == 0x04;
    }

    /** End-of-central-directory magic PK\x05\x06 — a valid but entry-less zip. */
    static boolean isEmptyZip(byte[] payload) {
        return payload.length >= 4
                && payload[0] == 0x50 && payload[1] == 0x4B
                && payload[2] == 0x05 && payload[3] == 0x06;
    }
}
