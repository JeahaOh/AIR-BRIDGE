package com.airbridge.pipeline;

import com.airbridge.Main;
import com.airbridge.core.Manifest;
import com.airbridge.core.ManifestBuilder;
import com.airbridge.core.Params;
import com.airbridge.ffmpeg.FfmpegRunner;
import com.airbridge.frame.FrameCodec;
import com.airbridge.frame.FramePacket;
import com.airbridge.util.Hashing;
import com.airbridge.util.Jsons;
import com.airbridge.util.ProgressPrinter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

public final class EncodePipeline {
    private static final int MANIFEST_HOLD_SECONDS = 5;
    private static final int END_SCREEN_SECONDS = 5;
    private final Path input;
    private final Path output;
    private final Path work;
    private final Params params;

    public EncodePipeline(Path input, Path output, Path work) {
        this(input, output, work, Params.default1080p());
    }

    public EncodePipeline(Path input, Path output, Path work, Params params) {
        this.input = input;
        this.output = output;
        this.work = work;
        this.params = params;
    }

    public void run() throws Exception {
        System.setProperty("java.awt.headless", "true");
        long startedAtNanos = System.nanoTime();

        List<String> warnings = new ArrayList<>();
        Path absInput = input.toAbsolutePath().normalize();
        Path absRequestedOutput = output.toAbsolutePath().normalize();
        Path absOutput = prepareOutputPath(absRequestedOutput, warnings);
        Path absWork = prepareWorkDir(absOutput, warnings);

        if (Files.exists(absOutput)) {
            if (Files.isDirectory(absOutput)) {
                throw new IOException("Output path is a directory: " + absOutput);
            }
            Files.delete(absOutput);
        }

        Path framesDir = absWork.resolve("frames");
        Path manifestPath = absWork.resolve("manifest.json");
        Path reportPath = absWork.resolve("encode_report.json");
        Files.createDirectories(framesDir);

        List<ManifestBuilder.ScannedFile> scannedFiles = ManifestBuilder.scanFiles(absInput, warning -> {
            warnings.add(warning);
            System.out.println(warning);
        });
        if (scannedFiles.isEmpty()) {
            throw new IOException("No input files found under: " + absInput);
        }

        Manifest manifest = buildManifest(scannedFiles, params.chunkSize());
        Jsons.write(manifestPath, manifest);

        FrameCodec codec = new FrameCodec(params);
        int maxPacketBytes = codec.maxPayloadBytes();
        int estimatedHeaderSize = FramePacket.estimateHeaderSizeBytes(manifest.sessionId);
        if (params.chunkSize() + estimatedHeaderSize > maxPacketBytes) {
            throw new IOException("Chunk size exceeds frame capacity. chunkSize=" + params.chunkSize()
                    + ", capacity=" + maxPacketBytes);
        }

        byte[] manifestBytes = Jsons.MAPPER.writeValueAsBytes(manifest);
        int metaPayloadLimit = maxPacketBytes - estimatedHeaderSize;
        if (metaPayloadLimit <= 0) {
            throw new IOException("Frame capacity too small for metadata header");
        }
        List<byte[]> manifestShards = split(manifestBytes, metaPayloadLimit);

        long manifestHoldFrames = Math.max(manifestShards.size(), (long) params.fps() * MANIFEST_HOLD_SECONDS);
        long endScreenFrames = (long) params.fps() * END_SCREEN_SECONDS;
        long totalFrames = manifestHoldFrames + manifest.totalChunks + endScreenFrames;
        FfmpegRunner ffmpeg = FfmpegRunner.create();
        printEncodeEstimate(manifest, manifestBytes.length, manifestHoldFrames, endScreenFrames, totalFrames);
        estimateEncodeOutput(ffmpeg, codec, manifest, manifestShards, scannedFiles, absWork, totalFrames, warnings)
                .ifPresent(this::printSampledEncodeEstimate);

        ProgressPrinter frameProgress = new ProgressPrinter("encode:frames");

        int frameNumber = 1;
        long generatedFrames = 0;

        for (int i = 0; i < manifestHoldFrames; i++) {
            int shardIndex = (int) (i % manifestShards.size());
            FramePacket packet = FramePacket.meta(
                    manifest.sessionId,
                    frameNumber,
                    shardIndex,
                    manifestShards.size(),
                    manifestShards.get(shardIndex)
            );
            writeFrame(codec, packet, framesDir.resolve(frameName(frameNumber)),
                    String.format("M | Manifest %d/%d", shardIndex + 1, manifestShards.size()));
            frameNumber++;
            generatedFrames++;
            frameProgress.update(generatedFrames, totalFrames);
        }

        byte[] chunkBuffer = new byte[params.chunkSize()];
        for (int fileOffset = 0; fileOffset < scannedFiles.size(); fileOffset++) {
            Manifest.FileEntry entry = manifest.files.get(fileOffset);
            ManifestBuilder.ScannedFile scanned = scannedFiles.get(fileOffset);

            if (entry.chunkCount > Integer.MAX_VALUE) {
                throw new IOException("File too large for current frame header index fields: " + entry.relativePath);
            }

            try (InputStream in = Files.newInputStream(scanned.absolutePath())) {
                for (int chunkIndex = 0; chunkIndex < entry.chunkCount; chunkIndex++) {
                    int read = readChunk(in, chunkBuffer);
                    if (read <= 0) {
                        throw new IOException("Unexpected EOF while reading file: " + scanned.absolutePath());
                    }

                    byte[] payload = Arrays.copyOf(chunkBuffer, read);
                    FramePacket packet = FramePacket.data(
                            manifest.sessionId,
                            frameNumber,
                            entry.index,
                            chunkIndex,
                            (int) entry.chunkCount,
                            payload
                    );

                    String overlay = String.format(
                            "D | File %d/%d | /%s | Chunk %d/%d",
                            entry.index,
                            manifest.totalFiles,
                            entry.relativePath,
                            chunkIndex + 1,
                            entry.chunkCount
                    );

                    writeFrame(codec, packet, framesDir.resolve(frameName(frameNumber)), overlay);
                    frameNumber++;
                    generatedFrames++;
                    frameProgress.update(generatedFrames, totalFrames);
                }
            }
        }

        for (int i = 0; i < endScreenFrames; i++) {
            writeEndFrame(params, framesDir.resolve(frameName(frameNumber)),
                    "TRANSFER COMPLETE", "You can stop recording now.");
            frameNumber++;
            generatedFrames++;
            frameProgress.update(generatedFrames, totalFrames);
        }

        ProgressPrinter muxProgress = new ProgressPrinter("encode:mux");
        muxProgress.update(0, 1);
        ffmpeg.encodePngSequence(framesDir.resolve("frame_%06d.png"), params.fps(), absOutput);
        muxProgress.update(1, 1);

        EncodeReport report = new EncodeReport();
        report.timestamp = Instant.now().toString();
        report.version = Main.VERSION;
        report.input = absInput.toString();
        report.requestedOutput = absRequestedOutput.toString();
        report.output = absOutput.toString();
        report.workdir = absWork.toString();
        report.manifest = manifestPath.toString();
        report.framesDir = framesDir.toString();
        report.ffmpegBinary = ffmpeg.binary();
        report.totalFiles = manifest.totalFiles;
        report.totalBytes = manifest.totalBytes;
        report.totalChunks = manifest.totalChunks;
        report.metaFrames = manifestHoldFrames;
        report.dataFrames = manifest.totalChunks;
        report.endFrames = endScreenFrames;
        report.totalFrames = totalFrames;
        report.frameWidth = params.width();
        report.frameHeight = params.height();
        report.overlayHeight = params.overlayHeight();
        report.cellSize = params.cellSize();
        report.fps = params.fps();
        report.chunkSize = params.chunkSize();
        report.elapsedMillis = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        report.outputBytes = Files.exists(absOutput) ? Files.size(absOutput) : -1L;
        report.warnings = warnings;

        Jsons.write(reportPath, report);

        String sizeText = report.outputBytes >= 0 ? humanBytes(report.outputBytes) : "unknown";
        System.out.printf("[encode:done] elapsed=%s, output=%s, size=%s, frames=%d%n",
                humanDurationMillis(report.elapsedMillis),
                absOutput,
                sizeText,
                totalFrames);
    }

    private Manifest buildManifest(List<ManifestBuilder.ScannedFile> scannedFiles, int chunkSize) throws IOException {
        Manifest manifest = new Manifest();
        manifest.sessionId = UUID.randomUUID().toString();
        manifest.chunkSize = chunkSize;

        long chunkCursor = 0;
        long totalBytes = 0;
        ProgressPrinter hashProgress = new ProgressPrinter("encode:hash");

        for (int i = 0; i < scannedFiles.size(); i++) {
            ManifestBuilder.ScannedFile scanned = scannedFiles.get(i);
            Manifest.FileEntry file = new Manifest.FileEntry();
            file.index = i + 1;
            file.relativePath = scanned.relativePath();
            file.size = Files.size(scanned.absolutePath());
            file.sha256 = Hashing.sha256Hex(scanned.absolutePath());
            file.startChunk = chunkCursor;
            file.chunkCount = divideRoundUp(file.size, chunkSize);

            manifest.files.add(file);

            chunkCursor += file.chunkCount;
            totalBytes += file.size;
            hashProgress.update(i + 1, scannedFiles.size());
        }

        manifest.totalFiles = manifest.files.size();
        manifest.totalBytes = totalBytes;
        manifest.totalChunks = chunkCursor;
        return manifest;
    }

    private static long divideRoundUp(long value, long divisor) {
        if (value == 0) {
            return 0;
        }
        return (value + divisor - 1) / divisor;
    }

    private static int readChunk(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
            if (offset == buffer.length) {
                break;
            }
            if (read == 0) {
                break;
            }
        }
        return offset;
    }

    private static List<byte[]> split(byte[] data, int chunkSize) {
        List<byte[]> parts = new ArrayList<>();
        if (data.length == 0) {
            parts.add(new byte[0]);
            return parts;
        }

        int cursor = 0;
        while (cursor < data.length) {
            int length = Math.min(chunkSize, data.length - cursor);
            parts.add(Arrays.copyOfRange(data, cursor, cursor + length));
            cursor += length;
        }
        return parts;
    }

    private static void writeFrame(FrameCodec codec, FramePacket packet, Path path, String overlayText) throws IOException {
        byte[] packetBytes = packet.toBytes();
        BufferedImage image = codec.encode(packetBytes, overlayText);
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static void writeEndFrame(Params params, Path path, String title, String subtitle) throws IOException {
        BufferedImage image = new BufferedImage(params.width(), params.height(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, params.width(), params.height());

            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 48));
            drawCenteredText(g, title, params.width(), params.height() / 2 - 20);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 28));
            drawCenteredText(g, subtitle, params.width(), params.height() / 2 + 30);
        } finally {
            g.dispose();
        }

        Files.createDirectories(path.getParent());
        ImageIO.write(image, "png", path.toFile());
    }

    private static void drawCenteredText(java.awt.Graphics2D g, String text, int width, int y) {
        if (text == null || text.isBlank()) {
            return;
        }
        java.awt.FontMetrics metrics = g.getFontMetrics();
        int x = (width - metrics.stringWidth(text)) / 2;
        g.drawString(text, Math.max(0, x), y);
    }

    private static String frameName(int frameNumber) {
        return String.format("frame_%06d.png", frameNumber);
    }

    private void printEncodeEstimate(
            Manifest manifest,
            int manifestBytes,
            long metaFrames,
            long endFrames,
            long totalFrames
    ) {
        double transferSeconds = totalFrames / (double) params.fps();
        System.out.printf("[encode:estimate] files=%d, input=%s, chunks=%d, frames=%d (meta=%d, data=%d, end=%d)%n",
                manifest.totalFiles,
                humanBytes(manifest.totalBytes),
                manifest.totalChunks,
                totalFrames,
                metaFrames,
                manifest.totalChunks,
                endFrames);
        System.out.printf("[encode:estimate] playback=%s @ %dfps, manifest=%s%n",
                humanDurationSeconds(transferSeconds),
                params.fps(),
                humanBytes(manifestBytes));
    }

    private void printSampledEncodeEstimate(EncodeEstimate estimate) {
        System.out.printf("[encode:estimate] output(mp4) ~= %s (range %s ~ %s, sample=%d frames)%n",
                humanBytes(estimate.estimatedBytes),
                humanBytes(estimate.estimatedBytesLower),
                humanBytes(estimate.estimatedBytesUpper),
                estimate.sampleFrames);
        System.out.printf("[encode:estimate] local runtime ~= %s (frame-gen %s + mux %s)%n",
                humanDurationMillis(estimate.estimatedTotalMillis),
                humanDurationMillis(estimate.estimatedGenerateMillis),
                humanDurationMillis(estimate.estimatedMuxMillis));
    }

    private Optional<EncodeEstimate> estimateEncodeOutput(
            FfmpegRunner ffmpeg,
            FrameCodec codec,
            Manifest manifest,
            List<byte[]> manifestShards,
            List<ManifestBuilder.ScannedFile> scannedFiles,
            Path absWork,
            long totalFrames,
            List<String> warnings
    ) {
        int sampleTargetFrames = 24;
        Path estimateDir = null;
        try {
            estimateDir = Files.createTempDirectory(absWork, "estimate-");
            Path sampleFramesDir = estimateDir.resolve("frames");
            Files.createDirectories(sampleFramesDir);

            int frameNumber = 1;
            int sampleFrames = 0;

            long genStartNanos = System.nanoTime();

            for (int shardIndex = 0; shardIndex < manifestShards.size() && sampleFrames < sampleTargetFrames; shardIndex++) {
                FramePacket packet = FramePacket.meta(
                        manifest.sessionId,
                        frameNumber,
                        shardIndex,
                        manifestShards.size(),
                        manifestShards.get(shardIndex)
                );
                writeFrame(codec, packet, sampleFramesDir.resolve(frameName(frameNumber)),
                        String.format("M | Manifest %d/%d", shardIndex + 1, manifestShards.size()));
                frameNumber++;
                sampleFrames++;
            }

            byte[] chunkBuffer = new byte[params.chunkSize()];
            outer:
            for (int fileOffset = 0; fileOffset < scannedFiles.size(); fileOffset++) {
                Manifest.FileEntry entry = manifest.files.get(fileOffset);
                ManifestBuilder.ScannedFile scanned = scannedFiles.get(fileOffset);

                if (entry.chunkCount > Integer.MAX_VALUE) {
                    continue;
                }

                try (InputStream in = Files.newInputStream(scanned.absolutePath())) {
                    for (int chunkIndex = 0; chunkIndex < entry.chunkCount; chunkIndex++) {
                        if (sampleFrames >= sampleTargetFrames) {
                            break outer;
                        }
                        int read = readChunk(in, chunkBuffer);
                        if (read <= 0) {
                            break;
                        }

                        byte[] payload = Arrays.copyOf(chunkBuffer, read);
                        FramePacket packet = FramePacket.data(
                                manifest.sessionId,
                                frameNumber,
                                entry.index,
                                chunkIndex,
                                (int) entry.chunkCount,
                                payload
                        );
                        writeFrame(codec, packet, sampleFramesDir.resolve(frameName(frameNumber)),
                                String.format("D | File %d/%d", entry.index, manifest.totalFiles));
                        frameNumber++;
                        sampleFrames++;
                    }
                }
            }

            long genElapsedNanos = System.nanoTime() - genStartNanos;
            if (sampleFrames <= 0) {
                return Optional.empty();
            }

            Path sampleMp4 = estimateDir.resolve("sample.mp4");
            long muxStartNanos = System.nanoTime();
            ffmpeg.encodePngSequence(sampleFramesDir.resolve("frame_%06d.png"), params.fps(), sampleMp4);
            long muxElapsedNanos = System.nanoTime() - muxStartNanos;

            long sampleMp4Bytes = Files.size(sampleMp4);
            double bytesPerFrame = sampleMp4Bytes / (double) sampleFrames;

            long estimatedBytes = Math.max(sampleMp4Bytes, Math.round(bytesPerFrame * totalFrames));
            long estimatedBytesLower = Math.max(0L, Math.round(estimatedBytes * 0.80));
            long estimatedBytesUpper = Math.max(estimatedBytesLower, Math.round(estimatedBytes * 1.20));

            double genMillisPerFrame = genElapsedNanos / 1_000_000.0 / sampleFrames;
            double muxMillisPerFrame = muxElapsedNanos / 1_000_000.0 / sampleFrames;
            long estimatedGenerateMillis = Math.max(0L, Math.round(genMillisPerFrame * totalFrames));
            long estimatedMuxMillis = Math.max(0L, Math.round(muxMillisPerFrame * totalFrames));

            return Optional.of(new EncodeEstimate(
                    sampleFrames,
                    estimatedBytes,
                    estimatedBytesLower,
                    estimatedBytesUpper,
                    estimatedGenerateMillis,
                    estimatedMuxMillis,
                    estimatedGenerateMillis + estimatedMuxMillis
            ));
        } catch (Exception e) {
            String warning = "WARN encode estimate skipped: " + e.getMessage();
            warnings.add(warning);
            System.out.println(warning);
            return Optional.empty();
        } finally {
            deleteRecursively(estimateDir);
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format("%.2f %s", value, units[unit]);
    }

    private static String humanDurationSeconds(double seconds) {
        if (seconds < 0) {
            return "0s";
        }
        long totalMillis = Math.round(seconds * 1000.0);
        return humanDurationMillis(totalMillis);
    }

    private static String humanDurationMillis(long millis) {
        if (millis < 60_000L) {
            return String.format("%.1fs", millis / 1000.0);
        }
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, seconds);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        }
        return String.format("%ds", seconds);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup for estimation temp files.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup for estimation temp files.
        }
    }

    private record EncodeEstimate(
            int sampleFrames,
            long estimatedBytes,
            long estimatedBytesLower,
            long estimatedBytesUpper,
            long estimatedGenerateMillis,
            long estimatedMuxMillis,
            long estimatedTotalMillis
    ) {
    }

    private Path resolveWorkDir(Path absOutput) {
        if (work != null) {
            return work.toAbsolutePath().normalize();
        }
        return defaultWorkDir(absOutput);
    }

    private Path prepareOutputPath(Path requestedOutput, List<String> warnings) throws IOException {
        String outputFileName = requestedOutput.getFileName() == null
                ? "result.mp4"
                : requestedOutput.getFileName().toString();
        Path cwdFallback = Path.of(System.getProperty("user.dir"), outputFileName).toAbsolutePath().normalize();
        Path tmpFallback = Path.of(System.getProperty("java.io.tmpdir"), "airbridge-output-" + UUID.randomUUID() + ".mp4")
                .toAbsolutePath().normalize();

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(requestedOutput);
        candidates.add(cwdFallback);
        candidates.add(tmpFallback);

        List<String> failures = new ArrayList<>();
        IOException lastError = null;

        for (Path candidate : candidates) {
            try {
                ensureParentWritable(candidate);
                if (!candidate.equals(requestedOutput)) {
                    String warning = "WARN unable to write requested --out path: " + requestedOutput
                            + ". Using fallback: " + candidate + ". Failures: " + String.join("; ", failures);
                    warnings.add(warning);
                    System.out.println(warning);
                }
                return candidate;
            } catch (IOException e) {
                lastError = e;
                failures.add(candidate + " (" + e.getMessage() + ")");
            }
        }

        IOException result = new IOException("Unable to prepare writable output path. Tried: " + String.join("; ", failures));
        if (lastError != null) {
            result.addSuppressed(lastError);
        }
        throw result;
    }

    private static void ensureParentWritable(Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent == null) {
            parent = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);

        Path probe = Files.createTempFile(parent, ".airbridge-write-check-", ".tmp");
        Files.deleteIfExists(probe);
    }

    private Path defaultWorkDir(Path absOutput) {
        Path parent = absOutput.getParent();
        if (parent == null || parent.equals(absOutput.getRoot())) {
            return Path.of(System.getProperty("user.dir"), "work").toAbsolutePath().normalize();
        }
        return parent.resolve("work").toAbsolutePath().normalize();
    }

    private Path prepareWorkDir(Path absOutput, List<String> warnings) throws IOException {
        Path requested = resolveWorkDir(absOutput);
        Path defaultDir = defaultWorkDir(absOutput);
        Path cwdFallback = Path.of(System.getProperty("user.dir"), "work").toAbsolutePath().normalize();
        Path tmpFallback = Path.of(System.getProperty("java.io.tmpdir"), "airbridge-work-" + UUID.randomUUID())
                .toAbsolutePath().normalize();

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(requested);
        candidates.add(defaultDir);
        candidates.add(cwdFallback);
        candidates.add(tmpFallback);

        List<String> failures = new ArrayList<>();
        IOException lastError = null;

        for (Path candidate : candidates) {
            try {
                if (Files.exists(candidate)) {
                    deleteRecursively(candidate);
                }
                Files.createDirectories(candidate);
                if (!candidate.equals(requested)) {
                    String warning = "WARN unable to create requested --work directory: " + requested
                            + ". Using fallback: " + candidate + ". Failures: " + String.join("; ", failures);
                    warnings.add(warning);
                    System.out.println(warning);
                }
                return candidate;
            } catch (IOException e) {
                lastError = e;
                failures.add(candidate + " (" + e.getMessage() + ")");
            }
        }

        IOException result = new IOException("Unable to create any work directory. Tried: " + String.join("; ", failures));
        if (lastError != null) {
            result.addSuppressed(lastError);
        }
        throw result;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    public static final class EncodeReport {
        public String timestamp;
        public String version;
        public String input;
        public String requestedOutput;
        public String output;
        public String workdir;
        public String manifest;
        public String framesDir;
        public String ffmpegBinary;
        public int totalFiles;
        public long totalBytes;
        public long totalChunks;
        public long metaFrames;
        public long dataFrames;
        public long endFrames;
        public long totalFrames;
        public int frameWidth;
        public int frameHeight;
        public int overlayHeight;
        public int cellSize;
        public int fps;
        public int chunkSize;
        public long elapsedMillis;
        public long outputBytes;
        public List<String> warnings;
    }
}
