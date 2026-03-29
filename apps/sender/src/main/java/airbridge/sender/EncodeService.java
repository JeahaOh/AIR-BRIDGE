package airbridge.sender;

import airbridge.common.ConsoleSupport;
import airbridge.common.QrPayloadSupport;
import airbridge.common.RelativePathSupport;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EncodeService {
    private final QrImageWriter qrImageWriter;
    private final int chunkDataSize;
    private final boolean convertXlsxToCsv;
    private final boolean convertOfficeToText;
    private final boolean folderStructure;
    private final int filesPerFolder;

    EncodeService(QrImageWriter qrImageWriter,
                     int chunkDataSize,
                     boolean convertXlsxToCsv,
                     boolean convertOfficeToText,
                     boolean folderStructure,
                     int filesPerFolder) {
        if (chunkDataSize < 1) {
            throw new IllegalArgumentException("chunkDataSize must be >= 1");
        }
        if (filesPerFolder < 1) {
            throw new IllegalArgumentException("filesPerFolder must be >= 1");
        }
        this.qrImageWriter = qrImageWriter;
        this.chunkDataSize = chunkDataSize;
        this.convertXlsxToCsv = convertXlsxToCsv;
        this.convertOfficeToText = convertOfficeToText;
        this.folderStructure = folderStructure;
        this.filesPerFolder = filesPerFolder;
    }

    EncodeSummary encode(Path srcPath,
                            Path outPath,
                            Path rootPath,
                            String projectName,
                            List<String> targetExtensions,
                            List<String> skipDirs,
                            List<String> excludePaths,
                            boolean printHtml,
                            EncodeListener listener) throws Exception {
        EncodeListener effectiveListener = listener != null ? listener : line -> { };
        List<Path> sourceFiles = SourceCollector.collectSourceFiles(srcPath, targetExtensions, skipDirs, excludePaths);
        if (sourceFiles.isEmpty()) {
            return new EncodeSummary(0, 0, 0, null);
        }

        Files.createDirectories(outPath);

        int totalQrCount = 0;
        int totalFileCount = 0;
        long totalOrigBytes = 0;
        int pngCounter = 0;
        StringBuilder manifest = new StringBuilder();
        manifest.append("PROJECT: ").append(projectName).append("\n");
        manifest.append("SOURCE : ").append(srcPath).append("\n");
        manifest.append("DATE   : ").append(new Date()).append("\n");
        manifest.append(ConsoleSupport.line('=', 60)).append("\n\n");

        List<Path> allQrPaths = new ArrayList<>();

        for (int fi = 0; fi < sourceFiles.size(); fi++) {
            Path file = sourceFiles.get(fi);
            String sourceRelPath = rootPath.relativize(file).toString().replace('\\', '/');

            String origName = file.getFileName().toString();
            String origExtLower = detectExtension(origName);
            if (".xls".equals(origExtLower)) {
                effectiveListener.onLog("");
                effectiveListener.onLog("  [WARN] .xls(구형 바이너리 포맷)는 자동 CSV 변환 미지원. 원본 그대로 인코딩합니다.");
            }

            FileEncodingPlan plan = FileEncodingPlan.fromSourceFile(
                    file,
                    sourceRelPath,
                    convertXlsxToCsv,
                    convertOfficeToText,
                    chunkDataSize
            );
            totalOrigBytes += plan.fileSize();

            effectiveListener.onLog("");
            if (plan.convertedType() != null) {
                effectiveListener.onLog(String.format("[FILE %d/%d] %s (%s 변환)",
                        fi + 1, sourceFiles.size(), plan.relPath(), plan.convertedType()));
                effectiveListener.onLog(String.format("  변환후: %,d bytes -> 압축+B64: %,d bytes -> QR %d장",
                        plan.fileSize(), plan.encodedSize(), plan.totalChunks()));
            } else {
                effectiveListener.onLog(String.format("[FILE %d/%d] %s", fi + 1, sourceFiles.size(), plan.relPath()));
                effectiveListener.onLog(String.format("  원본: %,d bytes -> 압축+B64: %,d bytes -> QR %d장",
                        plan.fileSize(), plan.encodedSize(), plan.totalChunks()));
            }

            Path fileOutDir = null;
            if (folderStructure) {
                Path relDir = srcPath.relativize(file).getParent();
                fileOutDir = (relDir != null) ? outPath.resolve(relDir) : outPath;
                Files.createDirectories(fileOutDir);
            }

            manifest.append(String.format("[%s] %,d bytes -> QR %d장 (hash: %s)\n",
                    plan.relPath(), plan.fileSize(), plan.totalChunks(), plan.fileHash().substring(0, 16)));

            for (int i = 0; i < plan.totalChunks(); i++) {
                int start = i * chunkDataSize;
                int end = Math.min((i + 1) * chunkDataSize, plan.encodedSize());
                String chunkData = plan.encoded().substring(start, end);

                String payload = QrPayloadSupport.buildPayload(
                        projectName,
                        plan.relPath(),
                        i + 1,
                        plan.totalChunks(),
                        plan.fileHash(),
                        chunkData
                );

                String line1 = buildQrLabel(plan.fileName(), i + 1, plan.totalChunks());
                String line2 = plan.relPath();
                BufferedImage qrImage = qrImageWriter.generateQrImage(payload, line1, line2);

                if (!folderStructure) {
                    String folderName = String.format("%07d", (pngCounter / filesPerFolder) * filesPerFolder);
                    fileOutDir = outPath.resolve(folderName);
                    Files.createDirectories(fileOutDir);
                }

                String qrFileName = buildQrFileName(plan.safePrefix(), i + 1, plan.totalChunks());
                Path qrFilePath = fileOutDir.resolve(qrFileName);
                ImageIO.write(qrImage, "PNG", qrFilePath.toFile());
                allQrPaths.add(qrFilePath);

                totalQrCount++;
                pngCounter++;
            }

            totalFileCount++;
        }

        manifest.append("\n").append(ConsoleSupport.line('=', 60)).append("\n");
        manifest.append(String.format("총 파일: %d개 / 총 QR: %d장 / 총 원본: %,d bytes\n",
                totalFileCount, totalQrCount, totalOrigBytes));

        Path manifestPath = outPath.resolve("_manifest.txt");
        Files.write(manifestPath, manifest.toString().getBytes(StandardCharsets.UTF_8));

        if (printHtml) {
            generatePrintHtml(allQrPaths, outPath, effectiveListener);
        }

        return new EncodeSummary(totalQrCount, totalFileCount, totalOrigBytes, manifestPath);
    }

    ReencodeSummary reencode(Path srcPath,
                                Path outPath,
                                Path rootPath,
                                Path resultPath,
                                String projectName,
                                EncodeListener listener) throws Exception {
        EncodeListener effectiveListener = listener != null ? listener : line -> { };
        List<String> lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
        Map<String, List<Integer>> failedFiles = ReencodeResultParser.parseFailedFiles(lines);

        if (failedFiles.isEmpty()) {
            return new ReencodeSummary(0, 0, 0);
        }

        Files.createDirectories(outPath);
        int totalQrCount = 0;
        int fileCount = 0;
        int errorCount = 0;
        int pngCounter = 0;

        for (Map.Entry<String, List<Integer>> entry : failedFiles.entrySet()) {
            String relPath = entry.getKey();
            List<Integer> missingChunks = entry.getValue();
            String normalizedRelPath;

            try {
                normalizedRelPath = RelativePathSupport.normalizeRelativePath(relPath);
            } catch (IllegalArgumentException e) {
                effectiveListener.onLog(String.format("%n[SKIP] %s - 잘못된 상대 경로: %s", relPath, e.getMessage()));
                errorCount++;
                continue;
            }

            Path originalFile = FileEncodingPlan.resolveSourceFile(
                    rootPath,
                    normalizedRelPath,
                    convertXlsxToCsv,
                    convertOfficeToText
            );
            if (!Files.exists(originalFile)) {
                effectiveListener.onLog(String.format("%n[SKIP] %s - 원본 파일 없음", normalizedRelPath));
                errorCount++;
                continue;
            }

            FileEncodingPlan plan = FileEncodingPlan.fromSourceFile(
                    originalFile,
                    normalizedRelPath,
                    convertXlsxToCsv,
                    convertOfficeToText,
                    chunkDataSize
            );

            List<Integer> chunksToGenerate;
            if (missingChunks.isEmpty()) {
                chunksToGenerate = new ArrayList<>();
                for (int i = 1; i <= plan.totalChunks(); i++) {
                    chunksToGenerate.add(i);
                }
            } else {
                chunksToGenerate = missingChunks;
            }

            Path fileOutDir = outPath;

            effectiveListener.onLog(String.format("%n[FILE %d/%d] %s (총 %d청크 중 %d개 재생성)",
                    fileCount + 1, failedFiles.size(), plan.relPath(),
                    plan.totalChunks(), chunksToGenerate.size()));

            for (int chunkIdx : chunksToGenerate) {
                if (chunkIdx < 1 || chunkIdx > plan.totalChunks()) {
                    effectiveListener.onLog(String.format("  [WARN] 청크 %d는 범위 밖 (총 %d) - 건너뜀", chunkIdx, plan.totalChunks()));
                    continue;
                }

                int start = (chunkIdx - 1) * chunkDataSize;
                int end = Math.min(chunkIdx * chunkDataSize, plan.encodedSize());
                String chunkData = plan.encoded().substring(start, end);

                String payload = QrPayloadSupport.buildPayload(
                        projectName,
                        plan.relPath(),
                        chunkIdx,
                        plan.totalChunks(),
                        plan.fileHash(),
                        chunkData
                );

                String line1 = buildQrLabel(plan.fileName(), chunkIdx, plan.totalChunks());
                String line2 = plan.relPath();
                BufferedImage qrImage = qrImageWriter.generateQrImage(payload, line1, line2);

                if (!folderStructure) {
                    String folderName = String.format("%07d", (pngCounter / filesPerFolder) * filesPerFolder);
                    fileOutDir = outPath.resolve(folderName);
                    Files.createDirectories(fileOutDir);
                }

                String qrFileName = buildQrFileName(plan.safePrefix(), chunkIdx, plan.totalChunks());
                Path qrFilePath = fileOutDir.resolve(qrFileName);
                ImageIO.write(qrImage, "PNG", qrFilePath.toFile());

                effectiveListener.onLog(String.format("  -> %s", qrFileName));
                totalQrCount++;
                pngCounter++;
            }

            fileCount++;
        }

        return new ReencodeSummary(totalQrCount, fileCount, errorCount);
    }

    private String buildQrFileName(String safePrefix, int chunkIdx, int totalChunks) {
        int width = Math.max(3, String.valueOf(totalChunks).length());
        return String.format(Locale.ROOT, "%s_%0" + width + "dof%0" + width + "d.png",
                safePrefix, chunkIdx, totalChunks);
    }

    private String buildQrLabel(String fileName, int chunkIdx, int totalChunks) {
        int width = Math.max(3, String.valueOf(totalChunks).length());
        return String.format(Locale.ROOT, "%s  [%0" + width + "d/%0" + width + "d]",
                fileName, chunkIdx, totalChunks);
    }

    private static String detectExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx <= 0) {
            return "";
        }
        return fileName.substring(dotIdx).toLowerCase(Locale.ROOT);
    }

    private static void generatePrintHtml(List<Path> qrPaths, Path outPath, EncodeListener listener) throws IOException {
        listener.onLog("");
        listener.onLog("[HTML] 인쇄용 HTML 생성 중...");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\">\n");
        html.append("<title>QR Source Backup - Print</title>\n");
        html.append("<style>\n");
        html.append("  @media print { .page { page-break-after: always; } .page:last-child { page-break-after: avoid; } }\n");
        html.append("  body { margin: 0; padding: 0; }\n");
        html.append("  .page { text-align: center; padding: 20px; }\n");
        html.append("  .page img { max-width: 90%; max-height: 80vh; }\n");
        html.append("  .label { font-family: monospace; font-size: 12px; margin-top: 8px; color: #555; }\n");
        html.append("</style>\n</head><body>\n");

        for (Path qrFile : qrPaths) {
            byte[] imgBytes = Files.readAllBytes(qrFile);
            String imgBase64 = Base64.getEncoder().encodeToString(imgBytes);

            html.append("<div class=\"page\">\n");
            html.append("  <img src=\"data:image/png;base64,").append(imgBase64).append("\">\n");
            html.append("  <div class=\"label\">").append(qrFile.getFileName()).append("</div>\n");
            html.append("</div>\n");
        }

        html.append("</body></html>\n");

        Path htmlPath = outPath.resolve("_print.html");
        Files.write(htmlPath, html.toString().getBytes(StandardCharsets.UTF_8));
        listener.onLog("[HTML] 생성 완료: " + htmlPath);
        listener.onLog(String.format("[HTML] QR %d장 포함", qrPaths.size()));
    }
}
