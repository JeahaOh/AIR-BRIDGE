package airbridge.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binary framing for one QR chunk. The payload carries gzip bytes directly in QR 8-bit byte
 * mode (no Base64), so there is no text separator scheme. Layout (all integers big-endian):
 *
 * <pre>
 *   magic    : 2 bytes  {@code 'A','B'}
 *   project  : u8 length  + UTF-8 bytes
 *   relPath  : u16 length + UTF-8 bytes
 *   chunkIdx : u32
 *   total    : u32
 *   hash     : 8 bytes  (first 8 bytes / 16 hex chars of the file SHA-256)
 *   data     : remaining bytes (gzip payload window)
 * </pre>
 */
public final class QrPayloadSupport {
    public static final byte MAGIC_0 = 'A';
    public static final byte MAGIC_1 = 'B';
    /** Bytes of the file hash carried in the frame (the 16-hex-char short hash). */
    public static final int HASH_BYTES = 8;

    private QrPayloadSupport() {
    }

    /**
     * Builds the binary frame for one chunk. {@code fileHash} may be the full SHA-256 hex or the
     * 16-char short hash; only its first 16 hex chars (8 bytes) are carried.
     */
    public static byte[] buildPayload(String project, String relPath,
                                      int chunkIdx, int totalChunks,
                                      String fileHash, byte[] chunkData) {
        byte[] projectBytes = project.getBytes(StandardCharsets.UTF_8);
        byte[] relPathBytes = relPath.getBytes(StandardCharsets.UTF_8);
        if (projectBytes.length > 0xFF) {
            throw new IllegalArgumentException("project name too long: " + projectBytes.length + " bytes");
        }
        if (relPathBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("relative path too long: " + relPathBytes.length + " bytes");
        }
        byte[] hashBytes = hash16ToBytes(fileHash);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                2 + 1 + projectBytes.length + 2 + relPathBytes.length + 4 + 4 + HASH_BYTES + chunkData.length);
        out.write(MAGIC_0);
        out.write(MAGIC_1);
        out.write(projectBytes.length);
        out.writeBytes(projectBytes);
        writeU16(out, relPathBytes.length);
        out.writeBytes(relPathBytes);
        writeU32(out, chunkIdx);
        writeU32(out, totalChunks);
        out.writeBytes(hashBytes);
        out.writeBytes(chunkData);
        return out.toByteArray();
    }

    /** Parses a binary frame produced by {@link #buildPayload}. */
    public static ParsedPayload parsePayload(byte[] payload) {
        Reader r = new Reader(payload);
        if (r.remaining() < 2 || r.readByte() != MAGIC_0 || r.readByte() != MAGIC_1) {
            throw new IllegalArgumentException("지원하지 않는 페이로드 형식");
        }
        try {
            int projectLen = r.readU8();
            String project = r.readUtf8(projectLen);
            int relPathLen = r.readU16();
            String relPath = r.readUtf8(relPathLen);
            int chunkIdx = r.readU32();
            int totalChunks = r.readU32();
            String hash16 = bytesToHex(r.readBytes(HASH_BYTES));
            byte[] data = r.readRemaining();
            return new ParsedPayload(project, relPath, chunkIdx, totalChunks, hash16, data);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("페이로드가 잘렸거나 손상되었습니다", e);
        }
    }

    /** Decoded view of one chunk frame. {@code hash16} is the 16-char lowercase hex short hash. */
    public record ParsedPayload(String project, String relPath, int chunkIdx, int totalChunks,
                                String hash16, byte[] chunkData) {
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] hash16ToBytes(String fileHash) {
        if (fileHash.length() < HASH_BYTES * 2) {
            throw new IllegalArgumentException("file hash must have at least " + (HASH_BYTES * 2) + " hex chars");
        }
        byte[] bytes = new byte[HASH_BYTES];
        for (int i = 0; i < HASH_BYTES; i++) {
            int hi = Character.digit(fileHash.charAt(i * 2), 16);
            int lo = Character.digit(fileHash.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("file hash is not hex: " + fileHash);
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        int remaining() {
            return buf.length - pos;
        }

        byte readByte() {
            return buf[pos++];
        }

        int readU8() {
            return buf[pos++] & 0xFF;
        }

        int readU16() {
            return (readU8() << 8) | readU8();
        }

        int readU32() {
            return (readU8() << 24) | (readU8() << 16) | (readU8() << 8) | readU8();
        }

        byte[] readBytes(int len) {
            if (len < 0 || pos + len > buf.length) {
                throw new IndexOutOfBoundsException();
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        String readUtf8(int len) {
            return new String(readBytes(len), StandardCharsets.UTF_8);
        }

        byte[] readRemaining() {
            return readBytes(remaining());
        }
    }
}
