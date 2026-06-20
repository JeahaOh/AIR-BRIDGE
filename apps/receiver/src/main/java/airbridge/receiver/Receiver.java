package airbridge.receiver;

import airbridge.common.AppPaths;
import airbridge.common.BannerExecutionStrategy;
import airbridge.common.BannerSupport;
import airbridge.common.CliSupport;
import airbridge.common.ConsoleSupport;
import airbridge.packager.IdentifyCommand;
import airbridge.packager.PackCommand;
import airbridge.receiver.gui.ReceiverGui;
import airbridge.receiver.capture.CaptureDefaults;
import airbridge.receiver.capture.CaptureDeviceInfo;
import airbridge.receiver.capture.CaptureListener;
import airbridge.receiver.capture.CaptureOptions;
import airbridge.receiver.capture.CaptureService;
import airbridge.receiver.capture.CaptureSupport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(
        name = "receiver",
        mixinStandardHelpOptions = true,
        resourceBundle = "Messages",
        subcommands = {
                Receiver.DecodeCommand.class,
                Receiver.CaptureCommand.class,
                Receiver.GuiCommand.class,
                IdentifyCommand.class,
                PackCommand.class
        }
)
public class Receiver implements Runnable {
    static final String RECEIVER_TITLE = "air-bridge receiver";

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

    static CommandLine newCommandLine() {
        CommandLine commandLine = new CommandLine(new Receiver());
        BannerSupport.apply(commandLine, RECEIVER_TITLE);
        // Print the banner once before any subcommand runs; capture opts out (it prints its own
        // READY banner instead).
        commandLine.setExecutionStrategy(new BannerExecutionStrategy(RECEIVER_TITLE, "capture"));
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
            description = "Open the receiver GUI.")
    static final class GuiCommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("[ERROR] GUI mode requires a graphical desktop environment.");
                return 2;
            }

            CountDownLatch closed = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(() -> {
                JFrame frame = ReceiverGui.createFrame();
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

    @Command(name = "decode", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Decode QR image sets and restore original files.")
    static final class DecodeCommand implements Callable<Integer> {
        @Option(names = "--in", paramLabel = "DIR", descriptionKey = "option.in")
        private Path sourceDir;

        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out")
        private Path outputDir;

        @Option(names = "--decode-workers", defaultValue = "4", descriptionKey = "option.decode-workers")
        private int decodeWorkers = ReceiverDefaults.DEFAULT_DECODE_WORKERS;

        @Override
        public Integer call() throws Exception {
            // Default to jar-relative captured -> decoded when --in/--out are omitted.
            Path srcPath = (sourceDir != null ? sourceDir : AppPaths.capturedDir()).toAbsolutePath().normalize();
            Path outPath = (outputDir != null ? outputDir : AppPaths.decodedDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(srcPath)) {
                System.out.println("[ERROR] QR 입력 디렉토리가 존재하지 않습니다: " + srcPath);
                return 0;
            }

            List<Path> qrFiles = QrDecodeSupport.collectQrImageFiles(srcPath);
            if (qrFiles.isEmpty()) {
                System.out.println("[WARN] 대상 QR PNG 파일이 없습니다: " + srcPath);
                return 0;
            }

            Files.createDirectories(outPath);

            ConsoleSupport.printLine('=', 60);
            System.out.println("air-bridge receiver");
            System.out.println("입력     : " + srcPath);
            System.out.println("복원출력 : " + outPath);
            System.out.println("QR수     : " + qrFiles.size());
            System.out.println("작업스레드: " + normalizedDecodeWorkers());
            ConsoleSupport.printLine('=', 60);

            DecodeSummary summary = new DecodeService(normalizedDecodeWorkers())
                    .decode(srcPath, outPath, qrFiles, System.out::println);

            System.out.println();
            ConsoleSupport.printLine('=', 60);
            System.out.println("복원 완료!");
            System.out.printf("복원 성공: %d개%n", summary.restoredCount());
            System.out.printf("누락 파일: %d개%n", summary.incompleteCount());
            System.out.printf("해시 불일치: %d개%n", summary.hashMismatchCount());
            System.out.printf("QR 읽기/복원 오류: %d건%n", summary.decodeErrorCount());
            System.out.println("결과파일 : " + summary.reportPath());
            ConsoleSupport.printLine('=', 60);
            return 0;
        }

        private int normalizedDecodeWorkers() {
            return Math.max(1, decodeWorkers);
        }
    }

    @Command(name = "capture", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Capture QR frames from a UVC camera source.")
    static final class CaptureCommand implements Callable<Integer> {
        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out")
        private Path outputDir;

        // Accepts a numeric index or a (case-insensitive) device-name substring.
        @Option(names = "--device", defaultValue = "0", descriptionKey = "option.device")
        private String captureDevice = String.valueOf(CaptureDefaults.DEFAULT_DEVICE_INDEX);

        @Option(names = "--width", defaultValue = "1920", descriptionKey = "option.width")
        private int captureWidth = CaptureDefaults.DEFAULT_WIDTH;

        @Option(names = "--height", defaultValue = "1080", descriptionKey = "option.height")
        private int captureHeight = CaptureDefaults.DEFAULT_HEIGHT;

        @Option(names = "--fps", defaultValue = "15", descriptionKey = "option.fps")
        private double captureFps = CaptureDefaults.DEFAULT_FPS;

        @Option(names = "--duration-seconds", defaultValue = "0", descriptionKey = "option.duration-seconds")
        private long durationSeconds = CaptureDefaults.DEFAULT_DURATION_SECONDS;

        @Option(names = "--max-payloads", defaultValue = "0", descriptionKey = "option.max-payloads")
        private int maxPayloads = CaptureDefaults.DEFAULT_MAX_PAYLOADS;

        @Option(names = {"--list-devices", "--device-list"}, descriptionKey = "option.list-devices")
        private boolean listDevices;

        @Option(names = "--status-interval-ms", defaultValue = "10000", descriptionKey = "option.status-interval-ms")
        private long statusIntervalMs = CaptureDefaults.DEFAULT_STATUS_INTERVAL_MS;

        @Option(names = "--decode-workers", defaultValue = "4", descriptionKey = "option.decode-workers")
        private int decodeWorkers = ReceiverDefaults.DEFAULT_DECODE_WORKERS;

        @Option(names = "--same-signal-seconds", defaultValue = "180", descriptionKey = "option.same-signal-seconds")
        private long sameSignalSeconds = CaptureDefaults.DEFAULT_SAME_SIGNAL_SECONDS;

        @Option(names = "--resume", descriptionKey = "option.resume")
        private boolean resume;

        @Override
        public Integer call() throws Exception {
            if (listDevices) {
                BannerSupport.print(RECEIVER_TITLE);
                listCaptureDevices();
                return 0;
            }

            int deviceIndex;
            try {
                deviceIndex = resolveDeviceIndex(captureDevice);
            } catch (IllegalArgumentException e) {
                System.out.println("[ERROR] " + e.getMessage());
                return 0;
            }

            Path outDir = (outputDir != null ? outputDir : AppPaths.capturedDir()).toAbsolutePath().normalize();
            new CaptureService(buildCaptureOptions(outDir, deviceIndex), new CaptureListener() {
                @Override
                public void onLog(String line) {
                    System.out.println(line);
                }

                @Override
                public void onReady() {
                    printCaptureReadyBanner();
                }
            }).run();
            return 0;
        }

        // Re-print the banner at the moment the camera is open and capture has started,
        // as an unmistakable marker of when to begin playing slides.
        private void printCaptureReadyBanner() {
            System.out.println();
            System.out.println(BannerSupport.render("air-bridge receiver — CAPTURE READY"));
        }

        private void listCaptureDevices() {
            System.out.println("[CAPTURE] probing video devices...");
            List<CaptureDeviceInfo> devices = CaptureSupport.listDevices();
            if (devices.isEmpty()) {
                System.out.println("(no devices found)");
                return;
            }
            for (CaptureDeviceInfo device : devices) {
                System.out.printf("[DEVICE] index=%d name=%s status=%s%n",
                        device.index(),
                        device.name(),
                        device.available() ? "available" : "unavailable");
            }
        }

        // Resolves a --device value (numeric index or name substring) to a device index.
        private static int resolveDeviceIndex(String spec) {
            if (spec == null || spec.isBlank()) {
                return CaptureDefaults.DEFAULT_DEVICE_INDEX;
            }
            String value = spec.trim();
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                // not an index; fall through to name matching
            }

            String needle = value.toLowerCase(Locale.ROOT);
            List<CaptureDeviceInfo> devices = CaptureSupport.listDevices();
            CaptureDeviceInfo match = devices.stream()
                    .filter(d -> d.available() && d.name().toLowerCase(Locale.ROOT).contains(needle))
                    .findFirst()
                    .orElseGet(() -> devices.stream()
                            .filter(d -> d.name().toLowerCase(Locale.ROOT).contains(needle))
                            .findFirst()
                            .orElse(null));
            if (match == null) {
                throw new IllegalArgumentException(
                        "일치하는 캡처 디바이스를 찾을 수 없습니다: \"" + value + "\" (사용 가능한 목록: --list-devices)");
            }
            System.out.printf("[CAPTURE] 디바이스 선택: index=%d name=%s%n", match.index(), match.name());
            return match.index();
        }

        private CaptureOptions buildCaptureOptions(Path outDir, int deviceIndex) {
            return new CaptureOptions(
                    outDir,
                    deviceIndex,
                    captureWidth,
                    captureHeight,
                    captureFps,
                    durationSeconds,
                    maxPayloads,
                    Math.max(1, decodeWorkers),
                    Math.max(0, statusIntervalMs),
                    Math.max(1, sameSignalSeconds),
                    resume
            );
        }
    }
}
