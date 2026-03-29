package airbridge.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class CodecSupport {
    private CodecSupport() {
    }

    public static String compressAndEncode(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos) {{
            def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION);
        }}) {
            gzos.write(data);
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
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
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
