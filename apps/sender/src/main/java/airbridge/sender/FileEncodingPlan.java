package airbridge.sender;

import airbridge.common.CodecSupport;
import airbridge.common.RelativePathSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FileEncodingPlan {
    private final String relPath;
    private final String fileName;
    private final String convertedType;
    private final String fileHash;
    private final String encoded;
    private final int encodedSize;
    private final int totalChunks;
    private final int fileSize;
    private final String safePrefix;

    private FileEncodingPlan(String relPath,
                                String fileName,
                                String convertedType,
                                String fileHash,
                                String encoded,
                                int encodedSize,
                                int totalChunks,
                                int fileSize,
                                String safePrefix) {
        this.relPath = relPath;
        this.fileName = fileName;
        this.convertedType = convertedType;
        this.fileHash = fileHash;
        this.encoded = encoded;
        this.encodedSize = encodedSize;
        this.totalChunks = totalChunks;
        this.fileSize = fileSize;
        this.safePrefix = safePrefix;
    }

    static FileEncodingPlan fromSourceFile(Path file,
                                              String relPath,
                                              boolean convertXlsxToCsv,
                                              boolean convertOfficeToText,
                                              int chunkDataSize) throws Exception {
        String originalName = file.getFileName().toString();
        String originalExt = detectExtension(originalName);

        byte[] rawData;
        String convertedType = null;
        String effectiveRelPath = relPath;
        String effectiveFileName = originalName;

        if (convertXlsxToCsv && ".xlsx".equals(originalExt)) {
            rawData = DocumentConverter.convertXlsxToCsv(file);
            effectiveRelPath = replaceExtension(relPath, ".xlsx", ".csv");
            effectiveFileName = replaceExtension(originalName, ".xlsx", ".csv");
            convertedType = "XLSX\u2192CSV";
        } else if (convertOfficeToText && ".docx".equals(originalExt)) {
            rawData = DocumentConverter.convertDocxToText(file);
            effectiveRelPath = replaceExtension(relPath, ".docx", ".txt");
            effectiveFileName = replaceExtension(originalName, ".docx", ".txt");
            convertedType = "DOCX\u2192TXT";
        } else if (convertOfficeToText && ".pptx".equals(originalExt)) {
            rawData = DocumentConverter.convertPptxToText(file);
            effectiveRelPath = replaceExtension(relPath, ".pptx", ".txt");
            effectiveFileName = replaceExtension(originalName, ".pptx", ".txt");
            convertedType = "PPTX\u2192TXT";
        } else {
            rawData = Files.readAllBytes(file);
        }

        String fileHash = CodecSupport.sha256Hex(rawData);
        String encoded = CodecSupport.compressAndEncode(rawData);
        int encodedSize = encoded.length();
        int totalChunks = (int) Math.ceil((double) encodedSize / chunkDataSize);
        if (totalChunks == 0) {
            totalChunks = 1;
        }

        return new FileEncodingPlan(
                effectiveRelPath,
                effectiveFileName,
                convertedType,
                fileHash,
                encoded,
                encodedSize,
                totalChunks,
                rawData.length,
                buildSafePrefix(effectiveFileName)
        );
    }

    static Path resolveSourceFile(Path rootPath,
                                  String relPath,
                                  boolean convertXlsxToCsv,
                                  boolean convertOfficeToText) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, relPath));

        String lowerRelPath = relPath.toLowerCase(Locale.ROOT);
        if (convertXlsxToCsv && lowerRelPath.endsWith(".csv")) {
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".csv", ".xlsx")));
        }
        if (convertOfficeToText && lowerRelPath.endsWith(".txt")) {
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".txt", ".docx")));
            candidates.add(RelativePathSupport.resolveUnderRoot(rootPath, replaceExtension(relPath, ".txt", ".pptx")));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return candidates.get(0);
    }

    String relPath() {
        return relPath;
    }

    String fileName() {
        return fileName;
    }

    String convertedType() {
        return convertedType;
    }

    String fileHash() {
        return fileHash;
    }

    String encoded() {
        return encoded;
    }

    int encodedSize() {
        return encodedSize;
    }

    int totalChunks() {
        return totalChunks;
    }

    int fileSize() {
        return fileSize;
    }

    String safePrefix() {
        return safePrefix;
    }

    private static String detectExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return "";
        }
        return fileName.substring(dotIdx).toLowerCase(Locale.ROOT);
    }

    private static String replaceExtension(String value, String fromExt, String toExt) {
        if (value.toLowerCase(Locale.ROOT).endsWith(fromExt)) {
            return value.substring(0, value.length() - fromExt.length()) + toExt;
        }
        return value;
    }

    private static String buildSafePrefix(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
        String ext = (dotIdx > 0) ? fileName.substring(dotIdx + 1) : "";
        return baseName + "_" + ext;
    }
}
