package airbridge.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binary framing for one QR fountain symbol. The payload carries gzip bytes directly in QR
 * 8-bit byte mode (no Base64), so there is no text separator scheme. Each frame is one LT
 * fountain symbol of the file's gzip stream (see {@code airbridge.common.fountain}). Layout
 * (all integers big-endian):
 *
 * <pre>
 *   magic    : 2 bytes  {@code 'A','B'}
 *   relPath  : u16 length + UTF-8 bytes
 *   hash     : 8 bytes  (first 8 bytes / 16 hex chars of the file SHA-256)
 *   k        : u32  (number of source symbols the gzip stream was split into)
 *   gzipLen  : u32  (gzip stream length, for trimming the padded last source symbol)
 *   esi      : u32  (encoding symbol id; 0..k-1 = systematic source, >=k = repair)
 *   data     : remaining bytes (one symbol; symbolSize == data.length, constant per file)
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
     * Builds the binary frame for one fountain symbol. {@code fileHash} may be the full SHA-256
     * hex or the 16-char short hash; only its first 16 hex chars (8 bytes) are carried.
     */
    public static byte[] buildPayload(String relPath, String fileHash,
                                      int k, int gzipLen, int esi,
                                      byte[] symbolData) {
        byte[] relPathBytes = relPath.getBytes(StandardCharsets.UTF_8);
        if (relPathBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("relative path too long: " + relPathBytes.length + " bytes");
        }
        byte[] hashBytes = hash16ToBytes(fileHash);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                2 + 2 + relPathBytes.length + HASH_BYTES + 4 + 4 + 4 + symbolData.length);
        out.write(MAGIC_0);
        out.write(MAGIC_1);
        writeU16(out, relPathBytes.length);
        out.writeBytes(relPathBytes);
        out.writeBytes(hashBytes);
        writeU32(out, k);
        writeU32(out, gzipLen);
        writeU32(out, esi);
        out.writeBytes(symbolData);
        return out.toByteArray();
    }

    /** Parses a binary frame produced by {@link #buildPayload}. */
    public static ParsedPayload parsePayload(byte[] payload) {
        Reader r = new Reader(payload);
        if (r.remaining() < 2 || r.readByte() != MAGIC_0 || r.readByte() != MAGIC_1) {
            throw new IllegalArgumentException("지원하지 않는 페이로드 형식");
        }
        try {
            int relPathLen = r.readU16();
            String relPath = r.readUtf8(relPathLen);
            String hash16 = bytesToHex(r.readBytes(HASH_BYTES));
            int k = r.readU32();
            int gzipLen = r.readU32();
            int esi = r.readU32();
            byte[] data = r.readRemaining();
            return new ParsedPayload(relPath, hash16, k, gzipLen, esi, data);
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("페이로드가 잘렸거나 손상되었습니다", e);
        }
    }

    /** Decoded view of one fountain symbol frame. {@code hash16} is the 16-char lowercase hex short hash. */
    public record ParsedPayload(String relPath, String hash16, int k, int gzipLen, int esi,
                                byte[] symbolData) {
    }

    /**
     * Receiver-side sanity check (no frame-format change): rejects frame-shaped payloads whose
     * fields cannot come from the encoder. The sender always sets {@code k =
     * ceil(gzipLen/symbolSize)}, so {@code (k-1)*symbolSize < gzipLen <= k*symbolSize} must
     * hold; u32 fields read as negative ints (>= 2^31) fail these checks too. Without this, a
     * corrupt or foreign frame with a bogus huge {@code k} would drive pathological decoder
     * allocations instead of being counted as one bad frame.
     */
    public static void validateFrameFields(ParsedPayload payload) {
        int k = payload.k();
        int gzipLen = payload.gzipLen();
        int symbolSize = payload.symbolData().length;
        if (k < 1 || payload.esi() < 0 || gzipLen < 1 || symbolSize < 1
                || gzipLen > (long) k * symbolSize
                || (long) (k - 1) * symbolSize >= gzipLen) {
            throw new IllegalArgumentException("페이로드 필드가 유효하지 않습니다 (k=" + k
                    + ", gzipLen=" + gzipLen + ", esi=" + payload.esi()
                    + ", symbolSize=" + symbolSize + ")");
        }
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
