package airbridge.common;

public final class QrPayloadSupport {
    public static final String HEADER_SEP = "\u001E";
    public static final String FIELD_SEP = "\u001F";

    private QrPayloadSupport() {
    }

    public static String buildPayload(String project, String relPath,
                                      int chunkIdx, int totalChunks,
                                      String fileHash, String chunkData) {
        String header = String.join(FIELD_SEP,
                project,
                relPath,
                String.valueOf(chunkIdx),
                String.valueOf(totalChunks),
                fileHash.substring(0, 16)
        );
        return "HDR" + HEADER_SEP + header + HEADER_SEP + chunkData;
    }
}
