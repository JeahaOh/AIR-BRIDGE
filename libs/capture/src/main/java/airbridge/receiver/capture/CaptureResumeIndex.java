package airbridge.receiver.capture;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, append-only cache used to avoid QR re-decoding every PNG on resume. */
final class CaptureResumeIndex implements AutoCloseable {
    static final String FILE_NAME = "capture-resume.index";

    record Entry(String imageFileName, String payload) {}

    private final Path path;
    private final LinkedBlockingQueue<Entry> pending = new LinkedBlockingQueue<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Thread writer;
    private volatile IOException failure;

    CaptureResumeIndex(Path path, boolean append) throws IOException {
        this.path = path;
        if (!append) {
            Files.deleteIfExists(path);
        }
        writer = new Thread(this::writeLoop, "qer-capture-resume-index");
        writer.setDaemon(true);
        writer.start();
    }

    void append(Path imagePath, String payload) {
        if (failure == null && !closing.get()) {
            pending.offer(new Entry(imagePath.getFileName().toString(), payload));
        }
    }

    private void writeLoop() {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            int buffered = 0;
            while (!closing.get() || !pending.isEmpty()) {
                Entry entry;
                try {
                    entry = pending.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    continue;
                }
                if (entry == null) {
                    if (buffered > 0) {
                        out.flush();
                        buffered = 0;
                    }
                    continue;
                }
                out.write(entry.imageFileName());
                out.write('\t');
                out.write(Base64.getEncoder().encodeToString(entry.payload().getBytes(StandardCharsets.ISO_8859_1)));
                out.newLine();
                if (++buffered >= 64) {
                    out.flush();
                    buffered = 0;
                }
            }
            out.flush();
        } catch (IOException e) {
            failure = e;
        }
    }

    static List<Entry> read(Path path) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> imageFileNames = new HashSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('\t');
            if (separator <= 0 || separator != line.lastIndexOf('\t')) {
                throw new IOException("invalid resume index entry");
            }
            String imageFileName = line.substring(0, separator);
            if (imageFileName.contains("/") || imageFileName.contains("\\") || imageFileName.isBlank()) {
                throw new IOException("invalid resume index file name");
            }
            if (!imageFileNames.add(imageFileName)) {
                throw new IOException("duplicate resume index file name");
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(line.substring(separator + 1));
                entries.add(new Entry(imageFileName, new String(bytes, StandardCharsets.ISO_8859_1)));
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid resume index payload", e);
            }
        }
        return entries;
    }

    @Override
    public void close() throws IOException {
        closing.set(true);
        writer.interrupt();
        try {
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while closing resume index", e);
        }
        if (failure != null) {
            throw failure;
        }
    }
}
