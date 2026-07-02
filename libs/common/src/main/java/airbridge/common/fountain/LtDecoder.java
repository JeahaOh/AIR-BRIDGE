package airbridge.common.fountain;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    // Resolved slots stay as nulls; `waiters` maps each unknown source index to the slots
    // whose neighbor set still contains it, so propagation touches only affected slots
    // (total peel work is proportional to the sum of symbol degrees, not recoveries x slots).
    private final List<int[]> pendingNeighbors = new ArrayList<>();
    private final List<byte[]> pendingValues = new ArrayList<>();
    private final Map<Integer, List<Integer>> waiters = new HashMap<>();
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
            int slot = pendingNeighbors.size();
            pendingNeighbors.add(toIntArray(unknown));
            pendingValues.add(value);
            for (int idx : unknown) {
                waiters.computeIfAbsent(idx, i -> new ArrayList<>()).add(slot);
            }
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
    // recovers that source and feeds the ripple. Only the slots subscribed to the recovered
    // index are visited; a slot resolved earlier in the cascade shows up as a null and is skipped.
    private void propagate(Queue<Integer> ripple) {
        while (!ripple.isEmpty()) {
            int known = ripple.poll();
            byte[] knownValue = source[known];
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
        checkOriginalLength(originalLength);
        byte[] out = new byte[originalLength];
        int written = 0;
        for (int i = 0; i < k && written < originalLength; i++) {
            int n = Math.min(symbolSize, originalLength - written);
            System.arraycopy(source[i], 0, out, written, n);
            written += n;
        }
        return out;
    }

    /**
     * Streams the reassembled block (trimmed to {@code originalLength}) directly from the
     * recovered source symbols, without materializing a second full copy of the block next to
     * the one this decoder already holds. Call only when {@link #isComplete()}; the stream is
     * only valid while this decoder is alive.
     */
    public InputStream reassembleStream(int originalLength) {
        if (!isComplete()) {
            throw new IllegalStateException("block is not fully decoded");
        }
        checkOriginalLength(originalLength);
        return new InputStream() {
            private int symbolIndex;
            private int offsetInSymbol;
            private int remaining = originalLength;

            @Override
            public int read() {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                Objects.checkFromIndexSize(off, len, b.length);
                if (remaining <= 0) {
                    return -1;
                }
                if (len == 0) {
                    return 0;
                }
                int total = 0;
                while (len > 0 && remaining > 0) {
                    int n = Math.min(symbolSize - offsetInSymbol, Math.min(len, remaining));
                    System.arraycopy(source[symbolIndex], offsetInSymbol, b, off, n);
                    offsetInSymbol += n;
                    if (offsetInSymbol == symbolSize) {
                        symbolIndex++;
                        offsetInSymbol = 0;
                    }
                    off += n;
                    len -= n;
                    remaining -= n;
                    total += n;
                }
                return total;
            }

            @Override
            public int available() {
                return remaining;
            }
        };
    }

    private void checkOriginalLength(int originalLength) {
        if (originalLength < 0 || originalLength > (long) k * symbolSize) {
            throw new IllegalArgumentException("originalLength out of range: " + originalLength);
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
