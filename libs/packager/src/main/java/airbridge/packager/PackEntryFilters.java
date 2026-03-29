package airbridge.packager;

import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class PackEntryFilters {
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
        String fileName = lastSegment(normalized);
        for (String pattern : patterns) {
            if (pattern.indexOf('/') >= 0) {
                if (globMatches(normalized, pattern)) {
                    return true;
                }
                continue;
            }
            if (globMatches(fileName, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean globMatches(String value, String pattern) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return matcher.matches(java.nio.file.Path.of(value));
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return path;
        }
        return path.substring(slash + 1);
    }
}
