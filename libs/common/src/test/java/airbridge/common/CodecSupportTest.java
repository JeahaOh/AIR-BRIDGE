package airbridge.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodecSupportTest {

    @TempDir
    Path tempDir;

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
    void compressAndEncodeToFileMatchesInMemoryAndRoundTrips() throws Exception {
        byte[] source = new byte[20_000];
        new Random(98765L).nextBytes(source);

        Path target = tempDir.resolve("encoded.b64");
        CodecSupport.EncodedStreamInfo info =
                CodecSupport.compressAndEncodeToFile(new ByteArrayInputStream(source), target);

        // Streaming must produce byte-identical output to the in-memory path so the QR
        // payload format is unchanged, and must report the raw size, encoded size, and hash.
        String streamed = Files.readString(target, StandardCharsets.US_ASCII);
        assertEquals(CodecSupport.compressAndEncode(source), streamed);
        assertEquals(source.length, info.rawByteCount());
        assertEquals(streamed.length(), info.encodedByteCount());
        assertEquals(CodecSupport.sha256Hex(source), info.sha256Hex());
        assertArrayEquals(source, CodecSupport.decodeAndDecompress(streamed));
    }

    @Test
    void streamingEncodeAndDecodeToFileRoundTrip() throws Exception {
        byte[] source = new byte[20_000];
        new Random(24680L).nextBytes(source);

        Path encoded = tempDir.resolve("rt.b64");
        CodecSupport.EncodedStreamInfo info =
                CodecSupport.compressAndEncodeToFile(new ByteArrayInputStream(source), encoded);

        Path restored = tempDir.resolve("rt.out");
        String restoredHash;
        try (var in = Files.newInputStream(encoded)) {
            restoredHash = CodecSupport.decodeDecompressToFile(in, restored);
        }

        assertArrayEquals(source, Files.readAllBytes(restored));
        assertEquals(info.sha256Hex(), restoredHash);
        assertEquals(CodecSupport.sha256Hex(source), restoredHash);
    }

    @Test
    void compressAndEncodeToFileHandlesEmptySource() throws Exception {
        Path target = tempDir.resolve("empty.b64");
        CodecSupport.EncodedStreamInfo info =
                CodecSupport.compressAndEncodeToFile(new ByteArrayInputStream(new byte[0]), target);

        String streamed = Files.readString(target, StandardCharsets.US_ASCII);
        assertEquals(CodecSupport.compressAndEncode(new byte[0]), streamed);
        assertEquals(0, info.rawByteCount());
        assertFalse(streamed.isBlank());
        assertArrayEquals(new byte[0], CodecSupport.decodeAndDecompress(streamed));
    }

    @Test
    void sha256HexMatchesKnownDigest() {
        assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                CodecSupport.sha256Hex("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
