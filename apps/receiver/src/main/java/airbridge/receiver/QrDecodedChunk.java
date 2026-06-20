package airbridge.receiver;

final class QrDecodedChunk {
    final String relPath;
    final int chunkIdx;
    final int totalChunks;
    final String hash16;
    final byte[] chunkData;

    QrDecodedChunk(String relPath, int chunkIdx, int totalChunks, String hash16, byte[] chunkData) {
        this.relPath = relPath;
        this.chunkIdx = chunkIdx;
        this.totalChunks = totalChunks;
        this.hash16 = hash16;
        this.chunkData = chunkData;
    }
}
