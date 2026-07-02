package airbridge.receiver;

import java.nio.file.Path;

final class QrDecodeTaskResult {
    final int index;
    final Path qrFile;
    final QrDecodedChunk chunk;
    final Throwable error;
    final int attempts;
    /** True when the PNG was not decoded because its file had already been restored. */
    final boolean skippedRestored;

    private QrDecodeTaskResult(int index, Path qrFile, QrDecodedChunk chunk, Throwable error,
                               int attempts, boolean skippedRestored) {
        this.index = index;
        this.qrFile = qrFile;
        this.chunk = chunk;
        this.error = error;
        this.attempts = attempts;
        this.skippedRestored = skippedRestored;
    }

    static QrDecodeTaskResult success(int index, Path qrFile, QrDecodedChunk chunk, int attempts) {
        return new QrDecodeTaskResult(index, qrFile, chunk, null, attempts, false);
    }

    static QrDecodeTaskResult failure(int index, Path qrFile, Throwable error, int attempts) {
        return new QrDecodeTaskResult(index, qrFile, null, error, attempts, false);
    }

    static QrDecodeTaskResult skippedRestored(int index, Path qrFile) {
        return new QrDecodeTaskResult(index, qrFile, null, null, 0, true);
    }
}
