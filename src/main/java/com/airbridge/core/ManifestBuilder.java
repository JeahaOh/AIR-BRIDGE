package com.airbridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class ManifestBuilder {
    private ManifestBuilder() {
    }

    public static List<ScannedFile> scanFiles(Path input, Consumer<String> warn) throws IOException {
        Path absInput = input.toAbsolutePath().normalize();
        if (!Files.exists(absInput)) {
            throw new IOException("Input does not exist: " + absInput);
        }

        List<ScannedFile> files = new ArrayList<>();
        if (Files.isRegularFile(absInput)) {
            String normalized = normalizeRelative(absInput.getFileName().toString());
            warnIfSuspicious(normalized, warn);
            files.add(new ScannedFile(absInput, normalized));
        } else {
            try (Stream<Path> walk = Files.walk(absInput)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String relative = absInput.relativize(path).toString();
                    String normalized = normalizeRelative(relative);
                    warnIfSuspicious(normalized, warn);
                    files.add(new ScannedFile(path.toAbsolutePath().normalize(), normalized));
                });
            }
        }

        files.sort(Comparator.comparing(ScannedFile::relativePath));
        warnIfDuplicated(files, warn);
        return List.copyOf(files);
    }

    private static void warnIfDuplicated(List<ScannedFile> files, Consumer<String> warn) {
        Set<String> seen = new HashSet<>();
        for (ScannedFile file : files) {
            if (!seen.add(file.relativePath())) {
                warn.accept("WARN suspicious duplicate normalized path: " + file.relativePath());
            }
        }
    }

    private static void warnIfSuspicious(String relativePath, Consumer<String> warn) {
        boolean suspicious = relativePath.isBlank()
                || relativePath.startsWith("/")
                || relativePath.contains("../")
                || relativePath.contains("..\\")
                || relativePath.contains(":");

        if (suspicious) {
            warn.accept("WARN suspicious path detected: " + relativePath);
        }
    }

    private static String normalizeRelative(String value) {
        String replaced = value.replace('\\', '/');
        String normalized = Normalizer.normalize(replaced, Normalizer.Form.NFC);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public record ScannedFile(Path absolutePath, String relativePath) {
    }
}
