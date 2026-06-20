package airbridge.sender;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ReencodeResultParser {
    private ReencodeResultParser() {
    }

    /**
     * Relative paths of files that did not restore in a {@code _restore_result.txt}. With the
     * fountain transfer there is no per-chunk granularity: a failed file is simply re-emitted as
     * a fresh symbol stream, so this returns just the set of failed paths (deduplicated, in
     * first-seen order).
     */
    static List<String> parseFailedFiles(List<String> lines) {
        Set<String> failed = new LinkedHashSet<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.startsWith("X ")) {
                continue;
            }
            if (line.contains("INCOMPLETE") || line.contains("DECODE_ERROR")
                    || line.contains("HASH_MISMATCH") || line.contains("INVALID_PATH")) {
                int dashIdx = line.indexOf(" - ");
                if (dashIdx > 2) {
                    failed.add(line.substring(2, dashIdx).trim());
                }
            }
        }

        return new ArrayList<>(failed);
    }
}
