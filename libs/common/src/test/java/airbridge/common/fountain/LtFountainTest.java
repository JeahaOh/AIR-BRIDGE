package airbridge.common.fountain;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LtFountainTest {

    private static final int SYMBOL_SIZE = 64;

    @Test
    void neighborsAreSystematicForSourceEsis() {
        for (int e = 0; e < 10; e++) {
            assertArrayEquals(new int[] {e}, LtFountain.neighbors(e, 10),
                    "esi < k must map to its own source symbol");
        }
    }

    @Test
    void neighborsAreDeterministicAcrossCalls() {
        // The decoder rebuilds neighbor sets the sender used; repeated calls must be identical.
        for (long esi = 10; esi < 200; esi++) {
            assertArrayEquals(LtFountain.neighbors(esi, 37), LtFountain.neighbors(esi, 37));
        }
    }

    @Test
    void cleanSystematicCaptureDecodesWithExactlyKSymbols() {
        for (int k : new int[] {1, 2, 7, 64, 257}) {
            byte[] original = randomBytes(k * SYMBOL_SIZE - 13, 1234L + k); // force padding
            byte[][] source = splitIntoSymbols(original, k, SYMBOL_SIZE);

            LtDecoder decoder = new LtDecoder(k, SYMBOL_SIZE);
            for (int e = 0; e < k; e++) {
                decoder.offer(e, LtFountain.encodeSymbol(e, k, source));
            }
            assertTrue(decoder.isComplete(), "systematic prefix must decode at k symbols, k=" + k);
            assertEquals(k, decoder.receivedCount());
            assertArrayEquals(original, decoder.reassemble(original.length));
        }
    }

    @Test
    void duplicateAndRedundantSymbolsAreIgnored() {
        int k = 16;
        byte[] original = randomBytes(k * SYMBOL_SIZE, 99L);
        byte[][] source = splitIntoSymbols(original, k, SYMBOL_SIZE);

        LtDecoder decoder = new LtDecoder(k, SYMBOL_SIZE);
        decoder.offer(0, LtFountain.encodeSymbol(0, k, source));
        decoder.offer(0, LtFountain.encodeSymbol(0, k, source)); // duplicate esi
        assertEquals(1, decoder.receivedCount());
        assertFalse(decoder.isComplete());
    }

    @Test
    void recoversFromSourceLossesUsingRepairSymbols() {
        // Simulate the one-way looping channel: stream systematic + repair symbols, drop each
        // independently with probability p, loop until decoded. Assert decode succeeds well
        // within a generous symbol budget and reproduces the block exactly.
        double[] lossRates = {0.0, 0.1, 0.25, 0.4};
        for (int k : new int[] {1, 3, 20, 128, 400}) {
            for (double p : lossRates) {
                assertDecodesUnderLoss(k, p, 20250620L + k * 31L + (long) (p * 100));
            }
        }
    }

    private void assertDecodesUnderLoss(int k, double lossRate, long seed) {
        byte[] original = randomBytes(k * SYMBOL_SIZE - 7, seed);
        byte[][] source = splitIntoSymbols(original, k, SYMBOL_SIZE);

        Random channel = new Random(seed ^ 0xABCDEF);
        long esi = 0;
        int distinctReceived = 0;
        long cap = 50L * k + 200; // looping budget; comfortably above any realistic need
        LtDecoder decoder = new LtDecoder(k, SYMBOL_SIZE);

        for (long sent = 0; sent < cap && !decoder.isComplete(); sent++, esi++) {
            if (channel.nextDouble() < lossRate) {
                continue; // frame dropped by the camera channel
            }
            decoder.offer(esi, LtFountain.encodeSymbol(esi, k, source));
            distinctReceived++;
        }

        assertTrue(decoder.isComplete(),
                "failed to decode k=" + k + " loss=" + lossRate + " (received " + distinctReceived + ")");
        assertArrayEquals(original, decoder.reassemble(original.length),
                "decoded block mismatch k=" + k + " loss=" + lossRate);
    }

    // ---- helpers ----

    private static byte[][] splitIntoSymbols(byte[] data, int k, int symbolSize) {
        byte[][] symbols = new byte[k][];
        for (int i = 0; i < k; i++) {
            byte[] sym = new byte[symbolSize];
            int start = i * symbolSize;
            int n = Math.min(symbolSize, data.length - start);
            if (n > 0) {
                System.arraycopy(data, start, sym, 0, n);
            }
            symbols[i] = sym; // remaining bytes stay zero-padded
        }
        return symbols;
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] data = new byte[Math.max(1, size)];
        new Random(seed).nextBytes(data);
        return data;
    }
}
