package com.airbridge.ffmpeg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

public final class FfmpegLocator {
    private FfmpegLocator() {
    }

    public static Optional<Path> extractBundledFfmpeg() {
        return extractBundled("ffmpeg");
    }

    public static Optional<Path> extractBundledFfprobe() {
        return extractBundled("ffprobe");
    }

    public static Optional<Path> extractBundledFfplay() {
        return extractBundled("ffplay");
    }

    private static Optional<Path> extractBundled(String toolName) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String resource;
        String outputName;

        if (os.contains("win")) {
            resource = "/ffmpeg/win-x64/bin/" + toolName + ".exe";
            outputName = toolName + ".exe";
        } else if (os.contains("mac") && (arch.contains("arm") || arch.contains("aarch64"))) {
            resource = "/ffmpeg/mac-arm64/" + toolName;
            outputName = toolName;
        } else {
            return Optional.empty();
        }

        try {
            Path dir = Files.createTempDirectory("airbridge-ffmpeg-");
            Path binary = dir.resolve(outputName);

            try (InputStream in = FfmpegLocator.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return Optional.empty();
                }
                Files.copy(in, binary, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!os.contains("win")) {
                binary.toFile().setExecutable(true);
            }

            binary.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return Optional.of(binary);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
