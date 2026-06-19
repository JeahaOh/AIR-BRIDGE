package airbridge.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void compressRoundTripsBinaryData() throws Exception {
        byte[] source = new byte[1024];
        new Random(12345L).nextBytes(source);

        byte[] compressed = CodecSupport.compress(source);

        assertTrue(compressed.length > 0);
        assertArrayEquals(source, CodecSupport.decompress(compressed));
    }

    @Test
    void compressRoundTripsEmptyPayload() throws Exception {
        byte[] source = new byte[0];

        byte[] compressed = CodecSupport.compress(source);

        assertTrue(compressed.length > 0);
        assertArrayEquals(source, CodecSupport.decompress(compressed));
    }

    @Test
    void compressToFileMatchesInMemoryAndRoundTrips() throws Exception {
        byte[] source = new byte[20_000];
        new Random(98765L).nextBytes(source);

        Path target = tempDir.resolve("encoded.gz");
        CodecSupport.CompressedStreamInfo info =
                CodecSupport.compressToFile(new ByteArrayInputStream(source), target);

        // Streaming must produce byte-identical output to the in-memory path, and must report
        // the raw size, compressed size, and hash.
        byte[] streamed = Files.readAllBytes(target);
        assertArrayEquals(CodecSupport.compress(source), streamed);
        assertEquals(source.length, info.rawByteCount());
        assertEquals(streamed.length, info.compressedByteCount());
        assertEquals(CodecSupport.sha256Hex(source), info.sha256Hex());
        assertArrayEquals(source, CodecSupport.decompress(streamed));
    }

    @Test
    void streamingCompressAndDecompressToFileRoundTrip() throws Exception {
        byte[] source = new byte[20_000];
        new Random(24680L).nextBytes(source);

        Path encoded = tempDir.resolve("rt.gz");
        CodecSupport.CompressedStreamInfo info =
                CodecSupport.compressToFile(new ByteArrayInputStream(source), encoded);

        Path restored = tempDir.resolve("rt.out");
        String restoredHash;
        try (var in = Files.newInputStream(encoded)) {
            restoredHash = CodecSupport.decompressToFile(in, restored);
        }

        assertArrayEquals(source, Files.readAllBytes(restored));
        assertEquals(info.sha256Hex(), restoredHash);
        assertEquals(CodecSupport.sha256Hex(source), restoredHash);
    }

    @Test
    void compressToFileHandlesEmptySource() throws Exception {
        Path target = tempDir.resolve("empty.gz");
        CodecSupport.CompressedStreamInfo info =
                CodecSupport.compressToFile(new ByteArrayInputStream(new byte[0]), target);

        byte[] streamed = Files.readAllBytes(target);
        assertArrayEquals(CodecSupport.compress(new byte[0]), streamed);
        assertEquals(0, info.rawByteCount());
        assertTrue(streamed.length > 0);
        assertArrayEquals(new byte[0], CodecSupport.decompress(streamed));
    }

    @Test
    void sha256HexMatchesKnownDigest() {
        assertEquals(
                "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
                CodecSupport.sha256Hex("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
