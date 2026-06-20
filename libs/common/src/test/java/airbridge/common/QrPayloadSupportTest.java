package airbridge.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QrPayloadSupportTest {

    @Test
    void buildPayloadStartsWithMagicAndRoundTripsThroughParse() {
        byte[] symbol = new byte[] {0, 1, 2, (byte) 0xFF, (byte) 0x80, 'g', 'z'};
        byte[] payload = QrPayloadSupport.buildPayload(
                "docs/sample.txt",
                "0123456789abcdefdeadbeefcafebabe",
                5,        // k
                9000,     // gzipLen
                7,        // esi (a repair symbol)
                symbol
        );

        assertEquals(QrPayloadSupport.MAGIC_0, payload[0]);
        assertEquals(QrPayloadSupport.MAGIC_1, payload[1]);

        QrPayloadSupport.ParsedPayload parsed = QrPayloadSupport.parsePayload(payload);
        assertEquals("docs/sample.txt", parsed.relPath());
        // Only the first 16 hex chars (8 bytes) of the hash are carried.
        assertEquals("0123456789abcdef", parsed.hash16());
        assertEquals(5, parsed.k());
        assertEquals(9000, parsed.gzipLen());
        assertEquals(7, parsed.esi());
        assertArrayEquals(symbol, parsed.symbolData());
    }

    @Test
    void parsePayloadPreservesUtf8RelativePaths() {
        byte[] payload = QrPayloadSupport.buildPayload(
                "문서/샘플.txt", "00112233445566778899aabbccddeeff", 1, 1, 0, new byte[] {42});

        QrPayloadSupport.ParsedPayload parsed = QrPayloadSupport.parsePayload(payload);
        assertEquals("문서/샘플.txt", parsed.relPath());
        assertArrayEquals(new byte[] {42}, parsed.symbolData());
    }

    @Test
    void parsePayloadRejectsWrongMagic() {
        byte[] notAFrame = "HDR".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IllegalArgumentException.class, () -> QrPayloadSupport.parsePayload(notAFrame));
    }

    @Test
    void parsePayloadRejectsTruncatedFrame() {
        byte[] payload = QrPayloadSupport.buildPayload(
                "a.txt", "00112233445566778899aabbccddeeff", 1, 3, 0, new byte[] {1, 2, 3});
        byte[] truncated = new byte[payload.length - 5];
        System.arraycopy(payload, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> QrPayloadSupport.parsePayload(truncated));
    }
}
