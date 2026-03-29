package airbridge.sender;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReencodeResultParserTest {

    @Test
    void parseFailedFilesCollectsRecoverableFailuresInInputOrder() {
        Map<String, List<Integer>> failedFiles = ReencodeResultParser.parseFailedFiles(List.of(
                "O docs/ok.txt - OK",
                "! qr/0001.png - QR_READ_ERROR",
                "X docs/missing.txt - INCOMPLETE (누락: [2, 4])",
                "X docs/bad-hash.txt - HASH_MISMATCH",
                "X docs/bad-decode.txt - DECODE_ERROR"
        ));

        assertEquals(
                List.of("docs/missing.txt", "docs/bad-hash.txt", "docs/bad-decode.txt"),
                new ArrayList<>(failedFiles.keySet())
        );
        assertEquals(List.of(2, 4), failedFiles.get("docs/missing.txt"));
        assertTrue(failedFiles.get("docs/bad-hash.txt").isEmpty());
        assertTrue(failedFiles.get("docs/bad-decode.txt").isEmpty());
    }

    @Test
    void parseFailedFilesIgnoresNonRecoverableLines() {
        Map<String, List<Integer>> failedFiles = ReencodeResultParser.parseFailedFiles(List.of(
                "O docs/ok.txt - OK",
                "! qr/0001.png - QR_READ_ERROR"
        ));

        assertTrue(failedFiles.isEmpty());
    }
}
