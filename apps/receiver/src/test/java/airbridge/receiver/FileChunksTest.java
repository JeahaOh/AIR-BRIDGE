package airbridge.receiver;

import airbridge.common.fountain.LtFountain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChunksTest {

    private static final int SYMBOL_SIZE = 16;
    private static final String HASH = "0123456789abcdef";

    @Test
    void systematicSymbolsCompleteAndReassembleTheBlock() throws Exception {
        int k = 4;
        byte[] block = randomBytes(k * SYMBOL_SIZE - 5, 7L); // forces padding on the last symbol
        byte[][] source = splitIntoSymbols(block, k, SYMBOL_SIZE);

        FileChunks fileChunks = new FileChunks("docs/sample.bin", k, block.length, SYMBOL_SIZE, HASH);
        for (int esi = 0; esi < k; esi++) {
            fileChunks.addChunk(chunk(k, block.length, esi, LtFountain.encodeSymbol(esi, k, source)),
                    Path.of("qr-" + esi + ".png"));
        }

        assertTrue(fileChunks.isComplete());
        assertEquals(k, fileChunks.receivedCount());
        assertArrayEquals(block, fileChunks.encodedStream().readAllBytes());
        assertEquals(k, fileChunks.qrFiles().size());
    }

    @Test
    void duplicateEsiIsCountedOnce() {
        int k = 4;
        byte[] block = randomBytes(k * SYMBOL_SIZE, 9L);
        byte[][] source = splitIntoSymbols(block, k, SYMBOL_SIZE);

        FileChunks fileChunks = new FileChunks("docs/sample.bin", k, block.length, SYMBOL_SIZE, HASH);
        fileChunks.addChunk(chunk(k, block.length, 0, LtFountain.encodeSymbol(0, k, source)), Path.of("a.png"));
        fileChunks.addChunk(chunk(k, block.length, 0, LtFountain.encodeSymbol(0, k, source)), Path.of("b.png"));

        assertEquals(1, fileChunks.receivedCount());
        assertFalse(fileChunks.isComplete());
    }

    @Test
    void addChunkRejectsMismatchedMetadata() {
        FileChunks fileChunks = new FileChunks("docs/sample.bin", 2, 32, SYMBOL_SIZE, HASH);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fileChunks.addChunk(
                        chunk(2, 32, 0, new byte[SYMBOL_SIZE], "ffffffffffffffff"), // different hash16
                        Path.of("qr-1.png")
                )
        );

        assertEquals("동일 파일에 대한 메타데이터가 일치하지 않습니다: qr-1.png", error.getMessage());
    }

    private static QrDecodedChunk chunk(int k, int gzipLen, int esi, byte[] data) {
        return chunk(k, gzipLen, esi, data, HASH);
    }

    private static QrDecodedChunk chunk(int k, int gzipLen, int esi, byte[] data, String hash16) {
        return new QrDecodedChunk("docs/sample.bin", hash16, k, gzipLen, esi, data);
    }

    private static byte[][] splitIntoSymbols(byte[] data, int k, int symbolSize) {
        byte[][] symbols = new byte[k][];
        for (int i = 0; i < k; i++) {
            byte[] sym = new byte[symbolSize];
            int start = i * symbolSize;
            int n = Math.min(symbolSize, data.length - start);
            if (n > 0) {
                System.arraycopy(data, start, sym, 0, n);
            }
            symbols[i] = sym;
        }
        return symbols;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[Math.max(1, size)];
        new Random(seed).nextBytes(data);
        return data;
    }
}
