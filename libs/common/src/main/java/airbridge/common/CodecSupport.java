package airbridge.common;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GZIP compression helpers for the QR payload. The QR carries the gzip bytes directly in
 * 8-bit byte mode (no Base64), so these methods produce and consume raw compressed bytes.
 */
public final class CodecSupport {
    private CodecSupport() {
    }

    /** GZIP-compresses {@code data} with best compression and returns the raw compressed bytes. */
    public static byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos) {{
            def.setLevel(Deflater.BEST_COMPRESSION);
        }}) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Streams {@code source} through gzip (best compression) into {@code target}, using bounded
     * memory regardless of source size, while computing the SHA-256 of the raw bytes in the same
     * pass. The produced file holds the gzip bytes the QR payload carries directly.
     */
    public static CompressedStreamInfo compressToFile(InputStream source, Path target) throws IOException {
        MessageDigest digest = newSha256();
        long rawByteCount = 0;
        // The BufferedOutputStream coalesces the deflater's ~512-byte flushes into 64KB file
        // writes; without it every deflated block is its own syscall.
        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024);
             GZIPOutputStream gzos = new GZIPOutputStream(fileOut) {{
                 def.setLevel(Deflater.BEST_COMPRESSION);
             }}) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                gzos.write(buffer, 0, read);
                rawByteCount += read;
            }
        }
        return new CompressedStreamInfo(toHex(digest.digest()), rawByteCount, Files.size(target));
    }

    /** Result of {@link #compressToFile(InputStream, Path)}. */
    public record CompressedStreamInfo(String sha256Hex, long rawByteCount, long compressedByteCount) {
    }

    /**
     * Streams a gzip {@code source} into {@code target} (the restored file) using bounded memory,
     * returning the SHA-256 (hex) of the decompressed bytes computed in the same pass. The inverse
     * of {@link #compressToFile(InputStream, Path)}; callers verify the returned hash before
     * committing the file.
     */
    public static String decompressToFile(InputStream compressedSource, Path target) throws IOException {
        MessageDigest digest = newSha256();
        try (GZIPInputStream gzis = new GZIPInputStream(compressedSource);
             DigestInputStream digestIn = new DigestInputStream(gzis, digest);
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 64 * 1024)) {
            digestIn.transferTo(out);
        }
        return toHex(digest.digest());
    }

    /** GZIP-inflates {@code compressed} and returns the raw decompressed bytes. */
    public static byte[] decompress(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gzis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    public static String sha256Hex(byte[] data) {
        return toHex(newSha256().digest(data));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
