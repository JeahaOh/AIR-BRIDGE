package com.airbridge.cli;

import com.airbridge.pipeline.DecodePipeline;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(name = "decode", mixinStandardHelpOptions = true)
public final class DecodeCommand implements Runnable {
    @CommandLine.Option(names = "--in", required = true, description = "Captured MP4 path")
    Path input;

    @CommandLine.Option(names = "--out", defaultValue = "decoded-output",
            description = "Reconstructed output directory (default: ${DEFAULT-VALUE})")
    Path output;

    @CommandLine.Option(names = "--work", description = "Work directory (extracted PNG frames and reports)")
    Path work;

    @CommandLine.Option(names = "--report", description = "decode_report.json path")
    Path report;

    @Override
    public void run() {
        try {
            new DecodePipeline(input, output, work, report).run();
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "Decode failed", e);
        }
    }
}
