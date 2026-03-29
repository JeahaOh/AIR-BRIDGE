package airbridge.sender;

import java.nio.file.Path;

final class EncodeSummary {
    private final int totalQrCount;
    private final int totalFileCount;
    private final long totalOrigBytes;
    private final Path manifestPath;

    EncodeSummary(int totalQrCount, int totalFileCount, long totalOrigBytes, Path manifestPath) {
        this.totalQrCount = totalQrCount;
        this.totalFileCount = totalFileCount;
        this.totalOrigBytes = totalOrigBytes;
        this.manifestPath = manifestPath;
    }

    int totalQrCount() {
        return totalQrCount;
    }

    int totalFileCount() {
        return totalFileCount;
    }

    long totalOrigBytes() {
        return totalOrigBytes;
    }

    Path manifestPath() {
        return manifestPath;
    }
}
