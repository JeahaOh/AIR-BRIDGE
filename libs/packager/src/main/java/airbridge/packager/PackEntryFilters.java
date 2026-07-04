package airbridge.packager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PackEntryFilters {
    // Patterns come from one small properties list, so this cache stays bounded.
    private static final Map<String, CompiledPattern> COMPILED = new ConcurrentHashMap<>();

    private PackEntryFilters() {
    }

    public static List<String> loadExcludePatterns() {
        List<String> fallback = List.of(
                "__MACOSX/**",
                ".DS_Store",
                ".AppleDouble",
                "._*",
                ".Spotlight-V100/**",
                ".Trashes/**",
                ".fseventsd/**",
                "Thumbs.db",
                "Desktop.ini",
                "$RECYCLE.BIN/**",
                ".directory",
                ".Trash-*",
                ".nfs*",
                ".idea/**",
                ".vscode/**",
                "node_modules/**",
                "__pycache__/**",
                ".gradle/**"
        );
        Properties props = new Properties();
        try (InputStream in = PackEntryFilters.class.getResourceAsStream("/ext/ext.properties")) {
            if (in == null) {
                return fallback;
            }
            props.load(in);
            String raw = props.getProperty("pack.exclude-entry-patterns", "").trim();
            if (raw.isEmpty()) {
                return fallback;
            }
            List<String> result = new ArrayList<>();
            for (String part : raw.split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    result.add(token);
                }
            }
            return result.isEmpty() ? fallback : List.copyOf(result);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean matchesAny(String entryName, List<String> patterns) {
        String normalized = entryName == null ? "" : entryName.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        String fileName = EntryNames.lastSegment(normalized);
        for (String pattern : patterns) {
            CompiledPattern compiled = compile(pattern);
            String value = compiled.fullPath ? normalized : fileName;
            if (compiled.matcher.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a directory entry (trailing '/' stripped) should be dropped from the packed
     * output. Only path-oriented patterns apply here: an "X/**" pattern drops the "X/" entry
     * itself (whose children the same pattern also drops), and a full-path glob is matched
     * against the whole name. Name-only patterns (".DS_Store", ".Trash-*", …) are NOT applied
     * to directories: dropping the directory entry while their children — matched only on
     * their own last segment — survive would orphan the subtree and break the round trip.
     */
    public static boolean matchesAnyDirectory(String directoryEntryName, List<String> patterns) {
        String normalized = directoryEntryName == null ? "" : directoryEntryName.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return false;
        }
        for (String pattern : patterns) {
            CompiledPattern compiled = compile(pattern);
            if (compiled.directoryMatcher != null && compiled.directoryMatcher.matcher(normalized).matches()) {
                return true;
            }
            if (compiled.fullPath && compiled.matcher.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    private static CompiledPattern compile(String pattern) {
        return COMPILED.computeIfAbsent(pattern, CompiledPattern::new);
    }

    /**
     * Platform-independent glob over '/'-separated entry names, compiled once per pattern:
     * '**' crosses segments, '*' and '?' stay within one segment, everything else is
     * literal (no brace/bracket syntax). Matching is case-sensitive on every OS, unlike
     * the default-filesystem PathMatcher this replaces.
     */
    private static final class CompiledPattern {
        final boolean fullPath;
        final Pattern matcher;
        final Pattern directoryMatcher;

        CompiledPattern(String pattern) {
            this.fullPath = pattern.indexOf('/') >= 0;
            this.matcher = Pattern.compile(toRegex(pattern));
            this.directoryMatcher = pattern.endsWith("/**")
                    ? Pattern.compile(toRegex(pattern.substring(0, pattern.length() - 3)))
                    : null;
        }

        private static String toRegex(String glob) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < glob.length()) {
                char c = glob.charAt(i);
                if (c == '*') {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i += 2;
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                    i++;
                } else {
                    if ("\\.[]{}()<>+-=!^$|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }
    }
}
