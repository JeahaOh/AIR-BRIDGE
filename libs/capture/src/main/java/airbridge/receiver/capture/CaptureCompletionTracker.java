package airbridge.receiver.capture;

import airbridge.common.QrPayloadSupport;
import airbridge.common.fountain.LtPeelTracker;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live per-file decodability tracking over the unique payloads captured so far. Each payload is
 * parsed as one fountain frame and grouped the same way decode groups symbols (relPath + hash16
 * + k + gzipLen + symbolSize), then its ESI feeds a structural {@link LtPeelTracker} — so "this
 * file is decodable" here means the later {@code decode} run over the saved PNGs will restore
 * it (barring gzip/hash-level corruption). There is no back-channel to the sender; the result
 * is surfaced to the operator, who decides when to stop the slideshow.
 *
 * <p>Only files whose frames were actually captured are visible: a file whose QRs never reached
 * the camera is not counted, so "all observed files decodable" is not proof the whole session
 * was sent. Payloads that are not air-bridge fountain frames (foreign QRs, corrupt reads) are
 * counted as unparsed and otherwise ignored.
 *
 * <p>Thread-safe: offers arrive on the save loop (and the resume scan) while status snapshots
 * are read from the grabber thread.
 */
final class CaptureCompletionTracker {

    /** Outcome of offering one unique payload. */
    enum Event {
        /** Not a valid fountain frame; counted as unparsed. */
        IGNORED,
        /** Frame accepted; its file is not decodable yet (or was already decodable). */
        PROGRESS,
        /** This frame just made its file decodable. */
        FILE_DECODABLE
    }

    /**
     * Result snapshot for one offer. {@code relPath}/{@code received}/{@code k} describe the
     * affected file (relPath is {@code null} for unparsed payloads); the two counts are the
     * tracker-wide totals after the offer.
     */
    record Offer(Event event, String relPath, int received, int k,
                 int decodableFiles, int observedFiles) {
    }

    private record FileKey(String relPath, String hash16, int k, int gzipLen, int symbolSize) {
    }

    private final Map<FileKey, LtPeelTracker> files = new LinkedHashMap<>();
    private int decodableCount;
    private long unparsedPayloads;

    synchronized Offer offer(String payload) {
        QrPayloadSupport.ParsedPayload parsed;
        try {
            parsed = QrPayloadSupport.parsePayload(payload.getBytes(StandardCharsets.ISO_8859_1));
        } catch (RuntimeException e) {
            unparsedPayloads++;
            return ignored();
        }
        FileKey key = new FileKey(parsed.relPath(), parsed.hash16(), parsed.k(), parsed.gzipLen(),
                parsed.symbolData().length);
        try {
            LtPeelTracker tracker = files.get(key);
            boolean firstSymbolOfFile = tracker == null;
            if (firstSymbolOfFile) {
                tracker = new LtPeelTracker(parsed.k());
            }
            boolean nowDecodable = tracker.offer(parsed.esi());
            if (firstSymbolOfFile) {
                // Register only after the first symbol is accepted, so a frame with impossible
                // fields cannot leave a phantom never-decodable file behind.
                files.put(key, tracker);
            }
            if (nowDecodable) {
                decodableCount++;
            }
            return new Offer(nowDecodable ? Event.FILE_DECODABLE : Event.PROGRESS,
                    parsed.relPath(), tracker.receivedCount(), parsed.k(),
                    decodableCount, files.size());
        } catch (RuntimeException e) {
            // Frame-shaped but with impossible fields (k < 1, negative esi, ...): decode would
            // reject it the same way, so count it as unparsed rather than crash the save loop.
            unparsedPayloads++;
            return ignored();
        }
    }

    private Offer ignored() {
        return new Offer(Event.IGNORED, null, 0, 0, decodableCount, files.size());
    }

    /** Distinct files seen so far (by decode's grouping identity). */
    synchronized int observedFiles() {
        return files.size();
    }

    /** Files whose captured symbols already suffice for decode to restore them. */
    synchronized int decodableFiles() {
        return decodableCount;
    }

    /** Payloads that were not valid fountain frames. */
    synchronized long unparsedPayloads() {
        return unparsedPayloads;
    }
}
