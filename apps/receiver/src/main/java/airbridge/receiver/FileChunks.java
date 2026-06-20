package airbridge.receiver;

import airbridge.common.fountain.LtDecoder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Accumulates the fountain symbols captured for one file and rebuilds the gzip stream once
 * enough distinct symbols arrive. Symbols arrive in any order; identity is keyed by relPath,
 * and {@code k}/{@code gzipLen}/{@code hash16}/symbol size must agree across them.
 */
final class FileChunks {
    final String relPath;
    final int k;
    final int gzipLen;
    final int symbolSize;
    final String hash16;
    private final LtDecoder decoder;
    private final Set<Path> qrFiles = new LinkedHashSet<>();

    FileChunks(String relPath, int k, int gzipLen, int symbolSize, String hash16) {
        this.relPath = relPath;
        this.k = k;
        this.gzipLen = gzipLen;
        this.symbolSize = symbolSize;
        this.hash16 = hash16;
        this.decoder = new LtDecoder(k, symbolSize);
    }

    void addChunk(QrDecodedChunk chunk, Path qrFile) {
        if (k != chunk.k || gzipLen != chunk.gzipLen || !hash16.equals(chunk.hash16)
                || symbolSize != chunk.symbolData.length) {
            throw new IllegalArgumentException("동일 파일에 대한 메타데이터가 일치하지 않습니다: " + qrFile.getFileName());
        }
        qrFiles.add(qrFile);
        decoder.offer(chunk.esi, chunk.symbolData);
    }

    List<Path> qrFiles() {
        return new ArrayList<>(qrFiles);
    }

    /** True once the fountain decoder has recovered all {@code k} source symbols. */
    boolean isComplete() {
        return decoder.isComplete();
    }

    /** Distinct symbols captured so far (for progress reporting). */
    int receivedCount() {
        return decoder.receivedCount();
    }

    /**
     * The reassembled gzip stream. Requires {@link #isComplete()} (call after the completeness
     * check). Materializes the block in memory, which the decoder already holds.
     */
    InputStream encodedStream() {
        return new ByteArrayInputStream(decoder.reassemble(gzipLen));
    }
}
