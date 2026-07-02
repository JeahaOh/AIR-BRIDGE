package airbridge.common.fountain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Structural twin of {@link LtDecoder}: runs the same peeling process over neighbor sets only,
 * without holding any symbol bytes, to answer "would the block decode from the ESIs seen so
 * far?". Whether the peel completes depends only on which ESIs were offered ({@link
 * LtFountain#neighbors} derives each neighbor set from {@code esi} and {@code k} alone), so
 * this tracker and a real {@link LtDecoder} fed the same ESIs always agree. The capture
 * pipeline uses it to detect per-file completion live without buffering file data.
 *
 * <p>Not thread-safe; guard externally when shared across threads.
 */
public final class LtPeelTracker {

    private final int k;
    private final boolean[] recovered;
    private int recoveredCount;

    // Encoded symbols not yet reduced to a single unknown neighbor (their remaining-unknown
    // source indices). Mirrors LtDecoder's pending set, minus the symbol values; `waiters`
    // maps each unknown source index to its subscribed slots so propagation touches only
    // affected slots, exactly like LtDecoder.
    private final List<int[]> pendingNeighbors = new ArrayList<>();
    private final Map<Integer, List<Integer>> waiters = new HashMap<>();
    private final Set<Long> seenEsi = new HashSet<>();

    public LtPeelTracker(int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        this.k = k;
        this.recovered = new boolean[k];
    }

    public int k() {
        return k;
    }

    public boolean isComplete() {
        return recoveredCount == k;
    }

    /** Distinct symbols accepted so far (duplicates by ESI are ignored). */
    public int receivedCount() {
        return seenEsi.size();
    }

    /**
     * Offers one encoding symbol id. Duplicate ESIs and symbols that add no new information
     * are ignored. Returns {@code true} only when this call transitioned the block to complete.
     */
    public boolean offer(long esi) {
        if (isComplete()) {
            return false;
        }
        if (!seenEsi.add(esi)) {
            return false;
        }
        int[] nb = LtFountain.neighbors(esi, k);
        List<Integer> unknown = new ArrayList<>(nb.length);
        for (int idx : nb) {
            if (!recovered[idx]) {
                unknown.add(idx);
            }
        }
        if (unknown.isEmpty()) {
            return false; // fully redundant
        }
        if (unknown.size() == 1) {
            Queue<Integer> ripple = new ArrayDeque<>();
            recover(unknown.get(0), ripple);
            propagate(ripple);
        } else {
            int slot = pendingNeighbors.size();
            pendingNeighbors.add(toIntArray(unknown));
            for (int idx : unknown) {
                waiters.computeIfAbsent(idx, i -> new ArrayList<>()).add(slot);
            }
        }
        return isComplete();
    }

    // Marks one source symbol recoverable and enqueues it for cascade propagation.
    private void recover(int idx, Queue<Integer> ripple) {
        if (recovered[idx]) {
            return;
        }
        recovered[idx] = true;
        recoveredCount++;
        ripple.add(idx);
    }

    // Cascades newly recovered sources through the pending set, exactly like LtDecoder's
    // propagate but without XOR-ing values. Only slots subscribed to the recovered index are
    // visited; slots resolved earlier in the cascade show up as nulls and are skipped.
    private void propagate(Queue<Integer> ripple) {
        while (!ripple.isEmpty()) {
            int known = ripple.poll();
            List<Integer> slots = waiters.remove(known);
            if (slots == null) {
                continue;
            }
            for (int s : slots) {
                int[] nb = pendingNeighbors.get(s);
                if (nb == null) {
                    continue;
                }
                int pos = indexOf(nb, known);
                if (pos < 0) {
                    continue;
                }
                int[] reduced = removeAt(nb, pos);
                if (reduced.length == 1) {
                    pendingNeighbors.set(s, null);
                    recover(reduced[0], ripple);
                } else {
                    pendingNeighbors.set(s, reduced);
                }
            }
        }
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int[] removeAt(int[] arr, int pos) {
        int[] out = new int[arr.length - 1];
        int j = 0;
        for (int i = 0; i < arr.length; i++) {
            if (i != pos) {
                out[j++] = arr[i];
            }
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
