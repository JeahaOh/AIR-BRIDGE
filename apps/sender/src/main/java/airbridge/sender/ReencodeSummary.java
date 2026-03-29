package airbridge.sender;

final class ReencodeSummary {
    private final int totalQrCount;
    private final int fileCount;
    private final int errorCount;

    ReencodeSummary(int totalQrCount, int fileCount, int errorCount) {
        this.totalQrCount = totalQrCount;
        this.fileCount = fileCount;
        this.errorCount = errorCount;
    }

    int totalQrCount() {
        return totalQrCount;
    }

    int fileCount() {
        return fileCount;
    }

    int errorCount() {
        return errorCount;
    }
}
