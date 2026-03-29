package airbridge.receiver;

import java.nio.file.Path;

final class QrDecodeTaskResult {
    final int index;
    final Path qrFile;
    final QrDecodedChunk chunk;
    final Throwable error;
    final int attempts;

    private QrDecodeTaskResult(int index, Path qrFile, QrDecodedChunk chunk, Throwable error, int attempts) {
        this.index = index;
        this.qrFile = qrFile;
        this.chunk = chunk;
        this.error = error;
        this.attempts = attempts;
    }

    static QrDecodeTaskResult success(int index, Path qrFile, QrDecodedChunk chunk, int attempts) {
        return new QrDecodeTaskResult(index, qrFile, chunk, null, attempts);
    }

    static QrDecodeTaskResult failure(int index, Path qrFile, Throwable error, int attempts) {
        return new QrDecodeTaskResult(index, qrFile, null, error, attempts);
    }
}
