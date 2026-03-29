package airbridge.common;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodecSupportTest {

    @Test
    void compressAndEncodeRoundTripsBinaryData() throws Exception {
        byte[] source = new byte[1024];
        new Random(12345L).nextBytes(source);

        String encoded = CodecSupport.compressAndEncode(source);

        assertFalse(encoded.isBlank());
        assertArrayEquals(source, CodecSupport.decodeAndDecompress(encoded));
    }

    @Test
    void compressAndEncodeRoundTripsEmptyPayload() throws Exception {
        byte[] source = new byte[0];

        String encoded = CodecSupport.compressAndEncode(source);

        assertFalse(encoded.isBlank());
        assertArrayEquals(source, CodecSupport.decodeAndDecompress(encoded));
    }

    @Test
    void sha256HexMatchesKnownDigest() {
        assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                CodecSupport.sha256Hex("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
