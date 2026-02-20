package com.airbridge.ffmpeg;

import com.airbridge.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FfmpegRunner {
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern FPS_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+fps");
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d{2,5})x(\\d{2,5})");

    private final String binary;
    private final String ffprobeBinary;

    private FfmpegRunner(String binary, String ffprobeBinary) {
        this.binary = binary;
        this.ffprobeBinary = ffprobeBinary;
    }

    public static FfmpegRunner create() {
        List<String> diagnostics = new ArrayList<>();
        Optional<Path> bundled = FfmpegLocator.extractBundledFfmpeg();
        Optional<Path> bundledProbe = FfmpegLocator.extractBundledFfprobe();

        if (bundled.isPresent()) {
            String bundledBinary = bundled.get().toAbsolutePath().toString();
            if (isUsable(bundledBinary, diagnostics)) {
                return new FfmpegRunner(bundledBinary, resolveProbeBinary(bundledProbe, diagnostics));
            }
        }

        if (isUsable("ffmpeg", diagnostics)) {
            if (bundled.isPresent()) {
                System.out.println("WARN bundled ffmpeg is not runnable; using system ffmpeg");
            }
            return new FfmpegRunner("ffmpeg", resolveProbeBinary(bundledProbe, diagnostics));
        }

        throw new IllegalStateException("No runnable ffmpeg binary found. Diagnostics: " + String.join(" | ", diagnostics));
    }

    public String binary() {
        return binary;
    }

    public void encodePngSequence(Path framePattern, int fps, Path outputMp4) throws IOException, InterruptedException {
        Path parent = outputMp4.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> args = List.of(
                "-y",
                "-framerate", Integer.toString(fps),
                "-i", framePattern.toString(),
                "-c:v", "libx264",
                "-pix_fmt", "yuv444p",
                "-crf", "0",
                outputMp4.toAbsolutePath().toString()
        );

        run(args, "encode PNG sequence");
    }

    public void extractPngSequence(Path inputMp4, Path outputPattern) throws IOException, InterruptedException {
        Path parent = outputPattern.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<String> args = List.of(
                "-y",
                "-i", inputMp4.toAbsolutePath().toString(),
                "-vsync", "0",
                outputPattern.toString()
        );

        run(args, "extract PNG sequence");
    }

    public Optional<VideoInfo> probeInputVideo(Path inputMp4) throws IOException, InterruptedException {
        Optional<VideoInfo> probed = probeWithFfprobe(inputMp4);
        if (probed.isPresent()) {
            return probed;
        }

        List<String> command = List.of(binary, "-hide_banner", "-i", inputMp4.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        return parseVideoInfo(output);
    }

    private static boolean isUsable(String binary, List<String> diagnostics) {
        List<String> command = List.of(binary, "-version");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code == 0) {
                return true;
            }
            diagnostics.add(binary + " exited with " + code + " while probing -version. Output: " + summarize(output));
            return false;
        } catch (IOException e) {
            diagnostics.add(binary + " probe failed: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            diagnostics.add(binary + " probe interrupted");
            return false;
        }
    }

    private static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 220) {
            return compact;
        }
        return compact.substring(0, 217) + "...";
    }

    private static String resolveProbeBinary(Optional<Path> bundledProbe, List<String> diagnostics) {
        if (bundledProbe.isPresent()) {
            String bundledBinary = bundledProbe.get().toAbsolutePath().toString();
            if (isUsable(bundledBinary, diagnostics)) {
                return bundledBinary;
            }
        }

        if (isUsable("ffprobe", diagnostics)) {
            if (bundledProbe.isPresent()) {
                System.out.println("WARN bundled ffprobe is not runnable; using system ffprobe");
            }
            return "ffprobe";
        }

        return null;
    }

    private Optional<VideoInfo> probeWithFfprobe(Path inputMp4) throws IOException, InterruptedException {
        if (ffprobeBinary == null) {
            return Optional.empty();
        }

        List<String> command = List.of(
                ffprobeBinary,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "format=duration:stream=width,height,avg_frame_rate",
                "-of", "json",
                inputMp4.toAbsolutePath().toString()
        );
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0 || output.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = Jsons.MAPPER.readTree(output);
            Double duration = null;
            Integer width = null;
            Integer height = null;
            Double fps = null;

            JsonNode format = root.path("format");
            if (format.hasNonNull("duration")) {
                String durationText = format.get("duration").asText();
                if (!durationText.isBlank()) {
                    duration = Double.parseDouble(durationText);
                }
            }

            JsonNode streams = root.path("streams");
            if (streams.isArray() && !streams.isEmpty()) {
                JsonNode stream = streams.get(0);
                if (stream.has("width")) {
                    width = stream.get("width").asInt();
                }
                if (stream.has("height")) {
                    height = stream.get("height").asInt();
                }
                if (stream.hasNonNull("avg_frame_rate")) {
                    fps = parseFrameRate(stream.get("avg_frame_rate").asText());
                }
            }

            if (duration == null && width == null && height == null && fps == null) {
                return Optional.empty();
            }
            return Optional.of(new VideoInfo(duration, fps, width, height));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Double parseFrameRate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.contains("/")) {
            String[] parts = trimmed.split("/", 2);
            try {
                double num = Double.parseDouble(parts[0]);
                double den = Double.parseDouble(parts[1]);
                if (den == 0) {
                    return null;
                }
                double fps = num / den;
                return fps > 0 ? fps : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        try {
            double fps = Double.parseDouble(trimmed);
            return fps > 0 ? fps : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Optional<VideoInfo> parseVideoInfo(String ffmpegText) {
        if (ffmpegText == null || ffmpegText.isBlank()) {
            return Optional.empty();
        }

        Double durationSeconds = null;
        Matcher durationMatcher = DURATION_PATTERN.matcher(ffmpegText);
        if (durationMatcher.find()) {
            int hours = Integer.parseInt(durationMatcher.group(1));
            int minutes = Integer.parseInt(durationMatcher.group(2));
            double seconds = Double.parseDouble(durationMatcher.group(3));
            durationSeconds = hours * 3600.0 + minutes * 60.0 + seconds;
        }

        Integer width = null;
        Integer height = null;
        Double fps = null;
        String[] lines = ffmpegText.split("\\R");
        for (String line : lines) {
            if (!line.contains("Video:")) {
                continue;
            }

            Matcher resolutionMatcher = RESOLUTION_PATTERN.matcher(line);
            if (resolutionMatcher.find()) {
                width = Integer.parseInt(resolutionMatcher.group(1));
                height = Integer.parseInt(resolutionMatcher.group(2));
            }

            Matcher fpsMatcher = FPS_PATTERN.matcher(line);
            if (fpsMatcher.find()) {
                fps = Double.parseDouble(fpsMatcher.group(1));
            }
            break;
        }

        if (durationSeconds == null && width == null && height == null && fps == null) {
            return Optional.empty();
        }
        return Optional.of(new VideoInfo(durationSeconds, fps, width, height));
    }

    private void run(List<String> args, String operation) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(binary);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();

        if (code != 0) {
            throw new IOException("ffmpeg failed to " + operation + " (exit=" + code + ", binary=" + binary + ")\n" + output);
        }
    }

    public record VideoInfo(
            Double durationSeconds,
            Double fps,
            Integer width,
            Integer height
    ) {
    }
}
