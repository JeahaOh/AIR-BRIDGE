package airbridge.sender;

import airbridge.common.BannerSupport;
import airbridge.common.CliSupport;
import airbridge.common.ConsoleSupport;
import airbridge.packager.UnpackCommand;
import airbridge.slide.SlideApp;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

@Command(
        name = "sender",
        mixinStandardHelpOptions = true,
        resourceBundle = "Messages",
        subcommands = {
                Sender.EncodeCommand.class,
                Sender.SlideCommand.class,
                UnpackCommand.class,
                Sender.ReencodeCommand.class
        }
)
public class Sender implements Runnable {
    private enum Lang {
        ko,
        en
    }

    @Option(names = "--lang", scope = CommandLine.ScopeType.INHERIT, descriptionKey = "option.lang")
    private Lang lang;

    public static void main(String[] args) {
        CliSupport.setLocaleFromArgs(args);
        String[] slideArgs = extractDirectSlideArgs(args);
        if (slideArgs != null) {
            SlideApp.launch(slideArgs);
            return;
        }
        int exitCode = newCommandLine().execute(args);
        System.exit(exitCode);
    }

    private static String[] extractDirectSlideArgs(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        int index = 0;
        while (index < args.length) {
            String arg = args[index];
            if ("slide".equals(arg)) {
                return Arrays.copyOfRange(args, index + 1, args.length);
            }
            if ("--lang".equals(arg)) {
                if (index + 1 >= args.length) {
                    return null;
                }
                index += 2;
                continue;
            }
            if (arg.startsWith("--lang=")) {
                index++;
                continue;
            }
            return null;
        }
        return null;
    }

    static CommandLine newCommandLine() {
        CommandLine commandLine = new CommandLine(new Sender());
        BannerSupport.apply(commandLine, "air-bridge sender");
        ResourceBundle bundle = ResourceBundle.getBundle("Messages", Locale.getDefault());
        commandLine.getCommandSpec().usageMessage().description(bundle.getString("command.description"));
        return commandLine;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    static final class EncodeSharedOptions {
        @Option(names = "--in", paramLabel = "DIR", descriptionKey = "option.in", required = true)
        private Path sourceDir;

        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out", required = true)
        private Path outputDir;

        @Option(names = "--project-name", defaultValue = "PROJECT", descriptionKey = "option.project-name")
        private String projectName = SenderDefaults.DEFAULT_PROJECT_NAME;

        @Option(names = "--encode-root", paramLabel = "DIR", descriptionKey = "option.encode-root")
        private Path encodeRoot;

        @Option(names = "--chunk-data-size", defaultValue = "2000", descriptionKey = "option.chunk-data-size")
        private int chunkDataSize = SenderDefaults.DEFAULT_CHUNK_DATA_SIZE;

        @Option(names = "--qr-image-size", defaultValue = "1200", descriptionKey = "option.qr-image-size")
        private int qrImageSize = SenderDefaults.DEFAULT_QR_IMAGE_SIZE;

        @Option(names = "--qr-error-level", defaultValue = "M", descriptionKey = "option.qr-error-level")
        private ErrorCorrectionLevel qrErrorLevel = SenderDefaults.DEFAULT_QR_ERROR_LEVEL;

        @Option(names = "--label-height", defaultValue = "80", descriptionKey = "option.label-height")
        private int labelHeight = SenderDefaults.DEFAULT_LABEL_HEIGHT;

        @Option(names = "--convert-xlsx-to-csv", descriptionKey = "option.convert-xlsx-to-csv")
        private boolean convertXlsxToCsv;

        @Option(names = "--convert-office-to-text", descriptionKey = "option.convert-office-to-text")
        private boolean convertOfficeToText;

        @Option(names = "--folder-structure", negatable = true, defaultValue = "true", descriptionKey = "option.folder-structure")
        private boolean folderStructure = SenderDefaults.DEFAULT_FOLDER_STRUCTURE;

        @Option(names = "--files-per-folder", defaultValue = "500", descriptionKey = "option.files-per-folder")
        private int filesPerFolder = SenderDefaults.DEFAULT_FILES_PER_FOLDER;

        @Option(names = "--target-extensions", split = ",", paramLabel = "EXT[,EXT...]", descriptionKey = "option.target-extensions")
        private List<String> targetExtensions = new ArrayList<>(SenderDefaults.DEFAULT_TARGET_EXTENSIONS);

        @Option(names = "--skip-dirs", split = ",", paramLabel = "DIR[,DIR...]", descriptionKey = "option.skip-dirs")
        private List<String> skipDirs = new ArrayList<>(SenderDefaults.DEFAULT_SKIP_DIRS);

        @Option(names = "--exclude-paths", split = ",", paramLabel = "PATH[,PATH...]", descriptionKey = "option.exclude-paths")
        private List<String> excludePaths = new ArrayList<>();

        private void validate(CommandLine commandLine) {
            requireMin(commandLine, "--chunk-data-size", chunkDataSize, 1);
            requireMin(commandLine, "--files-per-folder", filesPerFolder, 1);
            requireMin(commandLine, "--qr-image-size", qrImageSize, 1);
            requireMin(commandLine, "--label-height", labelHeight, 0);
        }

        private QrImageWriter newQrImageWriter() {
            return new QrImageWriter(qrImageSize, labelHeight, qrErrorLevel);
        }

        private EncodeService newEncodeService() {
            return new EncodeService(
                    newQrImageWriter(),
                    chunkDataSize,
                    convertXlsxToCsv,
                    convertOfficeToText,
                    folderStructure,
                    filesPerFolder
            );
        }

        private static void requireMin(CommandLine commandLine, String optionName, int actualValue, int minValue) {
            if (actualValue < minValue) {
                throw new CommandLine.ParameterException(
                        commandLine,
                        String.format("%s must be >= %d (was %d)", optionName, minValue, actualValue)
                );
            }
        }
    }

    @Command(name = "encode", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Encode source files and documents into QR images.")
    static final class EncodeCommand implements Callable<Integer> {
        @Mixin
        private EncodeSharedOptions options = new EncodeSharedOptions();

        @Spec
        private CommandSpec spec;

        @Option(names = "--print-html", descriptionKey = "option.print-html")
        private boolean printHtml;

        @Override
        public Integer call() throws Exception {
            options.validate(spec.commandLine());
            Path srcPath = options.sourceDir.toAbsolutePath();
            Path outPath = options.outputDir.toAbsolutePath();

            if (!Files.isDirectory(srcPath)) {
                System.out.println("[ERROR] 소스 디렉토리가 존재하지 않습니다: " + srcPath);
                return 0;
            }

            List<Path> sourceFiles = SourceCollector.collectSourceFiles(
                    srcPath,
                    options.targetExtensions,
                    options.skipDirs,
                    options.excludePaths
            );
            if (sourceFiles.isEmpty()) {
                System.out.println("[WARN] 대상 소스파일이 없습니다: " + srcPath);
                return 0;
            }

            ConsoleSupport.printLine('=', 60);
            System.out.println("air-bridge sender");
            System.out.println("프로젝트 : " + options.projectName);
            System.out.println("소스     : " + srcPath);
            System.out.println("출력     : " + outPath);
            System.out.println("파일수   : " + sourceFiles.size());
            ConsoleSupport.printLine('=', 60);

            Path rootPath = options.encodeRoot != null ? options.encodeRoot.toAbsolutePath() : srcPath;
            EncodeSummary summary = options.newEncodeService().encode(
                    srcPath,
                    outPath,
                    rootPath,
                    options.projectName,
                    options.targetExtensions,
                    options.skipDirs,
                    options.excludePaths,
                    printHtml,
                    System.out::println
            );

            System.out.println();
            ConsoleSupport.printLine('=', 60);
            System.out.println("인코딩 완료!");
            System.out.printf("총 파일: %d개%n", summary.totalFileCount());
            System.out.printf("총 QR:   %d장%n", summary.totalQrCount());
            System.out.printf("총 원본: %,d bytes%n", summary.totalOrigBytes());
            System.out.println("매니페스트: " + summary.manifestPath());
            ConsoleSupport.printLine('=', 60);
            return 0;
        }
    }

    @Command(name = "reencode", hidden = true, mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Regenerate failed files or missing QR chunks from a restore result.")
    static final class ReencodeCommand implements Callable<Integer> {
        @Mixin
        private EncodeSharedOptions options = new EncodeSharedOptions();

        @Spec
        private CommandSpec spec;

        @Option(names = "--restore-dir", paramLabel = "DIR", descriptionKey = "option.restore-dir")
        private Path restoreDir;

        @Option(names = "--reencode-result-path", paramLabel = "FILE", descriptionKey = "option.reencode-result-path")
        private Path reencodeResultPath;

        @Override
        public Integer call() throws Exception {
            options.validate(spec.commandLine());
            Path srcPath = options.sourceDir.toAbsolutePath();
            Path outPath = options.outputDir.toAbsolutePath();
            Path resultFilePath = reencodeResultPath != null
                    ? reencodeResultPath
                    : CliSupport.requirePath(this, restoreDir, "--restore-dir").resolve("_restore_result.txt");

            Path resultPath = resultFilePath;
            if (!Files.exists(resultPath)) {
                System.out.println("[ERROR] 복원 결과 파일이 존재하지 않습니다: " + resultPath);
                return 0;
            }

            if (!Files.isDirectory(srcPath)) {
                System.out.println("[ERROR] 소스 디렉토리가 존재하지 않습니다: " + srcPath);
                return 0;
            }

            Path rootPath = options.encodeRoot != null ? options.encodeRoot.toAbsolutePath() : srcPath;
            List<String> lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
            if (ReencodeResultParser.parseFailedFiles(lines).isEmpty()) {
                System.out.println("재인코딩 대상이 없습니다. 모든 파일이 정상 복원되었습니다.");
                return 0;
            }

            ConsoleSupport.printLine('=', 60);
            System.out.println("air-bridge sender re-encode (실패 청크 재생성)");
            System.out.printf("소스     : %s%n", srcPath);
            System.out.printf("출력     : %s%n", outPath);
            System.out.printf("결과파일 : %s%n", resultPath);
            System.out.printf("대상파일 : %d개%n", ReencodeResultParser.parseFailedFiles(lines).size());
            ConsoleSupport.printLine('=', 60);

            ReencodeSummary summary = options.newEncodeService()
                    .reencode(srcPath, outPath, rootPath, resultPath, options.projectName, System.out::println);

            System.out.println();
            ConsoleSupport.printLine('=', 60);
            System.out.println("재인코딩 완료!");
            System.out.printf("대상 파일: %d개%n", summary.fileCount());
            System.out.printf("생성 QR:   %d장%n", summary.totalQrCount());
            if (summary.errorCount() > 0) {
                System.out.printf("오류:      %d건 (원본 파일 없음)%n", summary.errorCount());
            }
            ConsoleSupport.printLine('=', 60);
            return 0;
        }
    }

    @Command(name = "slide", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Launch the bundled Swing slide player.")
    static final class SlideCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            SlideApp.launch(new String[0]);
            return 0;
        }
    }
}
