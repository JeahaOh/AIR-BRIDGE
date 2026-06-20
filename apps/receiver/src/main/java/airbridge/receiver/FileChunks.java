package airbridge.receiver;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class FileChunks {
    final String relPath;
    final int totalChunks;
    final String hash16;
    private final Map<Integer, byte[]> chunks = new TreeMap<>();
    private final Set<Path> qrFiles = new LinkedHashSet<>();

    FileChunks(String relPath, int totalChunks, String hash16) {
        this.relPath = relPath;
        this.totalChunks = totalChunks;
        this.hash16 = hash16;
    }

    void addChunk(QrDecodedChunk chunk, Path qrFile) {
        if (totalChunks != chunk.totalChunks || !hash16.equals(chunk.hash16)) {
            throw new IllegalArgumentException("동일 파일에 대한 메타데이터가 일치하지 않습니다: " + qrFile.getFileName());
        }
        if (chunk.chunkIdx < 1 || chunk.chunkIdx > totalChunks) {
            throw new IllegalArgumentException("청크 번호 범위 오류: " + chunk.chunkIdx);
        }
        qrFiles.add(qrFile);
        chunks.put(chunk.chunkIdx, chunk.chunkData);
    }

    List<Path> qrFiles() {
        return new ArrayList<>(qrFiles);
    }

    // addChunk dedupes by chunk index and rejects out-of-range indices, so having one entry
    // per index (size == totalChunks) means every chunk is present. O(1) completeness check.
    boolean isComplete() {
        return chunks.size() == totalChunks;
    }

    List<Integer> findMissingChunks() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= totalChunks; i++) {
            if (!chunks.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /**
     * Streams the ordered gzip chunk bytes without materializing the full payload. Holds only
     * one chunk's bytes at a time, so decode memory does not scale with file size. Requires all
     * chunks to be present (call after completeness check).
     */
    InputStream orderedEncodedStream() {
        return new InputStream() {
            private int nextChunk = 1;
            private byte[] current = new byte[0];
            private int pos = 0;

            @Override
            public int read() {
                if (!ensureAvailable()) {
                    return -1;
                }
                return current[pos++] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (len == 0) {
                    return 0;
                }
                if (!ensureAvailable()) {
                    return -1;
                }
                int n = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, n);
                pos += n;
                return n;
            }

            private boolean ensureAvailable() {
                while (pos >= current.length) {
                    if (nextChunk > totalChunks) {
                        return false;
                    }
                    byte[] chunk = chunks.get(nextChunk++);
                    if (chunk == null) {
                        throw new IllegalStateException("누락 청크 존재");
                    }
                    current = chunk;
                    pos = 0;
                }
                return true;
            }
        };
    }
}
