package airbridge.common.fountain;

import java.util.Random;

/**
 * Systematic LT (Luby Transform) fountain coding over a block of {@code k} equal-size source
 * symbols. Used to make the QR transfer resilient on a one-way camera channel: instead of
 * sequential indexed chunks that must all be captured (a dropped frame waits a full slideshow
 * loop), the encoder emits an open-ended stream of symbols and the receiver reconstructs the
 * block once it has collected enough of them, in any order.
 *
 * <p>Encoding symbol ids (ESI):
 * <ul>
 *   <li>{@code 0 .. k-1} are <b>systematic</b>: symbol {@code e} is source symbol {@code e}
 *       verbatim (neighbor set {@code {e}}). A clean capture with no losses therefore decodes
 *       trivially, with zero coding overhead.</li>
 *   <li>{@code e >= k} are <b>repair</b> symbols: the XOR of a pseudo-random subset of the
 *       source symbols, whose membership is derived deterministically from {@code e} and
 *       {@code k} alone — so the decoder reconstructs the same neighbor set without it being
 *       transmitted.</li>
 * </ul>
 *
 * <p><b>Cross-platform determinism.</b> The sender builds a repair symbol from
 * {@link #neighbors}, and the receiver rebuilds the same neighbor set from the same call; if
 * the two disagreed for one ESI, that symbol would corrupt decoding. Neighbor generation
 * therefore uses only {@link Random} (a spec-defined PRNG) and IEEE-754 division / {@link
 * Math#ceil} (both correctly rounded per the Java spec). It deliberately avoids
 * {@link Math#log}/{@link Math#sqrt}, which Java only bounds to ~1 ulp and may round
 * differently across JVMs — that rules out the Robust Soliton distribution here in favor of
 * the closed-form Ideal Soliton.
 */
public final class LtFountain {

    private LtFountain() {
    }

    /**
     * Source-symbol indices that encoding symbol {@code esi} is the XOR of, for a block of
     * {@code k} source symbols. For {@code esi < k} this is the singleton systematic set.
     */
    public static int[] neighbors(long esi, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1");
        }
        if (esi < 0) {
            throw new IllegalArgumentException("esi must be >= 0");
        }
        if (esi < k) {
            return new int[] {(int) esi};
        }
        Random rng = new Random(mixSeed(esi));
        int degree = sampleDegree(rng, k);
        return pickDistinct(rng, k, degree);
    }

    // splitmix64 finalizer: spreads sequential ESIs into well-separated PRNG seeds so that
    // adjacent repair symbols are not correlated. Deterministic 64-bit integer math.
    private static long mixSeed(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // Ideal Soliton degree, sampled by inverse CDF using only IEEE-deterministic ops.
    // rho(1)=1/k; rho(d)=1/(d(d-1)) for d>=2. For v ~ U(0,1], ceil(1/v) has P(=m)=1/(m(m-1)),
    // which is exactly the d>=2 tail; degree 1 is drawn with probability 1/k.
    private static int sampleDegree(Random rng, int k) {
        if (k == 1) {
            return 1;
        }
        double u = rng.nextDouble(); // [0,1), spec-defined
        if (u < 1.0 / k) {
            return 1;
        }
        // Map the remaining mass (1/k, 1) to v ~ (0,1] for the tail sampler.
        double v = (u - 1.0 / k) / (1.0 - 1.0 / k);
        if (v <= 0.0) {
            return 2;
        }
        long d = (long) Math.ceil(1.0 / v);
        if (d < 2) {
            return 2;
        }
        return (int) Math.min(d, (long) k);
    }

    // Picks `degree` distinct source indices in [0, k) using the spec-defined nextInt(bound).
    private static int[] pickDistinct(Random rng, int k, int degree) {
        int d = Math.min(degree, k);
        if (d == k) {
            int[] all = new int[k];
            for (int i = 0; i < k; i++) {
                all[i] = i;
            }
            return all;
        }
        boolean[] chosen = new boolean[k];
        int[] out = new int[d];
        int count = 0;
        while (count < d) {
            int idx = rng.nextInt(k);
            if (!chosen[idx]) {
                chosen[idx] = true;
                out[count++] = idx;
            }
        }
        return out;
    }

    /**
     * Builds the encoding symbol for {@code esi} by XOR-combining its source neighbors.
     * {@code source} must hold {@code k} symbols each exactly {@code symbolSize} bytes.
     */
    public static byte[] encodeSymbol(long esi, int k, byte[][] source) {
        int[] nb = neighbors(esi, k);
        byte[] out = source[nb[0]].clone();
        for (int i = 1; i < nb.length; i++) {
            xorInto(out, source[nb[i]]);
        }
        return out;
    }

    /** In-place {@code dst ^= src}; arrays must be the same length. */
    public static void xorInto(byte[] dst, byte[] src) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] ^= src[i];
        }
    }
}
