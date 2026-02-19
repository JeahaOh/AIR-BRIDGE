package com.airbridge.pipeline;

import com.airbridge.core.Manifest;
import com.airbridge.core.Params;
import com.airbridge.ffmpeg.FfmpegRunner;
import com.airbridge.frame.FrameCodec;
import com.airbridge.frame.FramePacket;
import com.airbridge.util.Hashing;
import com.airbridge.util.Jsons;
import com.airbridge.util.ProgressPrinter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

public final class DecodePipeline {
    private final Path input;
    private final Path output;
    private final Path work;
    private final Path report;

    public DecodePipeline(Path input, Path output, Path work, Path report) {
        this.input = input;
        this.output = output;
        this.work = work;
        this.report = report;
    }

    public void run() throws Exception {
        System.setProperty("java.awt.headless", "true");
        long startedAtNanos = System.nanoTime();

        List<String> warnings = new ArrayList<>();
        Path absInput = input.toAbsolutePath().normalize();
        Path absOutput = output.toAbsolutePath().normalize();
        Path absWork = prepareWorkDir(absOutput, warnings);
        Path absReport = resolveReportPath(absWork);

        Files.createDirectories(absOutput);

        DecodeReport decodeReport = new DecodeReport();
        decodeReport.timestamp = Instant.now().toString();
        decodeReport.input = absInput.toString();
        decodeReport.output = absOutput.toString();
        decodeReport.workdir = absWork.toString();

        Path extractedFramesDir = absWork.resolve("decoded_frames");
        Files.createDirectories(extractedFramesDir);

        FfmpegRunner ffmpeg = FfmpegRunner.create();
        decodeReport.ffmpegBinary = ffmpeg.binary();
        decodeReport.inputVideoBytes = Files.size(absInput);

        try {
            ffmpeg.probeInputVideo(absInput).ifPresent(video -> {
                decodeReport.estimatedVideoDurationSeconds = video.durationSeconds();
                decodeReport.estimatedVideoFps = video.fps();
                decodeReport.estimatedVideoWidth = video.width();
                decodeReport.estimatedVideoHeight = video.height();
                if (video.durationSeconds() != null && video.fps() != null) {
                    decodeReport.estimatedTransferFrames = Math.max(0L,
                            Math.round(video.durationSeconds() * video.fps()));
                }
            });
        } catch (Exception e) {
            warnings.add("WARN decode estimate probe failed: " + e.getMessage());
        }
        printDecodeInitialEstimate(decodeReport);

        ProgressPrinter extractProgress = new ProgressPrinter("decode:extract");
        extractProgress.update(0, 1);
        ffmpeg.extractPngSequence(absInput, extractedFramesDir.resolve("frame_%06d.png"));
        extractProgress.update(1, 1);

        List<Path> frames = listPngFrames(extractedFramesDir);
        if (frames.isEmpty()) {
            throw new IOException("No PNG frames extracted from: " + absInput);
        }
        decodeReport.totalExtractedFrames = frames.size();

        BufferedImage firstFrame = ImageIO.read(frames.get(0).toFile());
        if (firstFrame == null) {
            throw new IOException("Unable to read first extracted frame: " + frames.get(0));
        }

        Params params = paramsForFrame(firstFrame);
        FrameCodec codec = new FrameCodec(params);

        Map<String, SessionCollector> sessions = new HashMap<>();
        ProgressPrinter parseProgress = new ProgressPrinter("decode:parse");

        long validFrames = 0;
        long invalidFrames = 0;

        for (int i = 0; i < frames.size(); i++) {
            Path framePath = frames.get(i);
            BufferedImage image = ImageIO.read(framePath.toFile());
            if (image == null) {
                invalidFrames++;
                parseProgress.update(i + 1L, frames.size());
                continue;
            }

            var decodedPacketBytes = codec.decode(image);
            if (decodedPacketBytes.isEmpty()) {
                invalidFrames++;
                parseProgress.update(i + 1L, frames.size());
                continue;
            }

            var parsedPacket = FramePacket.fromBytes(decodedPacketBytes.get());
            if (parsedPacket.isEmpty()) {
                invalidFrames++;
                parseProgress.update(i + 1L, frames.size());
                continue;
            }

            FramePacket packet = parsedPacket.get();
            SessionCollector collector = sessions.computeIfAbsent(packet.sessionId(), key -> new SessionCollector());
            collector.accept(packet);
            validFrames++;
            parseProgress.update(i + 1L, frames.size());
        }

        decodeReport.validFrames = validFrames;
        decodeReport.invalidFrames = invalidFrames;

        SessionSelection selection = chooseSession(sessions);
        decodeReport.sessionId = selection.sessionId;

        Manifest manifest = recoverManifest(selection.collector);
        decodeReport.totalFiles = manifest.totalFiles;
        decodeReport.totalBytes = manifest.totalBytes;
        decodeReport.expectedChunks = manifest.totalChunks;
        System.out.printf("[decode:estimate] expected restore: files=%d, output=%s, chunks=%d%n",
                manifest.totalFiles,
                humanBytes(manifest.totalBytes),
                manifest.totalChunks);

        long invalidDataPackets = 0;
        Map<Long, byte[]> chunkMap = new HashMap<>();
        for (FramePacket packet : selection.collector.dataPackets) {
            if (packet.fileIndex() <= 0 || packet.fileIndex() > manifest.files.size()) {
                invalidDataPackets++;
                continue;
            }

            Manifest.FileEntry entry = manifest.files.get(packet.fileIndex() - 1);
            if (packet.fileChunkIndex() < 0 || packet.fileChunkIndex() >= entry.chunkCount) {
                invalidDataPackets++;
                continue;
            }

            if (packet.payload().length > manifest.chunkSize) {
                invalidDataPackets++;
                continue;
            }

            long globalChunkIndex = entry.startChunk + packet.fileChunkIndex();
            chunkMap.putIfAbsent(globalChunkIndex, packet.payload());
        }

        decodeReport.invalidDataPackets = invalidDataPackets;
        decodeReport.receivedChunks = chunkMap.size();

        List<RecoveredFile> recoveredFiles = new ArrayList<>();
        ProgressPrinter reassemblyProgress = new ProgressPrinter("decode:reassembly");

        for (int fileOffset = 0; fileOffset < manifest.files.size(); fileOffset++) {
            Manifest.FileEntry entry = manifest.files.get(fileOffset);
            String safeRelative = sanitizeRelativePath(entry.relativePath, entry.index, warnings);
            Path targetPath = resolveOutputPath(absOutput, safeRelative, entry.index, warnings);
            Files.createDirectories(Objects.requireNonNullElse(targetPath.getParent(), absOutput));

            long missingChunks = writeFile(entry, targetPath, chunkMap, manifest.chunkSize);
            recoveredFiles.add(new RecoveredFile(entry, safeRelative, targetPath, missingChunks));
            reassemblyProgress.update(fileOffset + 1L, manifest.files.size());
        }

        ProgressPrinter verifyProgress = new ProgressPrinter("decode:verify");
        List<FileReport> fileReports = new ArrayList<>();
        long missingChunksTotal = 0;
        int hashMismatches = 0;
        long recoveredBytes = 0;

        for (int i = 0; i < recoveredFiles.size(); i++) {
            RecoveredFile recovered = recoveredFiles.get(i);
            Manifest.FileEntry entry = recovered.entry;
            String actualSha = Hashing.sha256Hex(recovered.path);
            recoveredBytes += Files.size(recovered.path);

            String status;
            if (recovered.missingChunks > 0) {
                status = "MISSING_CHUNKS (" + recovered.missingChunks + " missing)";
                missingChunksTotal += recovered.missingChunks;
            } else if (!actualSha.equalsIgnoreCase(entry.sha256)) {
                status = "HASH_MISMATCH";
                hashMismatches++;
            } else {
                status = "OK";
            }

            System.out.printf("File %02d/%d %s   %s%n",
                    i + 1,
                    recoveredFiles.size(),
                    recovered.safeRelative,
                    status);

            FileReport reportItem = new FileReport();
            reportItem.path = recovered.safeRelative;
            reportItem.expectedSha256 = entry.sha256;
            reportItem.actualSha256 = actualSha;
            reportItem.missingChunks = recovered.missingChunks;
            reportItem.status = status;
            fileReports.add(reportItem);

            verifyProgress.update(i + 1L, recoveredFiles.size());
        }

        decodeReport.missingChunks = missingChunksTotal;
        decodeReport.hashMismatches = hashMismatches;
        decodeReport.ok = missingChunksTotal == 0 && hashMismatches == 0;
        decodeReport.recoveredBytes = recoveredBytes;
        decodeReport.elapsedMillis = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
        decodeReport.files = fileReports;
        decodeReport.warnings = warnings;

        Jsons.write(absReport, decodeReport);

        System.out.printf("[decode:done] elapsed=%s, output=%s, recovered=%s, files=%d, ok=%s%n",
                humanDurationMillis(decodeReport.elapsedMillis),
                absOutput,
                humanBytes(decodeReport.recoveredBytes),
                decodeReport.totalFiles,
                decodeReport.ok);
    }

    private static SessionSelection chooseSession(Map<String, SessionCollector> sessions) throws IOException {
        return sessions.entrySet().stream()
                .filter(entry -> entry.getValue().metaShardCount > 0)
                .max(Comparator
                        .<Map.Entry<String, SessionCollector>>comparingDouble(entry -> entry.getValue().metaCoverage())
                        .thenComparingInt(entry -> entry.getValue().metaShards.size())
                        .thenComparingInt(entry -> entry.getValue().dataPackets.size()))
                .map(entry -> new SessionSelection(entry.getKey(), entry.getValue()))
                .orElseThrow(() -> new IOException("Unable to locate valid session metadata in extracted frames"));
    }

    private static Manifest recoverManifest(SessionCollector collector) throws IOException {
        if (collector.metaShardCount <= 0) {
            throw new IOException("Missing metadata shard count");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < collector.metaShardCount; i++) {
            byte[] shard = collector.metaShards.get(i);
            if (shard == null) {
                throw new IOException("Missing metadata shard " + i + " / " + collector.metaShardCount);
            }
            baos.write(shard);
        }

        Manifest manifest = Jsons.MAPPER.readValue(baos.toByteArray(), Manifest.class);
        if (manifest.files == null) {
            manifest.files = List.of();
        }
        return manifest;
    }

    private static long writeFile(Manifest.FileEntry entry, Path targetPath, Map<Long, byte[]> chunkMap, int chunkSize)
            throws IOException {
        long remaining = entry.size;
        long missingChunks = 0;

        try (OutputStream out = Files.newOutputStream(targetPath)) {
            for (long i = 0; i < entry.chunkCount; i++) {
                long globalChunkIndex = entry.startChunk + i;
                int expectedLength = (int) Math.min(chunkSize, remaining);
                byte[] payload = chunkMap.get(globalChunkIndex);

                if (payload == null) {
                    out.write(new byte[expectedLength]);
                    missingChunks++;
                } else {
                    int writeLength = Math.min(expectedLength, payload.length);
                    out.write(payload, 0, writeLength);
                    if (writeLength < expectedLength) {
                        out.write(new byte[expectedLength - writeLength]);
                        missingChunks++;
                    }
                }

                remaining -= expectedLength;
                if (remaining <= 0) {
                    break;
                }
            }
        }

        return missingChunks;
    }

    private static String sanitizeRelativePath(String raw, int fileIndex, List<String> warnings) {
        String normalized = raw == null ? "" : raw.replace('\\', '/');
        String[] segments = normalized.split("/");
        List<String> safeSegments = new ArrayList<>();

        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                warnings.add("WARN suspicious path segment ignored in decode: " + raw);
                continue;
            }
            String cleaned = segment.replace(':', '_');
            safeSegments.add(cleaned);
        }

        String joined = String.join("/", safeSegments);
        if (joined.isBlank()) {
            joined = "file_" + fileIndex;
            warnings.add("WARN empty or unsafe path replaced: " + raw + " -> " + joined);
        } else if (!joined.equals(normalized)) {
            warnings.add("WARN suspicious path normalized during decode: " + raw + " -> " + joined);
        }

        return joined;
    }

    private static Path resolveOutputPath(Path root, String safeRelative, int fileIndex, List<String> warnings) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(safeRelative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            Path fallback = normalizedRoot.resolve("unsafe_" + fileIndex);
            warnings.add("WARN path traversal blocked: " + safeRelative + " -> " + fallback.getFileName());
            return fallback;
        }
        return resolved;
    }

    private static Params paramsForFrame(BufferedImage frame) {
        int overlayHeight = frame.getHeight() >= 2000 ? 144 : 96;
        return new Params(frame.getWidth(), frame.getHeight(), overlayHeight, 8, 24, Params.DEFAULT_CHUNK_SIZE);
    }

    private static void printDecodeInitialEstimate(DecodeReport report) {
        System.out.printf("[decode:estimate] input video=%s%n", humanBytes(report.inputVideoBytes));
        if (report.estimatedVideoDurationSeconds != null) {
            String fpsText = report.estimatedVideoFps == null ? "?" : String.format("%.2f", report.estimatedVideoFps);
            String resolutionText;
            if (report.estimatedVideoWidth != null && report.estimatedVideoHeight != null) {
                resolutionText = report.estimatedVideoWidth + "x" + report.estimatedVideoHeight;
            } else {
                resolutionText = "?x?";
            }
            System.out.printf("[decode:estimate] stream=%s @ %sfps, duration=%s%n",
                    resolutionText,
                    fpsText,
                    humanDurationSeconds(report.estimatedVideoDurationSeconds));
        }
        System.out.println("[decode:estimate] output size will be available after metadata recovery");
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
        if (seconds <= 0) {
            return "0s";
        }
        if (seconds < 60.0) {
            return String.format("%.1fs", seconds);
        }
        long total = Math.round(seconds);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long secs = total % 60L;
        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, secs);
        }
        return String.format("%ds", secs);
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

    private static List<Path> listPngFrames(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private Path resolveWorkDir(Path absOutput) {
        if (work != null) {
            return work.toAbsolutePath().normalize();
        }
        return defaultWorkDir(absOutput);
    }

    private Path defaultWorkDir(Path absOutput) {
        return absOutput.resolveSibling(absOutput.getFileName() + "_work").toAbsolutePath().normalize();
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

    private Path resolveReportPath(Path absWork) {
        if (report != null) {
            return report.toAbsolutePath().normalize();
        }
        return absWork.resolve("decode_report.json").toAbsolutePath().normalize();
    }

    private static final class SessionCollector {
        private final Map<Integer, byte[]> metaShards = new HashMap<>();
        private final List<FramePacket> dataPackets = new ArrayList<>();
        private int metaShardCount = 0;

        private void accept(FramePacket packet) {
            if (packet.type() == FramePacket.Type.META) {
                if (packet.metaShardIndex() >= 0) {
                    metaShards.putIfAbsent(packet.metaShardIndex(), packet.payload());
                }
                metaShardCount = Math.max(metaShardCount, packet.metaShardCount());
            } else if (packet.type() == FramePacket.Type.DATA) {
                dataPackets.add(packet);
            }
        }

        private double metaCoverage() {
            if (metaShardCount <= 0) {
                return 0.0;
            }
            return (double) metaShards.size() / (double) metaShardCount;
        }
    }

    private static final class SessionSelection {
        private final String sessionId;
        private final SessionCollector collector;

        private SessionSelection(String sessionId, SessionCollector collector) {
            this.sessionId = sessionId;
            this.collector = collector;
        }
    }

    private static final class RecoveredFile {
        private final Manifest.FileEntry entry;
        private final String safeRelative;
        private final Path path;
        private final long missingChunks;

        private RecoveredFile(Manifest.FileEntry entry, String safeRelative, Path path, long missingChunks) {
            this.entry = entry;
            this.safeRelative = safeRelative;
            this.path = path;
            this.missingChunks = missingChunks;
        }
    }

    public static final class DecodeReport {
        public String timestamp;
        public String input;
        public String output;
        public String workdir;
        public String ffmpegBinary;
        public long inputVideoBytes;
        public Double estimatedVideoDurationSeconds;
        public Double estimatedVideoFps;
        public Integer estimatedVideoWidth;
        public Integer estimatedVideoHeight;
        public Long estimatedTransferFrames;
        public String sessionId;
        public int totalFiles;
        public long totalBytes;
        public long totalExtractedFrames;
        public long validFrames;
        public long invalidFrames;
        public long invalidDataPackets;
        public long expectedChunks;
        public long receivedChunks;
        public long missingChunks;
        public int hashMismatches;
        public long recoveredBytes;
        public long elapsedMillis;
        public boolean ok;
        public List<FileReport> files;
        public List<String> warnings;
    }

    public static final class FileReport {
        public String path;
        public String expectedSha256;
        public String actualSha256;
        public long missingChunks;
        public String status;
    }
}
