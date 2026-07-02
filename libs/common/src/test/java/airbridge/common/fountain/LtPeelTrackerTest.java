package airbridge.common.fountain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LtPeelTrackerTest {

    private static final int SYMBOL_SIZE = 32;

    @Test
    void cleanSystematicCaptureCompletesAtExactlyKSymbols() {
        for (int k : new int[] {1, 2, 7, 64, 257}) {
            LtPeelTracker tracker = new LtPeelTracker(k);
            for (int e = 0; e < k; e++) {
                assertFalse(tracker.isComplete());
                boolean completedNow = tracker.offer(e);
                assertEquals(e == k - 1, completedNow, "k=" + k + " esi=" + e);
            }
            assertTrue(tracker.isComplete());
            assertEquals(k, tracker.receivedCount());
        }
    }

    @Test
    void duplicateEsisAreIgnored() {
        LtPeelTracker tracker = new LtPeelTracker(16);
        tracker.offer(0);
        assertFalse(tracker.offer(0));
        assertEquals(1, tracker.receivedCount());
        assertFalse(tracker.isComplete());
    }

    @Test
    void offerReportsCompletionTransitionOnlyOnce() {
        LtPeelTracker tracker = new LtPeelTracker(3);
        tracker.offer(0);
        tracker.offer(1);
        assertTrue(tracker.offer(2));
        assertFalse(tracker.offer(3), "offers after completion must not report a new transition");
        assertEquals(3, tracker.receivedCount(), "post-completion offers are not accepted");
    }

    // The tracker exists to predict, from ESIs alone, exactly what a real LtDecoder would do
    // with the symbol bytes. Feed both the same shuffled/lossy ESI streams and require the
    // completion state to match after every single offer.
    @Test
    void agreesWithLtDecoderUnderRandomLossAndReordering() {
        for (int k : new int[] {1, 3, 20, 128}) {
            for (double lossRate : new double[] {0.0, 0.1, 0.3, 0.5}) {
                long seed = 20260702L + k * 31L + (long) (lossRate * 100);
                assertAgreement(k, lossRate, seed);
            }
        }
    }

    private void assertAgreement(int k, double lossRate, long seed) {
        byte[][] source = randomSource(k, seed);
        Random channel = new Random(seed ^ 0x5EED);

        // Two slideshow loops' worth of symbols, independently dropped, then reordered.
        List<Long> received = new ArrayList<>();
        for (long esi = 0; esi < 4L * k + 40; esi++) {
            if (channel.nextDouble() >= lossRate) {
                received.add(esi % (2L * k + 20));
            }
        }
        Collections.shuffle(received, channel);

        LtDecoder decoder = new LtDecoder(k, SYMBOL_SIZE);
        LtPeelTracker tracker = new LtPeelTracker(k);
        for (long esi : received) {
            decoder.offer(esi, LtFountain.encodeSymbol(esi, k, source));
            tracker.offer(esi);
            assertEquals(decoder.isComplete(), tracker.isComplete(),
                    "divergence at esi=" + esi + " k=" + k + " loss=" + lossRate);
            assertEquals(decoder.receivedCount(), tracker.receivedCount(),
                    "receivedCount divergence at esi=" + esi + " k=" + k + " loss=" + lossRate);
        }
    }

    private static byte[][] randomSource(int k, long seed) {
        Random random = new Random(seed);
        byte[][] source = new byte[k][];
        for (int i = 0; i < k; i++) {
            source[i] = new byte[SYMBOL_SIZE];
            random.nextBytes(source[i]);
        }
        return source;
    }
}
