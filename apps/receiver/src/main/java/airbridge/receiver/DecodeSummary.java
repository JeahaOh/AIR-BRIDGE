package airbridge.receiver;

import java.nio.file.Path;

final class DecodeSummary {
    private final Path reportPath;
    private final int restoredCount;
    private final int incompleteCount;
    private final int hashMismatchCount;
    private final int decodeErrorCount;

    DecodeSummary(Path reportPath,
                     int restoredCount,
                     int incompleteCount,
                     int hashMismatchCount,
                     int decodeErrorCount) {
        this.reportPath = reportPath;
        this.restoredCount = restoredCount;
        this.incompleteCount = incompleteCount;
        this.hashMismatchCount = hashMismatchCount;
        this.decodeErrorCount = decodeErrorCount;
    }

    Path reportPath() {
        return reportPath;
    }

    int restoredCount() {
        return restoredCount;
    }

    int incompleteCount() {
        return incompleteCount;
    }

    int hashMismatchCount() {
        return hashMismatchCount;
    }

    int decodeErrorCount() {
        return decodeErrorCount;
    }
}
