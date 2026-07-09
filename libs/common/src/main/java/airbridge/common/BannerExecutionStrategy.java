package airbridge.common;

import picocli.CommandLine;

import java.util.Set;

/**
 * Picocli execution strategy that prints the ASCII banner once before the resolved subcommand
 * runs, so every CLI command shows it without each command duplicating the print. It is skipped
 * when:
 * <ul>
 *   <li>help or version is requested (the banner is already in the usage header / version text),</li>
 *   <li>no subcommand is selected (the root prints usage, which already carries the banner), or</li>
 *   <li>the leaf command opts out by name (e.g. {@code capture}, which prints its own READY banner).</li>
 * </ul>
 */
public final class BannerExecutionStrategy implements CommandLine.IExecutionStrategy {
    private final String title;
    private final Set<String> skipStartLeafCommands;
    private final CommandLine.IExecutionStrategy delegate = new CommandLine.RunLast();

    public BannerExecutionStrategy(String title, String... skipStartLeafCommands) {
        this.title = title;
        this.skipStartLeafCommands = Set.of(skipStartLeafCommands);
    }

    @Override
    public int execute(CommandLine.ParseResult parseResult) {
        boolean shouldPrintCompletionBanner = shouldPrintCompletionBanner(parseResult);
        if (shouldPrintStartBanner(parseResult)) {
            BannerSupport.print(title);
        }
        try {
            return delegate.execute(parseResult);
        } finally {
            if (shouldPrintCompletionBanner) {
                BannerSupport.print(title + " complete");
            }
        }
    }

    private boolean shouldPrintStartBanner(CommandLine.ParseResult parseResult) {
        if (!shouldPrintCompletionBanner(parseResult)) {
            return false;
        }
        CommandLine.ParseResult leaf = leaf(parseResult);
        return !skipStartLeafCommands.contains(leaf.commandSpec().name());
    }

    private boolean shouldPrintCompletionBanner(CommandLine.ParseResult parseResult) {
        for (CommandLine.ParseResult cur = parseResult; cur != null; cur = cur.subcommand()) {
            if (cur.isUsageHelpRequested() || cur.isVersionHelpRequested()) {
                return false;
            }
        }
        if (!parseResult.hasSubcommand()) {
            return false;
        }
        return true;
    }

    private static CommandLine.ParseResult leaf(CommandLine.ParseResult parseResult) {
        CommandLine.ParseResult leaf = parseResult;
        while (leaf.subcommand() != null) {
            leaf = leaf.subcommand();
        }
        return leaf;
    }
}
