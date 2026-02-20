package com.airbridge.cli;

import com.airbridge.pipeline.PlayPipeline;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(name = "play", mixinStandardHelpOptions = true)
public final class PlayCommand implements Runnable {
    @CommandLine.Option(names = "--in", required = true, description = "MP4 file path")
    Path input;

    @CommandLine.Option(names = "--fps", description = "Playback FPS (default: 24)")
    Integer fps;

    @CommandLine.Option(names = "--ffplay", description = "Use ffplay if available (fallbacks to internal player)")
    boolean useFfplay;

    @Override
    public void run() {
        try {
            new PlayPipeline(input, fps, useFfplay).run();
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "Play failed", e);
        }
    }
}
