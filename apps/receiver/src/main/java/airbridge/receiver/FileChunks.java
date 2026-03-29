package airbridge.receiver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class FileChunks {
    final String project;
    final String relPath;
    final int totalChunks;
    final String hash16;
    private final Map<Integer, String> chunks = new TreeMap<>();
    private final Set<Path> qrFiles = new LinkedHashSet<>();

    FileChunks(String project, String relPath, int totalChunks, String hash16) {
        this.project = project;
        this.relPath = relPath;
        this.totalChunks = totalChunks;
        this.hash16 = hash16;
    }

    void addChunk(QrDecodedChunk chunk, Path qrFile) {
        if (!project.equals(chunk.project) || totalChunks != chunk.totalChunks || !hash16.equals(chunk.hash16)) {
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

    List<Integer> findMissingChunks() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= totalChunks; i++) {
            if (!chunks.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    String joinEncodedData() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= totalChunks; i++) {
            String chunk = chunks.get(i);
            if (chunk == null) {
                throw new IllegalStateException("누락 청크 존재");
            }
            sb.append(chunk);
        }
        return sb.toString();
    }
}
