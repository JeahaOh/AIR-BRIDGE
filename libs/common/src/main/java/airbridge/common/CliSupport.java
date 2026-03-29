package airbridge.common;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Locale;

public final class CliSupport {
    private CliSupport() {
    }

    public static Path requirePath(Object command, Path value, String optionName) {
        if (value == null) {
            throw new CommandLine.ParameterException(new CommandLine(command), optionName + " 옵션이 필요합니다.");
        }
        return value;
    }

    public static void setLocaleFromArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--lang=")) {
                applyLocale(arg.substring("--lang=".length()));
                return;
            }
            if ("--lang".equals(arg) && i + 1 < args.length) {
                applyLocale(args[i + 1]);
                return;
            }
        }
    }

    private static void applyLocale(String languageTag) {
        if (languageTag == null || languageTag.trim().isEmpty()) {
            return;
        }
        Locale.setDefault(Locale.forLanguageTag(languageTag.trim()));
    }
}
