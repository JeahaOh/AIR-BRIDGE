package com.airbridge.cli;

import com.airbridge.pipeline.DecodePipeline;
import com.airbridge.util.LogRedirector;
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

    @CommandLine.Option(names = "--auto-align", description = "Attempt small alignment fixes when decoding frames")
    boolean autoAlign;

    @CommandLine.Option(names = "--fps-fix", description = "Skip duplicate frame numbers during decode")
    boolean fpsFix;

    @Override
    public void run() {
        LogRedirector redirector = null;
        try {
            redirector = LogRedirector.start("decode");
            new DecodePipeline(input, output, work, report, autoAlign, fpsFix).run();
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "Decode failed", e);
        } finally {
            if (redirector != null) {
                redirector.close();
            }
        }
    }
}
