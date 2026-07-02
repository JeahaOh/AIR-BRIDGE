package airbridge.receiver.capture;

import airbridge.common.QrPayloadSupport;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureCompletionTrackerTest {

    private static final String HASH_A = "0123456789abcdef";
    private static final String HASH_B = "fedcba9876543210";
    private static final int SYMBOL_SIZE = 8;

    @Test
    void systematicSymbolsMakeFileDecodableAtExactlyK() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        int k = 4;
        for (int esi = 0; esi < k - 1; esi++) {
            CaptureCompletionTracker.Offer offer = tracker.offer(payload("a.txt", HASH_A, k, esi));
            assertEquals(CaptureCompletionTracker.Event.PROGRESS, offer.event(), "esi=" + esi);
        }
        CaptureCompletionTracker.Offer last = tracker.offer(payload("a.txt", HASH_A, k, k - 1));
        assertEquals(CaptureCompletionTracker.Event.FILE_DECODABLE, last.event());
        assertEquals("a.txt", last.relPath());
        assertEquals(k, last.received());
        assertEquals(1, last.decodableFiles());
        assertEquals(1, last.observedFiles());
    }

    @Test
    void repairSymbolsCompensateForLostSourceSymbols() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        int k = 8;
        // Sources 0..5 captured; 6 and 7 lost. Repair symbols must eventually complete the file.
        for (int esi = 0; esi < k - 2; esi++) {
            tracker.offer(payload("b.bin", HASH_A, k, esi));
        }
        boolean decodable = false;
        for (int esi = k; esi < 50 * k && !decodable; esi++) {
            decodable = tracker.offer(payload("b.bin", HASH_A, k, esi)).event()
                    == CaptureCompletionTracker.Event.FILE_DECODABLE;
        }
        assertTrue(decodable, "repair symbols never completed the file");
        assertEquals(1, tracker.decodableFiles());
    }

    @Test
    void conflictingMetadataForSameRelPathIsNotCountedLikeDecode() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        // decode groups by relPath and rejects chunks whose metadata conflicts with the
        // first-seen file; the tracker mirrors that instead of counting a phantom second file.
        tracker.offer(payload("same.txt", HASH_A, 2, 0));
        CaptureCompletionTracker.Offer conflicting = tracker.offer(payload("same.txt", HASH_B, 2, 0));
        assertEquals(CaptureCompletionTracker.Event.IGNORED, conflicting.event());
        assertEquals(1, tracker.observedFiles());
        assertEquals(0, tracker.decodableFiles());
        assertEquals(1, tracker.unparsedPayloads());
    }

    @Test
    void invalidRelativePathIsNotTracked() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        // decode rejects path-escaping relPaths per chunk; a file decode can never restore
        // must not show up as observed/decodable here.
        CaptureCompletionTracker.Offer offer = tracker.offer(payload("../escape.txt", HASH_A, 1, 0));
        assertEquals(CaptureCompletionTracker.Event.IGNORED, offer.event());
        assertEquals(0, tracker.observedFiles());
        assertEquals(1, tracker.unparsedPayloads());
    }

    @Test
    void allObservedFilesDecodableOnlyWhenEveryFileCompletes() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        CaptureCompletionTracker.Offer first = tracker.offer(payload("one.txt", HASH_A, 1, 0));
        assertEquals(CaptureCompletionTracker.Event.FILE_DECODABLE, first.event());
        assertEquals(1, first.decodableFiles());
        assertEquals(1, first.observedFiles());

        CaptureCompletionTracker.Offer second = tracker.offer(payload("two.txt", HASH_B, 2, 0));
        assertEquals(CaptureCompletionTracker.Event.PROGRESS, second.event());
        assertEquals(1, second.decodableFiles());
        assertEquals(2, second.observedFiles());

        CaptureCompletionTracker.Offer third = tracker.offer(payload("two.txt", HASH_B, 2, 1));
        assertEquals(CaptureCompletionTracker.Event.FILE_DECODABLE, third.event());
        assertEquals(2, third.decodableFiles());
        assertEquals(2, third.observedFiles());
    }

    @Test
    void nonFountainPayloadsAreCountedUnparsedAndIgnored() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        CaptureCompletionTracker.Offer offer = tracker.offer("payload-A");
        assertEquals(CaptureCompletionTracker.Event.IGNORED, offer.event());
        assertNull(offer.relPath());
        assertEquals(1, tracker.unparsedPayloads());
        assertEquals(0, tracker.observedFiles());
    }

    @Test
    void frameShapedPayloadWithImpossibleFieldsDoesNotRegisterAPhantomFile() {
        CaptureCompletionTracker tracker = new CaptureCompletionTracker();
        // k=0 is structurally parseable but impossible; decode would reject it the same way.
        CaptureCompletionTracker.Offer offer = tracker.offer(payload("ghost.txt", HASH_A, 0, 0));
        assertEquals(CaptureCompletionTracker.Event.IGNORED, offer.event());
        assertEquals(1, tracker.unparsedPayloads());
        assertEquals(0, tracker.observedFiles());
    }

    // Builds the ISO-8859-1 payload string capture dedupes on (1 char = 1 byte).
    private static String payload(String relPath, String hash16, int k, int esi) {
        byte[] frame = QrPayloadSupport.buildPayload(relPath, hash16, k, k * SYMBOL_SIZE, esi,
                new byte[SYMBOL_SIZE]);
        return new String(frame, StandardCharsets.ISO_8859_1);
    }
}
