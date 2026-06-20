package airbridge.common.fountain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Peeling (belief-propagation) decoder for the systematic {@link LtFountain} scheme. Symbols
 * are offered in any order; the block is recoverable once {@link #isComplete()} returns true,
 * which typically needs slightly more than {@code k} distinct symbols when source losses must
 * be repaired (a clean systematic capture needs exactly {@code k}).
 *
 * <p>Not thread-safe; one decoder instance per in-flight file block.
 */
public final class LtDecoder {

    private final int k;
    private final int symbolSize;
    private final byte[][] source;     // recovered source symbols; null until known
    private int recoveredCount;

    // Encoded symbols not yet reduced to a single unknown neighbor. Parallel lists: the
    // remaining-unknown source indices and the symbol's current (partially reduced) value.
    private final List<int[]> pendingNeighbors = new ArrayList<>();
    private final List<byte[]> pendingValues = new ArrayList<>();
    private final Set<Long> seenEsi = new HashSet<>();

    public LtDecoder(int k, int symbolSize) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        if (symbolSize < 1) {
            throw new IllegalArgumentException("symbolSize must be >= 1");
        }
        this.k = k;
        this.symbolSize = symbolSize;
        this.source = new byte[k][];
    }

    public int k() {
        return k;
    }

    public int symbolSize() {
        return symbolSize;
    }

    public boolean isComplete() {
        return recoveredCount == k;
    }

    /** Distinct symbols accepted so far (duplicates by ESI are ignored). */
    public int receivedCount() {
        return seenEsi.size();
    }

    /**
     * Offers one encoding symbol. Duplicate ESIs and symbols that add no new information are
     * ignored. {@code data} must be exactly {@code symbolSize} bytes.
     */
    public void offer(long esi, byte[] data) {
        if (isComplete()) {
            return;
        }
        if (data.length != symbolSize) {
            throw new IllegalArgumentException(
                    "symbol size mismatch: expected " + symbolSize + " got " + data.length);
        }
        if (!seenEsi.add(esi)) {
            return;
        }
        int[] nb = LtFountain.neighbors(esi, k);
        byte[] value = data.clone();
        // Reduce against already-recovered sources.
        List<Integer> unknown = new ArrayList<>(nb.length);
        for (int idx : nb) {
            if (source[idx] != null) {
                LtFountain.xorInto(value, source[idx]);
            } else {
                unknown.add(idx);
            }
        }
        if (unknown.isEmpty()) {
            return; // fully redundant
        }
        if (unknown.size() == 1) {
            Queue<Integer> ripple = new ArrayDeque<>();
            recover(unknown.get(0), value, ripple);
            propagate(ripple);
        } else {
            pendingNeighbors.add(toIntArray(unknown));
            pendingValues.add(value);
        }
    }

    // Recovers one source symbol and enqueues it for cascade propagation.
    private void recover(int idx, byte[] value, Queue<Integer> ripple) {
        if (source[idx] != null) {
            return;
        }
        source[idx] = value;
        recoveredCount++;
        ripple.add(idx);
    }

    // Cascades newly recovered sources through the pending set: drop each recovered index from
    // any pending symbol's unknown set (XOR its value out); any symbol that drops to one unknown
    // recovers that source and feeds the ripple.
    private void propagate(Queue<Integer> ripple) {
        while (!ripple.isEmpty()) {
            int known = ripple.poll();
            byte[] knownValue = source[known];
            for (int s = 0; s < pendingNeighbors.size(); s++) {
                int[] nb = pendingNeighbors.get(s);
                if (nb == null) {
                    continue;
                }
                int pos = indexOf(nb, known);
                if (pos < 0) {
                    continue;
                }
                byte[] value = pendingValues.get(s);
                LtFountain.xorInto(value, knownValue);
                int[] reduced = removeAt(nb, pos);
                if (reduced.length == 1) {
                    pendingNeighbors.set(s, null);
                    pendingValues.set(s, null);
                    recover(reduced[0], value, ripple);
                } else {
                    pendingNeighbors.set(s, reduced);
                }
            }
        }
    }

    /**
     * Reassembles the {@code k} source symbols into the original block and trims trailing
     * padding to {@code originalLength}. Call only when {@link #isComplete()}.
     */
    public byte[] reassemble(int originalLength) {
        if (!isComplete()) {
            throw new IllegalStateException("block is not fully decoded");
        }
        if (originalLength < 0 || originalLength > (long) k * symbolSize) {
            throw new IllegalArgumentException("originalLength out of range: " + originalLength);
        }
        byte[] out = new byte[originalLength];
        int written = 0;
        for (int i = 0; i < k && written < originalLength; i++) {
            int n = Math.min(symbolSize, originalLength - written);
            System.arraycopy(source[i], 0, out, written, n);
            written += n;
        }
        return out;
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
