package airbridge.bench;

import airbridge.receiver.DecodeWorkflow;
import airbridge.sender.EncodeWorkflow;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Lightweight, dependency-free round-trip benchmark (no JMH). Generates a synthetic source
 * tree, runs encode then decode, and reports wall-clock time plus peak heap for each phase.
 * Peak heap is read from the JVM memory pool MX beans (java.lang.management), so it reflects
 * actual heap pressure rather than a sampled guess.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :receiver:benchmark \
 *     -Pbench.fileCount=20 -Pbench.fileSizeKb=2048 -Pbench.chunkSize=2000 \
 *     -Pbench.compressible=true -Pbench.decodeWorkers=4
 * </pre>
 * Parameters are passed as JVM system properties (see the Gradle task).
 */
public final class RoundTripBenchmark {

    private RoundTripBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int fileCount = intProp("bench.fileCount", 20);
        long fileSizeBytes = longProp("bench.fileSizeKb", 2048) * 1024L;
        int chunkSize = intProp("bench.chunkSize", 2000);
        boolean compressible = boolProp("bench.compressible", true);
        int decodeWorkers = intProp("bench.decodeWorkers", 4);
        long seed = longProp("bench.seed", 42L);

        Path work = Files.createTempDirectory("airbridge-bench-");
        Path src = work.resolve("src");
        Path qr = work.resolve("qr");
        Path restored = work.resolve("restored");
        Files.createDirectories(src);

        try {
            long totalBytes = generateSourceTree(src, fileCount, fileSizeBytes, compressible, seed);

            System.out.println("== air-bridge round-trip benchmark ==");
            System.out.printf(Locale.ROOT, "files=%d  fileSize=%s  totalSource=%s  chunkSize=%d  compressible=%b  decodeWorkers=%d%n",
                    fileCount, humanBytes(fileSizeBytes), humanBytes(totalBytes), chunkSize, compressible, decodeWorkers);
            System.out.printf(Locale.ROOT, "maxHeap=%s%n", humanBytes(Runtime.getRuntime().maxMemory()));
            System.out.println();

            EncodeWorkflow.Request request = new EncodeWorkflow.Request(
                    src, qr, null,
                    chunkSize, EncodeWorkflow.DEFAULT_QR_IMAGE_SIZE, EncodeWorkflow.DEFAULT_QR_ERROR_LEVEL,
                    EncodeWorkflow.DEFAULT_LABEL_HEIGHT, false, false,
                    EncodeWorkflow.DEFAULT_FOLDER_STRUCTURE, EncodeWorkflow.DEFAULT_FILES_PER_FOLDER,
                    EncodeWorkflow.DEFAULT_ENCODE_WORKERS, EncodeWorkflow.DEFAULT_REPAIR_OVERHEAD,
                    List.of("bin"), EncodeWorkflow.DEFAULT_SKIP_DIRS, List.of());

            long encodePeak;
            int qrCount;
            long encodeStart = System.nanoTime();
            try (HeapSampler sampler = new HeapSampler()) {
                EncodeWorkflow.Result encodeResult = new EncodeWorkflow().encode(request, line -> { });
                qrCount = encodeResult.totalQrCount();
                encodePeak = sampler.maxUsedBytes();
            }
            long encodeMs = msSince(encodeStart);

            long decodePeak;
            int restoredCount;
            long decodeStart = System.nanoTime();
            try (HeapSampler sampler = new HeapSampler()) {
                DecodeWorkflow.Result decodeResult = new DecodeWorkflow(decodeWorkers).decode(qr, restored, line -> { });
                restoredCount = decodeResult.restoredCount();
                decodePeak = sampler.maxUsedBytes();
            }
            long decodeMs = msSince(decodeStart);

            boolean ok = verify(src, restored);

            System.out.println();
            System.out.printf(Locale.ROOT, "ENCODE  : %,7d ms   qr=%d   peakHeap=%s   (%.1f MB/s source)%n",
                    encodeMs, qrCount, humanBytes(encodePeak), throughputMbPerSec(totalBytes, encodeMs));
            System.out.printf(Locale.ROOT, "DECODE  : %,7d ms   restored=%d   peakHeap=%s   (%.1f MB/s source)%n",
                    decodeMs, restoredCount, humanBytes(decodePeak), throughputMbPerSec(totalBytes, decodeMs));
            System.out.printf(Locale.ROOT, "ROUNDTRIP: %s%n", ok ? "OK (byte-identical)" : "FAILED (mismatch)");

            if (!ok || restoredCount != fileCount) {
                throw new IllegalStateException("benchmark round-trip verification failed: ok=" + ok
                        + " restored=" + restoredCount + " expected=" + fileCount);
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private static long generateSourceTree(Path src, int fileCount, long fileSizeBytes,
                                           boolean compressible, long seed) throws Exception {
        Random random = new Random(seed);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        for (int i = 0; i < fileCount; i++) {
            Path file = src.resolve(String.format(Locale.ROOT, "dir%02d/file_%04d.bin", i % 8, i));
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                long remaining = fileSizeBytes;
                while (remaining > 0) {
                    int n = (int) Math.min(buffer.length, remaining);
                    fill(buffer, n, compressible, random);
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            total += fileSizeBytes;
        }
        return total;
    }

    private static void fill(byte[] buffer, int n, boolean compressible, Random random) {
        if (compressible) {
            // Repetitive, text-like data so gzip actually compresses (closer to real documents).
            byte[] token = "the quick brown fox 0123456789 ".getBytes();
            for (int i = 0; i < n; i++) {
                buffer[i] = token[i % token.length];
            }
        } else {
            for (int i = 0; i < n; i++) {
                buffer[i] = (byte) random.nextInt(256);
            }
        }
    }

    private static boolean verify(Path src, Path restored) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(src)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path rel = src.relativize(file);
            Path target = restored.resolve(rel);
            if (!Files.exists(target) || Files.mismatch(file, target) != -1L) {
                System.out.println("  mismatch: " + rel);
                return false;
            }
        }
        return true;
    }

    /**
     * Samples the total heap-used figure on a background thread to capture the peak
     * <em>simultaneous</em> usage during a phase. Summing per-pool peak usages overcounts
     * (pool peaks happen at different times), so a sampled instantaneous reading is used
     * instead; it never exceeds the configured max heap.
     */
    private static final class HeapSampler implements AutoCloseable {
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final Thread thread;
        private volatile boolean running = true;
        private volatile long maxUsed;

        HeapSampler() {
            System.gc();
            this.maxUsed = memoryBean.getHeapMemoryUsage().getUsed();
            this.thread = new Thread(() -> {
                while (running) {
                    long used = memoryBean.getHeapMemoryUsage().getUsed();
                    if (used > maxUsed) {
                        maxUsed = used;
                    }
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "bench-heap-sampler");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        long maxUsedBytes() {
            // Final reading in case the peak landed between samples.
            long used = memoryBean.getHeapMemoryUsage().getUsed();
            if (used > maxUsed) {
                maxUsed = used;
            }
            return maxUsed;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
            try {
                thread.join(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static double throughputMbPerSec(long bytes, long millis) {
        if (millis <= 0) {
            return 0;
        }
        return (bytes / (1024.0 * 1024.0)) / (millis / 1000.0);
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return v != null ? Integer.parseInt(v.trim()) : def;
    }

    private static long longProp(String key, long def) {
        String v = System.getProperty(key);
        return v != null ? Long.parseLong(v.trim()) : def;
    }

    private static boolean boolProp(String key, boolean def) {
        String v = System.getProperty(key);
        return v != null ? Boolean.parseBoolean(v.trim()) : def;
    }

    private static void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
