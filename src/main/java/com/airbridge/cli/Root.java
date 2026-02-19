package com.airbridge.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "airbridge",
        mixinStandardHelpOptions = true,
        version = "airbridge 1.0.0",
        subcommands = {EncodeCommand.class, DecodeCommand.class, PlayCommand.class}
)
public final class Root implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
