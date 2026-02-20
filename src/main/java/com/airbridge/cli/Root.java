package com.airbridge.cli;

import picocli.CommandLine;

@CommandLine.Command(
        name = "airbridge",
        mixinStandardHelpOptions = true,
        versionProvider = Root.VersionProvider.class,
        subcommands = {EncodeCommand.class, DecodeCommand.class, PlayCommand.class, FltnCommand.class, UfltnCommand.class, HelpCommand.class, InitCommand.class, SearchCommand.class}
)
public final class Root implements Runnable {
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Root.class.getPackage().getImplementationVersion();
            if (version == null || version.isBlank()) {
                version = "dev";
            }
            return new String[]{"airbridge " + version};
        }
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
