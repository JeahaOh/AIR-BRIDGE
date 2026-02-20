package com.airbridge.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine;

@CommandLine.Command(name = "init", mixinStandardHelpOptions = true, description = "Initialize target files and work folders")
public final class InitCommand implements Runnable {
    @CommandLine.Option(names = "--in", description = "Input jar/zip path (used to locate target files)")
    Path input;

    @Override
    public void run() {
        try {
            Path baseDir = resolveBaseDir();
            ensureFile(baseDir.resolve("target.txt"));
            ensureFile(baseDir.resolve("target-ext.txt"));
            ensureDir(baseDir.resolve("work"));
            ensureDir(baseDir.resolve("decoded-output"));
            ensureDir(baseDir.resolve("decoded-output_work"));
        } catch (IOException e) {
            throw new CommandLine.ExecutionException(new CommandLine(this), "init failed", e);
        }
    }

    private Path resolveBaseDir() {
        if (input != null) {
            Path abs = input.toAbsolutePath().normalize();
            Path parent = abs.getParent();
            return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
        }
        return locateSelfJarDir().orElseGet(() -> Path.of(".").toAbsolutePath().normalize());
    }

    private void ensureFile(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createFile(path);
        }
    }

    private void ensureDir(Path path) throws IOException {
        if (Files.notExists(path)) {
            Files.createDirectories(path);
        }
    }

    private java.util.Optional<Path> locateSelfJarDir() {
        try {
            var location = InitCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (path.toString().toLowerCase().endsWith(".jar")) {
                Path parent = path.getParent();
                if (parent != null) {
                    return java.util.Optional.of(parent);
                }
            }
        } catch (Exception ignored) {
        }
        return java.util.Optional.empty();
    }
}
