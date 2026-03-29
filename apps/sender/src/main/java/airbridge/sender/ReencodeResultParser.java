package airbridge.sender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReencodeResultParser {
    private ReencodeResultParser() {
    }

    static Map<String, List<Integer>> parseFailedFiles(List<String> lines) {
        Map<String, List<Integer>> failedFiles = new LinkedHashMap<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("X ") && line.contains("INCOMPLETE")) {
                int dashIdx = line.indexOf(" - INCOMPLETE");
                if (dashIdx > 2) {
                    String filePath = line.substring(2, dashIdx).trim();
                    failedFiles.put(filePath, parseMissingChunks(line));
                }
            } else if (line.startsWith("X ") && (line.contains("DECODE_ERROR") || line.contains("HASH_MISMATCH"))) {
                int dashIdx = line.indexOf(" - ");
                if (dashIdx > 2) {
                    String filePath = line.substring(2, dashIdx).trim();
                    failedFiles.put(filePath, new ArrayList<>());
                }
            }
        }

        return failedFiles;
    }

    private static List<Integer> parseMissingChunks(String line) {
        List<Integer> missingChunks = new ArrayList<>();
        int bracketStart = line.indexOf('[');
        int bracketEnd = line.indexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            String nums = line.substring(bracketStart + 1, bracketEnd);
            for (String num : nums.split(",")) {
                missingChunks.add(Integer.parseInt(num.trim()));
            }
        }
        return missingChunks;
    }
}
