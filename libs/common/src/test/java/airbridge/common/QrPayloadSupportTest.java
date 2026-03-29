package airbridge.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrPayloadSupportTest {

    @Test
    void buildPayloadUsesHeaderSeparatorsAndShortHash() {
        String payload = QrPayloadSupport.buildPayload(
                "TESTPROJ",
                "docs/sample.txt",
                2,
                5,
                "0123456789abcdefdeadbeefcafebabe",
                "chunk-data"
        );

        String[] payloadParts = payload.split(QrPayloadSupport.HEADER_SEP, 3);
        String[] headerFields = payloadParts[1].split(QrPayloadSupport.FIELD_SEP, -1);

        assertEquals("HDR", payloadParts[0]);
        assertEquals("chunk-data", payloadParts[2]);
        assertEquals(5, headerFields.length);
        assertEquals("TESTPROJ", headerFields[0]);
        assertEquals("docs/sample.txt", headerFields[1]);
        assertEquals("2", headerFields[2]);
        assertEquals("5", headerFields[3]);
        assertEquals("0123456789abcdef", headerFields[4]);
        assertTrue(payload.startsWith("HDR" + QrPayloadSupport.HEADER_SEP));
    }
}
