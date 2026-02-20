package com.airbridge.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FfplayRunner {
    private final String binary;

    private FfplayRunner(String binary) {
        this.binary = binary;
    }

    public static Optional<FfplayRunner> createOptional() {
        Optional<Path> bundled = FfmpegLocator.extractBundledFfplay();
        if (bundled.isPresent()) {
            String bundledBinary = bundled.get().toAbsolutePath().toString();
            if (isUsable(bundledBinary)) {
                return Optional.of(new FfplayRunner(bundledBinary));
            }
        }

        if (isUsable("ffplay")) {
            if (bundled.isPresent()) {
                System.out.println("WARN bundled ffplay is not runnable; using system ffplay");
            }
            return Optional.of(new FfplayRunner("ffplay"));
        }

        return Optional.empty();
    }

    public void play(Path input, Integer fpsOverride) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("-hide_banner");
        command.add("-autoexit");
        command.add("-loglevel");
        command.add("error");
        if (fpsOverride != null) {
            command.add("-vf");
            command.add("fps=" + fpsOverride);
        }
        command.add(input.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process process = pb.start();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("ffplay failed (exit=" + code + ", binary=" + binary + ")");
        }
    }

    private static boolean isUsable(String binary) {
        List<String> command = List.of(binary, "-version");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            return code == 0 && !output.isBlank();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
