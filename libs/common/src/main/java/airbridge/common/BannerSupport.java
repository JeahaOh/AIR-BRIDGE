package airbridge.common;

import picocli.CommandLine;

import java.util.Arrays;
import java.util.Objects;

public final class BannerSupport {
    private static final String BANNER = """
  ____  ____  ____         ____   ____   ____  ___     ____    ___ 
 /    ||    ||    \\\\       |    \\\\ |    \\\\ |    ||   \\\\   /    |  /  _]
|  o  | |  | |  D  )_____ |  o  )|  D  ) |  | |    \\\\ |   __| /  [_ 
|     | |  | |    /|     ||     ||    /  |  | |  D  ||  |  ||    _]
|  _  | |  | |    \\\\|_____||  O  ||    \\\\  |  | |     ||  |_ ||   [_ 
|  |  | |  | |  .  \\\\      |     ||  .  \\\\ |  | |     ||     ||     |
|__|__||____||__|\\\\_|      |_____||__|\\\\_||____||_____||___,_||_____|
                                                                   
""";
    private static final int BANNER_WIDTH = Arrays.stream(BANNER.split("\\R", -1))
            .mapToInt(String::length)
            .max()
            .orElse(0);

    private BannerSupport() {
    }

    public static void apply(CommandLine commandLine, String title) {
        Objects.requireNonNull(commandLine, "commandLine");
        String bannerBlock = render(title);
        commandLine.getCommandSpec().version(bannerBlock);
        commandLine.getCommandSpec().usageMessage().header(bannerBlock + System.lineSeparator());
    }

    public static void applyRecursively(CommandLine commandLine, String title) {
        apply(commandLine, title);
        for (CommandLine subcommand : commandLine.getSubcommands().values()) {
            applyRecursively(subcommand, title);
        }
    }

    public static String render(String title) {
        return render(title, VersionSupport.version());
    }

    public static String render(String title, String versionText) {
        String normalizedTitle = normalize(title, "title");
        String normalizedVersion = normalize(versionText, "versionText");
        String footer = normalizedTitle + " - " + normalizedVersion;
        return BANNER + rightAlign(footer, BANNER_WIDTH) + System.lineSeparator();
    }

    private static String normalize(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String rightAlign(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat(Math.max(0, width - text.length())) + text;
    }
}
