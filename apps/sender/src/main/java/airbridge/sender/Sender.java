package airbridge.sender;

import airbridge.common.AppPaths;
import airbridge.common.BannerExecutionStrategy;
import airbridge.common.BannerSupport;
import airbridge.common.CliSupport;
import airbridge.common.ConsoleSupport;
import airbridge.packager.UnpackCommand;
import airbridge.query.QueryCommand;
import airbridge.sender.gui.SenderGui;
import airbridge.slide.SlideApp;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(
        name = "sender",
        mixinStandardHelpOptions = true,
        resourceBundle = "Messages",
        subcommands = {
                Sender.EncodeCommand.class,
                Sender.GuiCommand.class,
                QueryCommand.class,
                Sender.SlideCommand.class,
                UnpackCommand.class,
                Sender.ReencodeCommand.class
        }
)
public class Sender implements Runnable {
    static final String SENDER_TITLE = "air-bridge sender";

    private enum Lang {
        ko,
        en
    }

    @Option(names = "--lang", scope = CommandLine.ScopeType.INHERIT, descriptionKey = "option.lang")
    private Lang lang;

    public static void main(String[] args) {
        CliSupport.setLocaleFromArgs(args);
        if (shouldLaunchGuiByDefault(args)) {
            args = appendGuiCommand(args);
        }
        String[] slideArgs = extractDirectSlideArgs(args);
        if (slideArgs != null) {
            BannerSupport.print(SENDER_TITLE);
            try {
                SlideApp.launch(slideArgs);
            } finally {
                BannerSupport.print(SENDER_TITLE + " complete");
            }
            return;
        }
        int exitCode = newCommandLine().execute(args);
        System.exit(exitCode);
    }

    static boolean shouldLaunchGuiByDefault(String[] args) {
        if (args == null || args.length == 0) {
            return true;
        }

        int index = 0;
        while (index < args.length) {
            String arg = args[index];
            if ("--lang".equals(arg)) {
                if (index + 1 >= args.length) {
                    return false;
                }
                index += 2;
                continue;
            }
            if (arg.startsWith("--lang=")) {
                index++;
                continue;
            }
            return false;
        }
        return true;
    }

    private static String[] appendGuiCommand(String[] args) {
        String[] effectiveArgs = Arrays.copyOf(args == null ? new String[0] : args, args == null ? 1 : args.length + 1);
        effectiveArgs[effectiveArgs.length - 1] = "gui";
        return effectiveArgs;
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
        BannerSupport.apply(commandLine, SENDER_TITLE);
        // Print the banner once before any subcommand runs (encode/slide/unpack/gui/reencode).
        commandLine.setExecutionStrategy(new BannerExecutionStrategy(SENDER_TITLE));
        ResourceBundle bundle = ResourceBundle.getBundle("Messages", Locale.getDefault());
        commandLine.getCommandSpec().usageMessage().description(bundle.getString("command.description"));
        applySubcommandDescriptions(commandLine, bundle);
        return commandLine;
    }

    // @Command has no descriptionKey attribute, so the command.<name>.description bundle
    // keys are wired manually (same pattern as the root command.description). This keeps
    // subcommand descriptions localized to the active --lang.
    private static void applySubcommandDescriptions(CommandLine commandLine, ResourceBundle bundle) {
        commandLine.getSubcommands().forEach((name, sub) -> {
            String key = "command." + name + ".description";
            if (bundle.containsKey(key)) {
                sub.getCommandSpec().usageMessage().description(bundle.getString(key));
            }
        });
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "gui", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Open the sender GUI.")
    static final class GuiCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("[ERROR] GUI mode requires a graphical desktop environment.");
                return 2;
            }

            CountDownLatch closed = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = SenderGui.createFrame();
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosed(WindowEvent event) {
                        closed.countDown();
                    }
                });
                frame.setVisible(true);
            });
            closed.await();
            return 0;
        }
    }

    static final class EncodeSharedOptions {
        @Option(names = "--in", paramLabel = "DIR", descriptionKey = "option.in")
        private Path sourceDir;

        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out")
        private Path outputDir;

        // Default to jar-relative directories when --in/--out are omitted, forming the
        // pipeline source -> encoded -> ... See AppPaths.
        Path resolvedSourceDir() {
            Path dir = (sourceDir != null) ? sourceDir : AppPaths.sourceDir();
            return dir.toAbsolutePath().normalize();
        }

        Path resolvedOutputDir() {
            Path dir = (outputDir != null) ? outputDir : AppPaths.encodedDir();
            return dir.toAbsolutePath().normalize();
        }

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

        @Option(names = "--encode-workers", descriptionKey = "option.encode-workers")
        private int encodeWorkers = SenderDefaults.DEFAULT_ENCODE_WORKERS;

        @Option(names = "--repair-overhead", descriptionKey = "option.repair-overhead")
        private double repairOverhead = SenderDefaults.DEFAULT_REPAIR_OVERHEAD;

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
            if (repairOverhead < 0) {
                throw new CommandLine.ParameterException(commandLine,
                        String.format("--repair-overhead must be >= 0 (was %s)", repairOverhead));
            }
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
                    filesPerFolder,
                    encodeWorkers,
                    repairOverhead
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

        @Override
        public Integer call() throws Exception {
            options.validate(spec.commandLine());
            Path srcPath = options.resolvedSourceDir();
            Path outPath = options.resolvedOutputDir();

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
            System.out.println("소스     : " + srcPath);
            System.out.println("출력     : " + outPath);
            System.out.println("파일수   : " + sourceFiles.size());
            ConsoleSupport.printLine('=', 60);

            Path rootPath = options.encodeRoot != null ? options.encodeRoot.toAbsolutePath() : srcPath;
            if (!EncodeService.isSourceUnderRoot(srcPath, rootPath)) {
                System.out.println("[ERROR] --encode-root는 소스 디렉토리의 상위 경로여야 합니다: encode-root="
                        + rootPath.toAbsolutePath().normalize() + ", 소스=" + srcPath.toAbsolutePath().normalize());
                return 0;
            }
            EncodeSummary summary = options.newEncodeService().encode(
                    srcPath,
                    outPath,
                    rootPath,
                    options.targetExtensions,
                    options.skipDirs,
                    options.excludePaths,
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
            Path srcPath = options.resolvedSourceDir();
            Path outPath = options.resolvedOutputDir();
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
                    .reencode(srcPath, outPath, rootPath, resultPath, System.out::println);

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
