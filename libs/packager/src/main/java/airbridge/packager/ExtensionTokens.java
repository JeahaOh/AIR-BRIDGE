package airbridge.packager;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

final class ExtensionTokens {
    private static final Set<String> BLOCKED_PACK_EXTENSIONS = Set.of("png", "jpg", "jpeg");

    private ExtensionTokens() {
    }

    static Set<String> filterIncluded(List<String> tokens) {
        Set<String> excluded = loadExcludedTokens();
        Set<String> results = new TreeSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!excluded.contains(normalized)) {
                results.add(normalized);
            }
        }
        return results;
    }

    static Set<String> loadExcludedTokens() {
        Set<String> fallback = Set.of(
                "class", "xml", "js", "jsp", "html", "css", "exe", "zip", "jar", "properties",
                "png", "jpg", "jpeg"
        );
        Properties props = new Properties();
        try (InputStream in = ExtensionTokens.class.getResourceAsStream("/ext/ext.properties")) {
            if (in == null) {
                return fallback;
            }
            props.load(in);
            String raw = props.getProperty("exts.exclude", "").trim();
            if (raw.isEmpty()) {
                return fallback;
            }
            Set<String> result = new TreeSet<>();
            String[] parts = raw.split(",");
            for (String part : parts) {
                String token = part.trim().toLowerCase(Locale.ROOT);
                if (!token.isEmpty()) {
                    result.add(token);
                }
            }
            return result.isEmpty() ? fallback : result;
        } catch (Exception e) {
            return fallback;
        }
    }

    static boolean isBlockedPackExtension(String ext) {
        return BLOCKED_PACK_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
    }
}
