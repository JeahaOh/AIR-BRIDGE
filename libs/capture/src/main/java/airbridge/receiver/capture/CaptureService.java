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
    // Margin applied to the observed per-symbol capture interval when recommending a slide
    // dwell. There is no back-channel to the sender, so the recommendation is a guideline the
    // operator applies by hand; fountain repair + slideshow looping absorb any mismatch.
    private static final double PACING_SAFETY_FACTOR = 1.3;

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
    // Dedupe by a 128-bit SHA-256 prefix of the payload instead of retaining every unique
    // payload string: long captures otherwise grow the heap by ~2KB per unique frame forever.
    private final Set<PayloadDigest> seenPayloads = Collections.synchronizedSet(new HashSet<>());
    private final CaptureCompletionTracker completionTracker = new CaptureCompletionTracker();
    private final BufferedImagePool imagePool;

    private static final ThreadLocal<BufferedImage> FINGERPRINT_BUFFER = ThreadLocal.withInitial(() ->
        new BufferedImage(33, 32, BufferedImage.TYPE_BYTE_GRAY)
    );

    private volatile String stopReason = "completed";
    private volatile long startedAtMillis;

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
        int poolCapacity = CaptureDefaults.RAW_QUEUE_CAPACITY
                + CaptureDefaults.SAVE_QUEUE_CAPACITY
                + CaptureDefaults.MAX_PENDING_DECODE
                + 10;
        this.imagePool = new BufferedImagePool(options.width(), options.height(), BufferedImage.TYPE_INT_RGB, poolCapacity);
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

            // Camera is open and we are about to start grabbing: signal "ready to receive".
            listener.onReady();

            long startedMillis = System.currentTimeMillis();
            startedAtMillis = startedMillis;
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

                BufferedImage copy = copyImage(image, imagePool);
                emitPreviewIfDue(copy);
                long frameId = frameSequence.incrementAndGet();
                totalFrames.incrementAndGet();
                FramePacket packet = new FramePacket(frameId, System.currentTimeMillis(), copy);
                boolean offered = false;
                while (!stopRequested.get() && !(offered = rawFrameQueue.offer(packet, 100, TimeUnit.MILLISECONDS))) {
                    rawQueueOfferRetries.incrementAndGet();
                }
                if (!offered) {
                    imagePool.release(copy);
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
            // A blocking put would hang forever if the consumer thread already died (its
            // queue never drains); poke with a timeout and stop once the thread is gone.
            offerPoisonWhileAlive(analyzeThread, () -> rawFrameQueue.offer(FramePacket.POISON, 100, TimeUnit.MILLISECONDS));
            analyzeThread.join();
            fingerprintExecutor.shutdown();
            fingerprintExecutor.awaitTermination(5, TimeUnit.MINUTES);
            decodeExecutor.shutdown();
            decodeExecutor.awaitTermination(5, TimeUnit.MINUTES);
            offerPoisonWhileAlive(saveThread, () -> saveQueue.offer(SavePacket.POISON, 100, TimeUnit.MILLISECONDS));
            saveThread.join();
            saveExecutor.shutdown();
            saveExecutor.awaitTermination(5, TimeUnit.MINUTES);

            // Clean up any remaining images in the raw queue
            FramePacket p;
            while ((p = rawFrameQueue.poll()) != null) {
                if (p != FramePacket.POISON && p.image != null) {
                    imagePool.release(p.image);
                }
            }
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
                decodeFailures.get(),
                completionTracker.observedFiles(),
                completionTracker.decodableFiles()
        );
        String recommendation = dwellRecommendationLog(seenPayloads.size(), elapsedMillis(), true);
        if (recommendation != null) {
            listener.onLog(recommendation);
        }
        listener.onLog(String.format(Locale.ROOT,
                "[CAPTURE][INFO] 복원 가능 파일 %d/%d (관측된 파일 기준; 최종 확정은 decode 결과)",
                summary.decodableFiles(), summary.observedFiles()));
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
                    imagePool.release(packet.image);
                    pendingFingerprint = null;
                    pendingPacket = null;
                    pendingCount = 0;
                    continue;
                }

                if (activeFingerprint != null
                        && hammingDistance(activeFingerprint.bits, fingerprint.bits) <= CaptureDefaults.SAME_SCREEN_DISTANCE_THRESHOLD) {
                    if (packet.capturedAtMillis - activeSinceMillis >= options.sameSignalSeconds() * 1000L) {
                        imagePool.release(packet.image);
                        requestStop("same-signal");
                        return;
                    }
                    imagePool.release(packet.image);
                    continue;
                }

                if (pendingFingerprint == null
                        || hammingDistance(pendingFingerprint.bits, fingerprint.bits) > CaptureDefaults.SAME_SCREEN_DISTANCE_THRESHOLD) {
                    if (pendingPacket != null) {
                        imagePool.release(pendingPacket.image);
                    }
                    pendingFingerprint = fingerprint;
                    pendingPacket = packet;
                    pendingCount = 1;
                    continue;
                }

                pendingCount++;
                if (pendingPacket != null) {
                    imagePool.release(pendingPacket.image);
                }
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
        } finally {
            if (pendingPacket != null) {
                imagePool.release(pendingPacket.image);
            }
        }
    }

    private void submitDecode(FramePacket packet) throws InterruptedException {
        decodePermits.acquire();
        decodeExecutor.submit(() -> {
            long startedAt = System.nanoTime();
            boolean submittedToSave = false;
            try {
                String payload = CaptureQrDecodeSupport.decodeQrPayloadWithRetries(packet.image);
                decodedFrames.incrementAndGet();
                saveQueue.put(new SavePacket(packet.frameId, packet.capturedAtMillis, packet.image, payload));
                updateHighWaterMark(saveQueueHighWaterMark, saveQueue.size());
                submittedToSave = true;
            } catch (Exception ignored) {
                decodeFailures.incrementAndGet();
            } finally {
                decodeNanos.addAndGet(System.nanoTime() - startedAt);
                decodePermits.release();
                if (!submittedToSave) {
                    imagePool.release(packet.image);
                }
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
                if (!seenPayloads.add(PayloadDigest.of(packet.payload))) {
                    imagePool.release(packet.image);
                    continue;
                }
                trackCompletion(packet.payload);
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
        } finally {
            // Clean up any remaining images in the save queue
            SavePacket p;
            while ((p = saveQueue.poll()) != null) {
                if (p != SavePacket.POISON && p.image != null) {
                    imagePool.release(p.image);
                }
            }
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
            imagePool.release(packet.image);
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
                // For the JDK PNG writer, LOWER quality means MORE deflate effort: 0.0 selects
                // the slowest maximum-compression level. 0.75 picks a fast level, which is what
                // this live save path needs; camera noise barely compresses better at level 9.
                param.setCompressionQuality(0.75f);
            }
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private void logStatus() {
        CaptureStatus status = buildStatus();
        listener.onStatus(status);
        listener.onLog(String.format(
                "[CAPTURE][INFO] frames=%d analyzed=%d decoded=%d uniquePayloads=%d saved=%d decodableFiles=%d/%d",
                status.totalFrames(),
                status.analyzedFrames(),
                status.decodedFrames(),
                status.uniquePayloads(),
                status.savedImages(),
                status.decodableFiles(),
                status.observedFiles()));
        // 매 상태 주기(기본 10초)마다 지금까지 관측된 속도로 다시 계산한 slide 권장값을 별도 줄로 출력한다.
        String recommendation = dwellRecommendationLog(status.uniquePayloads(), elapsedMillis(), false);
        if (recommendation != null) {
            listener.onLog(recommendation);
        }
    }

    private CaptureStatus buildStatus() {
        return new CaptureStatus(
                totalFrames.get(),
                analyzedFrames.get(),
                decodedFrames.get(),
                seenPayloads.size(),
                savedImageCounter.get(),
                blackFramesSkipped.get(),
                decodeFailures.get(),
                completionTracker.observedFiles(),
                completionTracker.decodableFiles(),
                stopReason
        );
    }

    // saveLoop(및 resume 스캔)에서 고유 페이로드마다 정확히 한 번 호출된다. 어떤 파일이 막
    // "복원 가능"해진 순간과, 관측된 파일 전부가 복원 가능해진 순간을 알린다. 송신측으로
    // 신호를 보낼 채널은 없으므로 slide 정지는 이 안내를 본 운영자 몫이다.
    private void trackCompletion(String payload) {
        CaptureCompletionTracker.Offer offer = completionTracker.offer(payload);
        if (offer.event() != CaptureCompletionTracker.Event.FILE_DECODABLE) {
            return;
        }
        listener.onLog(String.format(Locale.ROOT,
                "[CAPTURE][DONE] %s — 심볼 %d개 수집(k=%d), decode로 복원 가능",
                offer.relPath(), offer.received(), offer.k()));
        if (offer.decodableFiles() == offer.observedFiles()) {
            String rule = "[CAPTURE][DONE] " + "=".repeat(56);
            listener.onLog(rule);
            listener.onLog(String.format(Locale.ROOT,
                    "[CAPTURE][DONE] 관측된 파일 %d개 모두 복원 가능 — slide를 정지해도 됩니다"
                            + " (아직 한 번도 안 잡힌 파일이 있다면 계속 재생)",
                    offer.observedFiles()));
            listener.onLog(rule);
        }
        // 상태 주기를 기다리지 않고 상태(GUI 라벨 등)도 즉시 갱신한다.
        listener.onStatus(buildStatus());
    }

    private long elapsedMillis() {
        long started = startedAtMillis;
        return started > 0 ? System.currentTimeMillis() - started : 0;
    }

    // Recommended slide page-display-ms for the next pass: roughly the average interval between
    // capturing new unique symbols this run, plus a safety margin. Returns -1 when too little
    // was captured to advise. Conservative by construction (recommends slower, never faster).
    private static long recommendedDwellMs(long uniquePayloads, long elapsedMillis) {
        if (uniquePayloads < 2 || elapsedMillis <= 0) {
            return -1;
        }
        double msPerUnique = (double) elapsedMillis / uniquePayloads;
        return (long) Math.ceil(msPerUnique * PACING_SAFETY_FACTOR);
    }

    // Pacing guidance with an explicit adjustment direction. The receiver cannot see the
    // sender's current Page(ms), so the direction is phrased against the recommended floor and
    // the operator compares it with their own setting. Null when too little was captured.
    private String dwellRecommendationLog(long uniquePayloads, long elapsedMillis, boolean finalPass) {
        long recommended = recommendedDwellMs(uniquePayloads, elapsedMillis);
        if (recommended < 0) {
            return null;
        }
        double uniquePerSec = uniquePayloads * 1000.0 / Math.max(1, elapsedMillis);
        if (finalPass) {
            return String.format(Locale.ROOT,
                    "[CAPTURE][INFO] 이번 실행 고유 %.1f QR/s -> 다음 패스 slide page-display-ms >= %dms 권장 — "
                            + "지금 slide Page(ms)가 %d보다 작았다면 올리고, 컸다면 %d까지 내려도 됩니다 "
                            + "(fountain 복구+루프가 흡수하므로 가이드값)",
                    uniquePerSec, recommended, recommended, recommended);
        }
        return String.format(Locale.ROOT,
                "[CAPTURE][INFO] 고유 %.1f QR/s -> slide page-display-ms >= %dms 권장 — "
                        + "지금 slide Page(ms)가 %d보다 작으면 올리고, 크면 %d까지 내려도 됩니다 (가이드값)",
                uniquePerSec, recommended, recommended, recommended);
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
        appendJsonField(sb, "observedFiles", String.valueOf(completionTracker.observedFiles()), false, true);
        appendJsonField(sb, "decodableFiles", String.valueOf(completionTracker.decodableFiles()), false, true);
        appendJsonField(sb, "unparsedPayloads", String.valueOf(completionTracker.unparsedPayloads()), false, true);
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

    // Retries a poison-pill offer only while the consumer thread is alive; if the queue stays
    // full because the consumer died, shutdown proceeds instead of blocking forever.
    private static void offerPoisonWhileAlive(Thread consumer, PoisonOffer offer) throws InterruptedException {
        while (consumer.isAlive() && !offer.offer()) {
            // retry; the timeout inside offer() paces the loop
        }
    }

    @FunctionalInterface
    private interface PoisonOffer {
        boolean offer() throws InterruptedException;
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
                BufferedImage image = null;
                try {
                    image = ImageIO.read(imagePath.toFile());
                    if (image == null) {
                        listener.onLog("[CAPTURE][WARN] resume skipped unreadable image: " + imagePath.getFileName());
                        continue;
                    }
                    String payload = CaptureQrDecodeSupport.decodeQrPayloadWithRetries(image);
                    if (seenPayloads.add(PayloadDigest.of(payload))) {
                        restoredPayloads++;
                        trackCompletion(payload);
                    }
                } catch (Exception e) {
                    listener.onLog("[CAPTURE][WARN] resume skipped " + imagePath.getFileName() + ": " + e.getMessage());
                } finally {
                    if (image != null) {
                        image.flush();
                    }
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

    private static BufferedImage copyImage(BufferedImage source, BufferedImagePool pool) {
        BufferedImage copy = pool.acquire();
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
        BufferedImage scaled = FINGERPRINT_BUFFER.get();
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

    // 128-bit payload fingerprint for the dedupe set. A collision would silently drop one
    // frame, which the fountain absorbs like any other lost frame; at 2^-64-ish odds for
    // realistic session sizes that trade is safe.
    private record PayloadDigest(long hi, long lo) {
        static PayloadDigest of(String payload) {
            byte[] hash;
            try {
                hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.getBytes(StandardCharsets.ISO_8859_1));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 not available", e);
            }
            long hi = 0L;
            long lo = 0L;
            for (int i = 0; i < 8; i++) {
                hi = (hi << 8) | (hash[i] & 0xFF);
                lo = (lo << 8) | (hash[i + 8] & 0xFF);
            }
            return new PayloadDigest(hi, lo);
        }
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

    private static final class BufferedImagePool {
        private final int width;
        private final int height;
        private final int imageType;
        private final ArrayBlockingQueue<BufferedImage> pool;

        BufferedImagePool(int width, int height, int imageType, int capacity) {
            this.width = width;
            this.height = height;
            this.imageType = imageType;
            this.pool = new ArrayBlockingQueue<>(capacity);
        }

        BufferedImage acquire() {
            BufferedImage img = pool.poll();
            if (img == null) {
                img = new BufferedImage(width, height, imageType);
            }
            return img;
        }

        void release(BufferedImage img) {
            if (img == null) return;
            if (!pool.contains(img)) {
                pool.offer(img);
            }
        }
    }
}
