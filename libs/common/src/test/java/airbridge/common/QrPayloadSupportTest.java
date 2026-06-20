package airbridge.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QrPayloadSupportTest {

    @Test
    void buildPayloadStartsWithMagicAndRoundTripsThroughParse() {
        byte[] chunkData = new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0x80, 'g', 'z'};
        byte[] payload = QrPayloadSupport.buildPayload(
                "docs/sample.txt",
                2,
                5,
                "0123456789abcdefdeadbeefcafebabe",
                chunkData
        );

        assertEquals(QrPayloadSupport.MAGIC_0, payload[0]);
        assertEquals(QrPayloadSupport.MAGIC_1, payload[1]);

        QrPayloadSupport.ParsedPayload parsed = QrPayloadSupport.parsePayload(payload);
        assertEquals("docs/sample.txt", parsed.relPath());
        assertEquals(2, parsed.chunkIdx());
        assertEquals(5, parsed.totalChunks());
        // Only the first 16 hex chars (8 bytes) of the hash are carried.
        assertEquals("0123456789abcdef", parsed.hash16());
        assertArrayEquals(chunkData, parsed.chunkData());
    }

    @Test
    void parsePayloadPreservesUtf8RelativePaths() {
        byte[] payload = QrPayloadSupport.buildPayload(
                "문서/샘플.txt", 1, 1, "00112233445566778899aabbccddeeff", new byte[]{42});

        QrPayloadSupport.ParsedPayload parsed = QrPayloadSupport.parsePayload(payload);
        assertEquals("문서/샘플.txt", parsed.relPath());
        assertArrayEquals(new byte[]{42}, parsed.chunkData());
    }

    @Test
    void parsePayloadRejectsWrongMagic() {
        byte[] notAFrame = "HDR".getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(IllegalArgumentException.class, () -> QrPayloadSupport.parsePayload(notAFrame));
    }

    @Test
    void parsePayloadRejectsTruncatedFrame() {
        byte[] payload = QrPayloadSupport.buildPayload(
                "a.txt", 1, 1, "00112233445566778899aabbccddeeff", new byte[]{1, 2, 3});
        byte[] truncated = new byte[payload.length - 5];
        System.arraycopy(payload, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class, () -> QrPayloadSupport.parsePayload(truncated));
    }
}
