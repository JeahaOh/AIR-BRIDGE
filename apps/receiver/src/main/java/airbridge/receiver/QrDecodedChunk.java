package airbridge.receiver;

final class QrDecodedChunk {
    final String project;
    final String relPath;
    final int chunkIdx;
    final int totalChunks;
    final String hash16;
    final String chunkData;

    QrDecodedChunk(String project, String relPath, int chunkIdx, int totalChunks, String hash16, String chunkData) {
        this.project = project;
        this.relPath = relPath;
        this.chunkIdx = chunkIdx;
        this.totalChunks = totalChunks;
        this.hash16 = hash16;
        this.chunkData = chunkData;
    }
}
