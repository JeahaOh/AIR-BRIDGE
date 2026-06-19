package airbridge.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class CodecSupport {
    private CodecSupport() {
    }

    public static String compressAndEncode(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos) {{
            def.setLevel(Deflater.BEST_COMPRESSION);
        }}) {
            gzos.write(data);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * Streams {@code source} through gzip (best compression) and base64 into {@code target},
     * using bounded memory regardless of source size, while computing the SHA-256 of the raw
     * bytes in the same pass. The produced file is byte-identical to
     * {@code compressAndEncode(allBytesOf(source))}, so the QR payload format is unchanged.
     */
    public static EncodedStreamInfo compressAndEncodeToFile(InputStream source, Path target) throws IOException {
        MessageDigest digest = newSha256();
        long rawByteCount = 0;
        try (OutputStream fileOut = Files.newOutputStream(target);
             OutputStream base64Out = Base64.getEncoder().wrap(fileOut);
             GZIPOutputStream gzos = new GZIPOutputStream(base64Out) {{
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
        return new EncodedStreamInfo(toHex(digest.digest()), rawByteCount, Files.size(target));
    }

    /** Result of {@link #compressAndEncodeToFile(InputStream, Path)}. */
    public record EncodedStreamInfo(String sha256Hex, long rawByteCount, long encodedByteCount) {
    }

    /**
     * Streams a base64+gzip {@code source} into {@code target} (the restored file) using
     * bounded memory, returning the SHA-256 (hex) of the decompressed bytes computed in the
     * same pass. The inverse of {@link #compressAndEncodeToFile(InputStream, java.nio.file.Path)};
     * callers verify the returned hash before committing the file.
     */
    public static String decodeDecompressToFile(InputStream base64Source, Path target) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream compressed = Base64.getDecoder().wrap(base64Source);
             GZIPInputStream gzis = new GZIPInputStream(compressed);
             DigestInputStream digestIn = new DigestInputStream(gzis, digest);
             OutputStream out = Files.newOutputStream(target)) {
            digestIn.transferTo(out);
        }
        return toHex(digest.digest());
    }

    public static byte[] decodeAndDecompress(String encoded) throws IOException {
        byte[] compressed = Base64.getDecoder().decode(encoded);
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
