package airbridge.sender;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReencodeResultParserTest {

    @Test
    void parseFailedFilesCollectsRecoverableFailuresInInputOrder() {
        List<String> failedFiles = ReencodeResultParser.parseFailedFiles(List.of(
                "O docs/ok.txt - OK",
                "! qr/0001.png - QR_READ_ERROR",
                "X docs/missing.txt - INCOMPLETE (심볼 7/9 소스, 복원 불가)",
                "X docs/bad-hash.txt - HASH_MISMATCH",
                "X docs/bad-decode.txt - DECODE_ERROR",
                "X docs/bad-path.txt - INVALID_PATH"
        ));

        assertEquals(
                List.of("docs/missing.txt", "docs/bad-hash.txt", "docs/bad-decode.txt", "docs/bad-path.txt"),
                failedFiles
        );
    }

    @Test
    void parseFailedFilesDeduplicatesAndIgnoresNonRecoverableLines() {
        List<String> failedFiles = ReencodeResultParser.parseFailedFiles(List.of(
                "O docs/ok.txt - OK",
                "! qr/0001.png - QR_READ_ERROR",
                "X docs/missing.txt - INCOMPLETE (심볼 1/3 소스, 복원 불가)",
                "X docs/missing.txt - INCOMPLETE (심볼 1/3 소스, 복원 불가)"
        ));

        assertEquals(List.of("docs/missing.txt"), failedFiles);
    }

    @Test
    void parseFailedFilesIsEmptyWhenNothingFailed() {
        List<String> failedFiles = ReencodeResultParser.parseFailedFiles(List.of(
                "O docs/ok.txt - OK",
                "! qr/0001.png - QR_READ_ERROR"
        ));

        assertTrue(failedFiles.isEmpty());
    }
}
