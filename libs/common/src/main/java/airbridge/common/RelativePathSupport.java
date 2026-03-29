package airbridge.common;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RelativePathSupport {
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:([/\\\\].*|$)");

    private RelativePathSupport() {
    }

    public static String normalizeRelativePath(String relativePath) {
        String candidate = Objects.requireNonNull(relativePath, "relativePath");
        if (candidate.isBlank()) {
            throw new IllegalArgumentException("relative path must not be blank");
        }
        if (candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("relative path must not contain NUL");
        }
        if (candidate.startsWith("/") || candidate.startsWith("\\")) {
            throw new IllegalArgumentException("absolute path is not allowed");
        }
        if (candidate.contains("\\")) {
            throw new IllegalArgumentException("relative path must use '/' separators");
        }
        if (WINDOWS_ABSOLUTE_PATH.matcher(candidate).matches()) {
            throw new IllegalArgumentException("drive-qualified path is not allowed");
        }

        Path parsed;
        try {
            parsed = Path.of(candidate);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid relative path: " + e.getMessage(), e);
        }

        if (parsed.isAbsolute()) {
            throw new IllegalArgumentException("absolute path is not allowed");
        }
        if (parsed.getNameCount() == 0) {
            throw new IllegalArgumentException("relative path must not be empty");
        }

        for (Path part : parsed) {
            String name = part.toString();
            if (".".equals(name) || "..".equals(name)) {
                throw new IllegalArgumentException("relative path must not contain '.' or '..' segments");
            }
        }

        return parsed.normalize().toString().replace('\\', '/');
    }

    public static Path resolveUnderRoot(Path rootPath, String relativePath) {
        Path normalizedRoot = Objects.requireNonNull(rootPath, "rootPath").toAbsolutePath().normalize();
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        Path resolved = normalizedRoot.resolve(normalizedRelativePath).normalize();
        if (resolved.equals(normalizedRoot) || !resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("relative path escapes root");
        }
        return resolved;
    }
}
