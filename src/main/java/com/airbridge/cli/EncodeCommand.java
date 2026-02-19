package com.airbridge.cli;

import com.airbridge.core.Params;
import com.airbridge.pipeline.EncodePipeline;
import java.nio.file.Path;
import java.util.Locale;
import picocli.CommandLine;

@CommandLine.Command(name = "encode", mixinStandardHelpOptions = true)
public final class EncodeCommand implements Runnable {
    @CommandLine.Option(names = "--in", required = true, description = "Input file or directory")
    Path input;

    @CommandLine.Option(names = "--out", defaultValue = "transfer.mp4",
            description = "Output MP4 path (default: ${DEFAULT-VALUE})")
    Path output;

    @CommandLine.Option(names = "--work", description = "Work directory (PNG frames and reports)")
    Path work;

    @CommandLine.Option(names = "--chunk-size", description = "Chunk size in bytes per data frame (default: 32768)")
    Integer chunkSize;

    @CommandLine.Option(names = "--profile", defaultValue = "1080p",
            description = "Frame profile: 1080p or 4k (default: ${DEFAULT-VALUE})")
    String profile;

    @CommandLine.Option(names = "--fps", description = "Video FPS (default: 24)")
    Integer fps;

    @Override
    public void run() {
        try {
            new EncodePipeline(input, output, work, buildParams()).run();
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "Encode failed", e);
        }
    }

    private Params buildParams() {
        Params base = switch (profile.toLowerCase(Locale.ROOT)) {
            case "1080p" -> Params.default1080p();
            case "4k" -> Params.default4k();
            default -> throw new IllegalArgumentException("Unsupported --profile: " + profile
                    + " (allowed: 1080p, 4k)");
        };

        int resolvedFps = fps == null ? base.fps() : fps;
        if (resolvedFps <= 0) {
            throw new IllegalArgumentException("--fps must be > 0");
        }

        int resolvedChunkSize = chunkSize == null ? base.chunkSize() : chunkSize;
        if (resolvedChunkSize <= 0) {
            throw new IllegalArgumentException("--chunk-size must be > 0");
        }

        return new Params(
                base.width(),
                base.height(),
                base.overlayHeight(),
                base.cellSize(),
                resolvedFps,
                resolvedChunkSize
        );
    }
}
