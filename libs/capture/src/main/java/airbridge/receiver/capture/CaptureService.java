package airbridge.receiver.capture;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CaptureService {
    private static final Pattern SAVED_IMAGE_PATTERN = Pattern.compile("^frame_(\\d+)\\.png$", Pattern.CASE_INSENSITIVE);

    private final CaptureOptions options;
    private final CaptureListener listener;
    private final ArrayBlockingQueue<FramePacket> rawFrameQueue = new ArrayBlockingQueue<>(CaptureDefaults.RAW_QUEUE_CAPACITY);
    private final LinkedBlockingQueue<SavePacket> saveQueue = new LinkedBlockingQueue<>(CaptureDefaults.SAVE_QUEUE_CAPACITY);
    private final ExecutorService fingerprintExecutor = Executors.newFixedThreadPool(CaptureDefaults.FINGERPRINT_WORKERS, r -> {
        Thread thread = new Thread(r, "qer-capture-fingerprint");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService saveExecutor = Executors.newFixedThreadPool(CaptureDefaults.SAVE_WORKERS, r -> {
        Thread thread = new Thread(r, "qer-capture-save-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService decodeExecutor;
    private final Semaphore decodePermits = new Semaphore(CaptureDefaults.MAX_PENDING_DECODE);
    private final Semaphore savePermits = new Semaphore(CaptureDefaults.MAX_PENDING_SAVE);
    private final Java2DFrameConverter frameConverter = new Java2DFrameConverter();
    private final ScheduledExecutorService mouseJiggleExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "qer-capture-mouse-jiggle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicLong frameSequence = new AtomicLong();
    private final AtomicLong totalFrames = new AtomicLong();
    private final AtomicLong analyzedFrames = new AtomicLong();
    private final AtomicLong decodedFrames = new AtomicLong();
    private final AtomicLong decodeFailures = new AtomicLong();
    private final AtomicLong blackFramesSkipped = new AtomicLong();
    private final AtomicLong fingerprintNanos = new AtomicLong();
    private final AtomicLong decodeNanos = new AtomicLong();
    private final AtomicLong saveNanos = new AtomicLong();
    private final AtomicLong rawQueueOfferRetries = new AtomicLong();
    private final AtomicLong rawQueueHighWaterMark = new AtomicLong();
    private final AtomicLong saveQueueHighWaterMark = new AtomicLong();
    private final AtomicLong lastPreviewAtMillis = new AtomicLong();
    private final AtomicInteger savedImageCounter = new AtomicInteger();
    private final Set<String> seenPayloads = Collections.synchronizedSet(new HashSet<>());

    private volatile String stopReason = "completed";

    public CaptureService(CaptureOptions options, CaptureListener listener) {
        this.options = options;
        this.listener = listener != null ? listener : new CaptureListener() {
        };
        this.decodeExecutor = new ThreadPoolExecutor(
                options.decodeWorkers(),
                options.decodeWorkers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(CaptureDefaults.MAX_PENDING_DECODE, options.decodeWorkers() * 2)),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public CaptureSummary run() throws Exception {
        Path outDir = options.outputDir();
        Path imagesDir = outDir.resolve("captured-images");
        Files.createDirectories(imagesDir);
        restoreResumeState(imagesDir);

        Instant startedAt = Instant.now();
        listener.onLog(String.format("[CAPTURE][INFO] source=uvc:index=%d fps=%.1f decodeWorkers=%d",
                options.deviceIndex(), options.fps(), options.decodeWorkers()));
        listener.onLog("[CAPTURE][INFO] out=" + outDir);
        listener.onLog("[CAPTURE][INFO] images=" + imagesDir);
        if (options.resume()) {
            listener.onLog(String.format("[CAPTURE][INFO] resume=true restoredUniquePayloads=%d nextImageIndex=%d",
                    seenPayloads.size(),
                    savedImageCounter.get() + 1));
        }
        mouseJiggleExecutor.scheduleAtFixedRate(CaptureService::nudgeMousePointer, 60, 60, TimeUnit.SECONDS);

        Thread analyzeThread = new Thread(this::analyzeLoop, "qe-capture-analyze");
        Thread saveThread = new Thread(() -> saveLoop(imagesDir), "qe-capture-save");
        analyzeThread.start();
        saveThread.start();

        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(options.deviceIndex())) {
            grabber.setImageWidth(options.width());
            grabber.setImageHeight(options.height());
            grabber.setFrameRate(options.fps());
            grabber.start();

            long startedMillis = System.currentTimeMillis();
            long lastStatusLogAt = startedMillis;

            while (!stopRequested.get()) {
                if (options.durationSeconds() > 0
                        && System.currentTimeMillis() - startedMillis >= options.durationSeconds() * 1000L) {
                    requestStop("duration-reached");
                    break;
                }

                Frame frame = grabber.grab();
                if (frame == null) {
                    continue;
                }

                BufferedImage image = frameConverter.getBufferedImage(frame);
                if (image == null) {
                    continue;
                }

                BufferedImage copy = copyImage(image);
                emitPreviewIfDue(copy);
                long frameId = frameSequence.incrementAndGet();
                totalFrames.incrementAndGet();
                FramePacket packet = new FramePacket(frameId, System.currentTimeMillis(), copy);
                while (!stopRequested.get() && !rawFrameQueue.offer(packet, 100, TimeUnit.MILLISECONDS)) {
                    rawQueueOfferRetries.incrementAndGet();
                }
                updateHighWaterMark(rawQueueHighWaterMark, rawFrameQueue.size());

                if (options.statusIntervalMs() > 0
                        && System.currentTimeMillis() - lastStatusLogAt >= options.statusIntervalMs()) {
                    lastStatusLogAt = System.currentTimeMillis();
                    logStatus();
                }
            }
        } finally {
            stopRequested.set(true);
            mouseJiggleExecutor.shutdownNow();
            rawFrameQueue.put(FramePacket.POISON);
            analyzeThread.join();
            fingerprintExecutor.shutdown();
            fingerprintExecutor.awaitTermination(5, TimeUnit.MINUTES);
            decodeExecutor.shutdown();
            decodeExecutor.awaitTermination(5, TimeUnit.MINUTES);
            saveQueue.put(SavePacket.POISON);
            saveThread.join();
            saveExecutor.shutdown();
            saveExecutor.awaitTermination(5, TimeUnit.MINUTES);
        }

        String finishedAt = Instant.now().toString();
        Path manifestPath = outDir.resolve("capture-manifest.json");
        Files.writeString(manifestPath, buildManifestJson(outDir, imagesDir, startedAt.toString(), finishedAt), StandardCharsets.UTF_8);

        CaptureSummary summary = new CaptureSummary(
                outDir,
                imagesDir,
                manifestPath,
                startedAt.toString(),
                finishedAt,
                stopReason,
                totalFrames.get(),
                analyzedFrames.get(),
                decodedFrames.get(),
                seenPayloads.size(),
                savedImageCounter.get(),
                blackFramesSkipped.get(),
                decodeFailures.get()
        );
        listener.onFinished(summary);
        return summary;
    }

    public void requestStop() {
        requestStop("requested");
    }

    private void analyzeLoop() {
        ScreenFingerprint activeFingerprint = null;
        long activeSinceMillis = 0L;
        ScreenFingerprint pendingFingerprint = null;
        FramePacket pendingPacket = null;
        int pendingCount = 0;
        long nextFrameId = 1L;
        boolean producerDone = false;
        Map<Long, Future<AnalyzedPacket>> pendingAnalysis = new TreeMap<>();

        try {
            while (!producerDone || !pendingAnalysis.isEmpty()) {
                while (!producerDone && pendingAnalysis.size() < CaptureDefaults.MAX_PENDING_FINGERPRINT) {
                    FramePacket packet = rawFrameQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (packet == null) {
                        break;
                    }
                    if (packet == FramePacket.POISON) {
                        producerDone = true;
                        break;
                    }
                    pendingAnalysis.put(packet.frameId, fingerprintExecutor.submit(() ->
                            analyzePacket(packet)));
                }

                Future<AnalyzedPacket> nextFuture = pendingAnalysis.get(nextFrameId);
                if (nextFuture == null) {
                    continue;
                }

                AnalyzedPacket analyzedPacket = nextFuture.get();
                pendingAnalysis.remove(nextFrameId);
                nextFrameId++;
                FramePacket packet = analyzedPacket.packet;
                ScreenFingerprint fingerprint = analyzedPacket.fingerprint;
                analyzedFrames.incrementAndGet();
                if (fingerprint.meanLuma <= CaptureDefaults.BLACK_FRAME_LUMA_THRESHOLD) {
                    blackFramesSkipped.incrementAndGet();
                    pendingFingerprint = null;
                    pendingPacket = null;
                    pendingCount = 0;
                    continue;
                }

                if (activeFingerprint != null
                        && hammingDistance(activeFingerprint.bits, fingerprint.bits) <= CaptureDefaults.SAME_SCREEN_DISTANCE_THRESHOLD) {
                    if (packet.capturedAtMillis - activeSinceMillis >= options.sameSignalSeconds() * 1000L) {
                        requestStop("same-signal");
                        return;
                    }
                    continue;
                }

                if (pendingFingerprint == null
                        || hammingDistance(pendingFingerprint.bits, fingerprint.bits) > CaptureDefaults.SAME_SCREEN_DISTANCE_THRESHOLD) {
                    pendingFingerprint = fingerprint;
                    pendingPacket = packet;
                    pendingCount = 1;
                    continue;
                }

                pendingCount++;
                pendingPacket = packet;
                if (pendingCount >= 2) {
                    activeFingerprint = pendingFingerprint;
                    activeSinceMillis = pendingPacket.capturedAtMillis;
                    submitDecode(pendingPacket);
                    pendingFingerprint = null;
                    pendingPacket = null;
                    pendingCount = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestStop("interrupted");
        } catch (Exception e) {
            requestStop("analyze-error");
            listener.onLog("[CAPTURE][ERROR] " + e.getMessage());
            throw new RuntimeException("캡처 분석 실패", e);
        }
    }

    private void submitDecode(FramePacket packet) throws InterruptedException {
        decodePermits.acquire();
        decodeExecutor.submit(() -> {
            long startedAt = System.nanoTime();
            try {
                String payload = CaptureQrDecodeSupport.decodeQrPayloadWithRetries(packet.image);
                decodedFrames.incrementAndGet();
                saveQueue.put(new SavePacket(packet.frameId, packet.capturedAtMillis, packet.image, payload));
                updateHighWaterMark(saveQueueHighWaterMark, saveQueue.size());
            } catch (Exception ignored) {
                decodeFailures.incrementAndGet();
            } finally {
                decodeNanos.addAndGet(System.nanoTime() - startedAt);
                decodePermits.release();
            }
        });
    }

    private void saveLoop(Path imagesDir) {
        try {
            while (true) {
                SavePacket packet = saveQueue.take();
                if (packet == SavePacket.POISON) {
                    return;
                }
                if (!seenPayloads.add(packet.payload)) {
                    continue;
                }
                int imageNumber = savedImageCounter.incrementAndGet();
                Path imagePath = imagesDir.resolve(String.format(Locale.ROOT, "frame_%06d.png", imageNumber));
                savePermits.acquire();
                saveExecutor.submit(() -> writeSavedImage(packet, imagePath, imageNumber));
                if (options.maxPayloads() > 0 && savedImageCounter.get() >= options.maxPayloads()) {
                    requestStop("max-payloads-reached");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestStop("interrupted");
        } catch (Exception e) {
            requestStop("save-error");
            listener.onLog("[CAPTURE][ERROR] " + e.getMessage());
            throw new RuntimeException("캡처 이미지 저장 실패", e);
        }
    }

    private void writeSavedImage(SavePacket packet, Path imagePath, int imageNumber) {
        long startedAt = System.nanoTime();
        try {
            writePngFast(packet.image, imagePath);
            listener.onSavedImage(imagePath, packet.payload, imageNumber);
        } catch (Exception e) {
            requestStop("save-error");
            listener.onLog("[CAPTURE][ERROR] " + e.getMessage());
            throw new RuntimeException("캡처 이미지 저장 실패", e);
        } finally {
            saveNanos.addAndGet(System.nanoTime() - startedAt);
            savePermits.release();
        }
    }

    private static void writePngFast(BufferedImage image, Path imagePath) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            ImageIO.write(image, "PNG", imagePath.toFile());
            return;
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(imagePath.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                if (param.getCompressionTypes() != null && param.getCompressionTypes().length > 0) {
                    param.setCompressionType(param.getCompressionTypes()[0]);
                }
                param.setCompressionQuality(0.0f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private void logStatus() {
        CaptureStatus status = new CaptureStatus(
                totalFrames.get(),
                analyzedFrames.get(),
                decodedFrames.get(),
                seenPayloads.size(),
                savedImageCounter.get(),
                blackFramesSkipped.get(),
                decodeFailures.get(),
                stopReason
        );
        listener.onStatus(status);
        listener.onLog(String.format("[CAPTURE][INFO] frames=%d analyzed=%d decoded=%d uniquePayloads=%d saved=%d",
                status.totalFrames(),
                status.analyzedFrames(),
                status.decodedFrames(),
                status.uniquePayloads(),
                status.savedImages()));
    }

    private void requestStop(String reason) {
        if (stopRequested.compareAndSet(false, true)) {
            stopReason = reason;
            listener.onLog("[CAPTURE][STOP] " + reason);
        }
    }

    private AnalyzedPacket analyzePacket(FramePacket packet) {
        long startedAt = System.nanoTime();
        try {
            return new AnalyzedPacket(packet, computeFingerprint(packet.image));
        } finally {
            fingerprintNanos.addAndGet(System.nanoTime() - startedAt);
        }
    }

    private void emitPreviewIfDue(BufferedImage source) {
        long now = System.currentTimeMillis();
        long lastPreview = lastPreviewAtMillis.get();
        if (now - lastPreview < CaptureDefaults.PREVIEW_FRAME_INTERVAL_MS) {
            return;
        }
        if (!lastPreviewAtMillis.compareAndSet(lastPreview, now)) {
            return;
        }
        listener.onPreviewFrame(buildPreviewImage(source));
    }

    private String buildManifestJson(Path outDir, Path imagesDir, String startedAt, String finishedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendJsonField(sb, "schemaVersion", "1", false, true);
        appendJsonField(sb, "command", "capture", true, true);
        appendJsonField(sb, "outputDir", outDir.toString(), true, true);
        appendJsonField(sb, "capturedImagesDir", imagesDir.toString(), true, true);
        appendJsonField(sb, "deviceIndex", String.valueOf(options.deviceIndex()), false, true);
        appendJsonField(sb, "width", String.valueOf(options.width()), false, true);
        appendJsonField(sb, "height", String.valueOf(options.height()), false, true);
        appendJsonField(sb, "fps", String.valueOf(options.fps()), false, true);
        appendJsonField(sb, "resume", String.valueOf(options.resume()), false, true);
        appendJsonField(sb, "startedAt", startedAt, true, true);
        appendJsonField(sb, "finishedAt", finishedAt, true, true);
        appendJsonField(sb, "stopReason", stopReason, true, true);
        appendJsonField(sb, "totalFrames", String.valueOf(totalFrames.get()), false, true);
        appendJsonField(sb, "analyzedFrames", String.valueOf(analyzedFrames.get()), false, true);
        appendJsonField(sb, "decodedFrames", String.valueOf(decodedFrames.get()), false, true);
        appendJsonField(sb, "uniquePayloads", String.valueOf(seenPayloads.size()), false, true);
        appendJsonField(sb, "savedImages", String.valueOf(savedImageCounter.get()), false, true);
        appendJsonField(sb, "blackFramesSkipped", String.valueOf(blackFramesSkipped.get()), false, true);
        appendJsonField(sb, "decodeFailures", String.valueOf(decodeFailures.get()), false, true);
        appendJsonField(sb, "rawQueueOfferRetries", String.valueOf(rawQueueOfferRetries.get()), false, true);
        appendJsonField(sb, "rawQueueHighWaterMark", String.valueOf(rawQueueHighWaterMark.get()), false, true);
        appendJsonField(sb, "saveQueueHighWaterMark", String.valueOf(saveQueueHighWaterMark.get()), false, true);
        appendJsonField(sb, "fingerprintMillis", formatMillis(fingerprintNanos.get()), false, true);
        appendJsonField(sb, "decodeMillis", formatMillis(decodeNanos.get()), false, true);
        appendJsonField(sb, "saveMillis", formatMillis(saveNanos.get()), false, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static void updateHighWaterMark(AtomicLong highWaterMark, int currentSize) {
        highWaterMark.accumulateAndGet(currentSize, Math::max);
    }

    private void restoreResumeState(Path imagesDir) throws Exception {
        if (!options.resume()) {
            return;
        }
        if (!Files.isDirectory(imagesDir)) {
            return;
        }

        listener.onLog("[CAPTURE][INFO] resume scan started");
        int maxImageNumber = 0;
        int restoredPayloads = 0;
        int scannedImages = 0;

        try (Stream<Path> files = Files.list(imagesDir)) {
            for (Path imagePath : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                scannedImages++;
                maxImageNumber = Math.max(maxImageNumber, extractSavedImageNumber(imagePath, scannedImages));
                try {
                    BufferedImage image = ImageIO.read(imagePath.toFile());
                    if (image == null) {
                        listener.onLog("[CAPTURE][WARN] resume skipped unreadable image: " + imagePath.getFileName());
                        continue;
                    }
                    String payload = CaptureQrDecodeSupport.decodeQrPayloadWithRetries(image);
                    if (seenPayloads.add(payload)) {
                        restoredPayloads++;
                    }
                } catch (Exception e) {
                    listener.onLog("[CAPTURE][WARN] resume skipped " + imagePath.getFileName() + ": " + e.getMessage());
                }
            }
        }

        savedImageCounter.set(maxImageNumber);
        listener.onLog(String.format("[CAPTURE][INFO] resume scan finished images=%d restoredPayloads=%d nextImageIndex=%d",
                scannedImages,
                restoredPayloads,
                savedImageCounter.get() + 1));
    }

    private static int extractSavedImageNumber(Path imagePath, int fallback) {
        String fileName = imagePath.getFileName().toString();
        Matcher matcher = SAVED_IMAGE_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000d);
    }

    private static BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static BufferedImage copyBuffered(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static BufferedImage buildPreviewImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(
                1.0d,
                Math.min(CaptureDefaults.PREVIEW_MAX_WIDTH / (double) Math.max(1, width),
                        CaptureDefaults.PREVIEW_MAX_HEIGHT / (double) Math.max(1, height))
        );
        if (scale >= 0.999d) {
            return copyBuffered(source);
        }
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage preview = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = preview.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        g.dispose();
        return preview;
    }

    private static void nudgeMousePointer() {
        try {
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            if (pointerInfo == null) {
                return;
            }
            Point point = pointerInfo.getLocation();
            Robot robot = new Robot();
            robot.mouseMove(point.x + 1, point.y);
            robot.mouseMove(point.x, point.y);
        } catch (Exception ignored) {
            // best-effort keep-awake helper
        }
    }

    private static ScreenFingerprint computeFingerprint(BufferedImage image) {
        int width = 33;
        int height = 32;
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();

        long[] bits = new long[16];
        long lumaSum = 0L;
        int bitIndex = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width - 1; x++) {
                int left = scaled.getRaster().getSample(x, y, 0);
                int right = scaled.getRaster().getSample(x + 1, y, 0);
                int longIndex = bitIndex / 64;
                int offset = bitIndex % 64;
                if (left > right) {
                    bits[longIndex] |= (1L << offset);
                }
                lumaSum += left;
                bitIndex++;
            }
            lumaSum += scaled.getRaster().getSample(width - 1, y, 0);
        }

        int meanLuma = (int) (lumaSum / (width * height));
        return new ScreenFingerprint(bits, meanLuma);
    }

    private static int hammingDistance(long[] a, long[] b) {
        int distance = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            distance += Long.bitCount(a[i] ^ b[i]);
        }
        return distance;
    }

    private static void appendJsonField(StringBuilder sb, String name, String value, boolean quote, boolean comma) {
        sb.append("  \"").append(escapeJson(name)).append("\": ");
        if (quote) {
            sb.append("\"").append(escapeJson(value == null ? "" : value)).append("\"");
        } else {
            sb.append(value);
        }
        if (comma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private record FramePacket(long frameId, long capturedAtMillis, BufferedImage image) {
        private static final FramePacket POISON = new FramePacket(-1, -1, null);
    }

    private record SavePacket(long frameId, long capturedAtMillis, BufferedImage image, String payload) {
        private static final SavePacket POISON = new SavePacket(-1, -1, null, null);
    }

    private record AnalyzedPacket(FramePacket packet, ScreenFingerprint fingerprint) {
    }

    private record ScreenFingerprint(long[] bits, int meanLuma) {
    }
}
