package airbridge.sender;

import airbridge.common.CodecSupport;
import airbridge.common.RelativePathSupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Encode plan for a single source file. The gzip-compressed payload is staged to a
 * temporary file (streamed with bounded memory) rather than held in memory, so encoding
 * large files no longer scales heap usage with file size. Callers must {@link #close()}
 * the plan to release the chunk reader and delete the temp file.
 */
final class FileEncodingPlan implements AutoCloseable {
    private final String relPath;
    private final String fileName;
    private final String convertedType;
    private final String fileHash;
    private final Path encodedTempFile;
    private final int encodedSize;
    private final int totalChunks;
    private final int symbolSize;
    private final long fileSize;
    private final String safePrefix;
    private final String flatSafePrefix;
    private volatile FileChannel chunkChannel;

    private FileEncodingPlan(String relPath,
                                String fileName,
                                String convertedType,
                                String fileHash,
                                Path encodedTempFile,
                                int encodedSize,
                                int totalChunks,
                                int symbolSize,
                                long fileSize,
                                String safePrefix,
                                String flatSafePrefix) {
        this.relPath = relPath;
        this.fileName = fileName;
        this.convertedType = convertedType;
        this.fileHash = fileHash;
        this.encodedTempFile = encodedTempFile;
        this.encodedSize = encodedSize;
        this.totalChunks = totalChunks;
        this.symbolSize = symbolSize;
        this.fileSize = fileSize;
        this.safePrefix = safePrefix;
        this.flatSafePrefix = flatSafePrefix;
    }

    static FileEncodingPlan fromSourceFile(Path file,
                                              String relPath,
                                              boolean convertXlsxToCsv,
                                              boolean convertOfficeToText,
                                              int chunkDataSize) throws Exception {
        String originalName = file.getFileName().toString();
        String originalExt = detectExtension(originalName);

        byte[] convertedData = null;
        String convertedType = null;
        String effectiveRelPath = relPath;
        String effectiveFileName = originalName;

        if (convertXlsxToCsv && ".xlsx".equals(originalExt)) {
            convertedData = DocumentConverter.convertXlsxToCsv(file);
            effectiveRelPath = replaceExtension(relPath, ".xlsx", ".csv");
            effectiveFileName = replaceExtension(originalName, ".xlsx", ".csv");
            convertedType = "XLSX\u2192CSV";
        } else if (convertOfficeToText && ".docx".equals(originalExt)) {
            convertedData = DocumentConverter.convertDocxToText(file);
            effectiveRelPath = replaceExtension(relPath, ".docx", ".txt");
            effectiveFileName = replaceExtension(originalName, ".docx", ".txt");
            convertedType = "DOCX\u2192TXT";
        } else if (convertOfficeToText && ".pptx".equals(originalExt)) {
            convertedData = DocumentConverter.convertPptxToText(file);
            effectiveRelPath = replaceExtension(relPath, ".pptx", ".txt");
            effectiveFileName = replaceExtension(originalName, ".pptx", ".txt");
            convertedType = "PPTX\u2192TXT";
        }

        Path tempFile = Files.createTempFile("airbridge-encode-", ".gz");
        tempFile.toFile().deleteOnExit();
        try {
            CodecSupport.CompressedStreamInfo info;
            try (InputStream source = (convertedData != null)
                    ? new ByteArrayInputStream(convertedData)
                    : Files.newInputStream(file)) {
                info = CodecSupport.compressToFile(source, tempFile);
            }

            if (info.compressedByteCount() > Integer.MAX_VALUE) {
                throw new IOException("encoded payload too large to chunk: " + info.compressedByteCount() + " bytes");
            }
            int encodedSize = (int) info.compressedByteCount();
            int totalChunks = (int) Math.ceil((double) encodedSize / chunkDataSize);
            if (totalChunks == 0) {
                totalChunks = 1;
            }

            return new FileEncodingPlan(
                    effectiveRelPath,
                    effectiveFileName,
                    convertedType,
                    info.sha256Hex(),
                    tempFile,
                    encodedSize,
                    totalChunks,
                    chunkDataSize,
                    info.rawByteCount(),
                    buildSafePrefix(effectiveFileName),
                    buildFlatSafePrefix(effectiveRelPath)
            );
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Reads the gzip payload window {@code [start, end)} from the staged temp file. Uses
     * positional reads on a shared {@link FileChannel}, which do not touch the channel's
     * position, so this is safe to call concurrently for different chunks of the same file.
     */
    byte[] readChunk(int start, int end) throws IOException {
        int length = end - start;
        FileChannel channel = channel();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = start;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        byte[] out = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, out, 0, buffer.position());
        return out;
    }

    /**
     * Reads source symbol {@code index} (one of the {@code totalChunks()} symbols the gzip
     * stream is split into) as a fixed {@code symbolSize}-byte block, zero-padding the final
     * symbol. Positional reads, so safe to call concurrently for different symbols of one file.
     */
    byte[] readSymbol(int index) throws IOException {
        int start = index * symbolSize;
        int end = (int) Math.min((long) (index + 1) * symbolSize, encodedSize);
        byte[] out = new byte[symbolSize]; // zero-padded tail for the last symbol
        int length = end - start;
        if (length <= 0) {
            return out;
        }
        FileChannel channel = channel();
        ByteBuffer buffer = ByteBuffer.wrap(out, 0, length);
        long position = start;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return out;
    }

    private FileChannel channel() throws IOException {
        FileChannel channel = chunkChannel;
        if (channel == null) {
            synchronized (this) {
                channel = chunkChannel;
                if (channel == null) {
                    channel = FileChannel.open(encodedTempFile, StandardOpenOption.READ);
                    chunkChannel = channel;
                }
            }
        }
        return channel;
    }

    @Override
    public void close() throws IOException {
        try {
            FileChannel channel = chunkChannel;
            if (channel != null) {
                channel.close();
                chunkChannel = null;
            }
        } finally {
            Files.deleteIfExists(encodedTempFile);
        }
    }

    static Path resolveSourceFile(Path rootPath,
                                  String relPath,
                                  boolean convertXlsxToCsv,
                                  boolean convertOfficeToText) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, relPath));

        String lowerRelPath = relPath.toLowerCase(Locale.ROOT);
        if (convertXlsxToCsv && lowerRelPath.endsWith(".csv")) {
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".csv", ".xlsx")));
        }
        if (convertOfficeToText && lowerRelPath.endsWith(".txt")) {
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".txt", ".docx")));
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".txt", ".pptx")));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return candidates.get(0);
    }

    String relPath() {
        return relPath;
    }

    String fileName() {
        return fileName;
    }

    String convertedType() {
        return convertedType;
    }

    String fileHash() {
        return fileHash;
    }

    int encodedSize() {
        return encodedSize;
    }

    int totalChunks() {
        return totalChunks;
    }

    int symbolSize() {
        return symbolSize;
    }

    long fileSize() {
        return fileSize;
    }

    String safePrefix() {
        return safePrefix;
    }

    String flatSafePrefix() {
        return flatSafePrefix;
    }

    private static String detectExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return "";
        }
        return fileName.substring(dotIdx).toLowerCase(Locale.ROOT);
    }

    private static String replaceExtension(String value, String fromExt, String toExt) {
        if (value.toLowerCase(Locale.ROOT).endsWith(fromExt)) {
            return value.substring(0, value.length() - fromExt.length()) + toExt;
        }
        return value;
    }

    private static String buildSafePrefix(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
        String ext = (dotIdx > 0) ? fileName.substring(dotIdx + 1) : "";
        return baseName + "_" + ext;
    }

    // When the output is flattened (no folder structure), the QR file name must stay
    // unique across the whole source tree; basename alone collides for files such as
    // a/sample.txt and b/sample.txt. Derive the prefix from the relative path so each
    // source file maps to a distinct file name. Sanitizing alone is not enough: two
    // relPaths that differ only in sanitized-away characters (e.g. all-Korean names of
    // equal length) collapse to the same prefix and would silently overwrite each
    // other's PNGs, so a short relPath hash pins uniqueness.
    private static String buildFlatSafePrefix(String relPath) {
        String sanitized = relPath.replaceAll("[^A-Za-z0-9._-]", "_");
        String relPathHash = CodecSupport.sha256Hex(
                relPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 8);
        return buildSafePrefix(sanitized) + "-" + relPathHash;
    }
}
