package airbridge.receiver;

/** One decoded fountain symbol frame (see {@code airbridge.common.QrPayloadSupport}). */
final class QrDecodedChunk {
    final String relPath;
    final String hash16;
    final int k;
    final int gzipLen;
    final int esi;
    final byte[] symbolData;

    QrDecodedChunk(String relPath, String hash16, int k, int gzipLen, int esi, byte[] symbolData) {
        this.relPath = relPath;
        this.hash16 = hash16;
        this.k = k;
        this.gzipLen = gzipLen;
        this.esi = esi;
        this.symbolData = symbolData;
    }
}
