package airbridge.receiver;

import airbridge.common.BannerSupport;
import airbridge.common.CliSupport;
import airbridge.common.ConsoleSupport;
import airbridge.packager.IdentifyCommand;
import airbridge.packager.PackCommand;
import airbridge.receiver.capture.CaptureDefaults;
import airbridge.receiver.capture.CaptureDeviceInfo;
import airbridge.receiver.capture.CaptureListener;
import airbridge.receiver.capture.CaptureOptions;
import airbridge.receiver.capture.CaptureService;
import airbridge.receiver.capture.CaptureSupport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;

@Command(
        name = "receiver",
        mixinStandardHelpOptions = true,
        resourceBundle = "Messages",
        subcommands = {
                Receiver.DecodeCommand.class,
                Receiver.CaptureCommand.class,
                IdentifyCommand.class,
                PackCommand.class
        }
)
public class Receiver implements Runnable {
    private enum Lang {
        ko,
        en
    }

    @Option(names = "--lang", scope = CommandLine.ScopeType.INHERIT, descriptionKey = "option.lang")
    private Lang lang;

    public static void main(String[] args) {
        CliSupport.setLocaleFromArgs(args);
        int exitCode = newCommandLine().execute(args);
        System.exit(exitCode);
    }

    static CommandLine newCommandLine() {
        CommandLine commandLine = new CommandLine(new Receiver());
        BannerSupport.apply(commandLine, "air-bridge receiver");
        ResourceBundle bundle = ResourceBundle.getBundle("Messages", Locale.getDefault());
        commandLine.getCommandSpec().usageMessage().description(bundle.getString("command.description"));
        return commandLine;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "decode", mixinStandardHelpOptions = true, resourceBundle = "Messages",
            description = "Decode QR image sets and restore original files.")
    static final class DecodeCommand implements Callable<Integer> {
        @Option(names = "--in", paramLabel = "DIR", descriptionKey = "option.in", required = true)
        private Path sourceDir;

        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out", required = true)
        private Path outputDir;

        @Option(names = "--decode-workers", defaultValue = "4", descriptionKey = "option.decode-workers")
        private int decodeWorkers = ReceiverDefaults.DEFAULT_DECODE_WORKERS;

        @Override
        public Integer call() throws Exception {
            Path srcPath = sourceDir.toAbsolutePath();
            Path outPath = outputDir.toAbsolutePath();
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
        @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR", descriptionKey = "option.out", required = true)
        private Path outputDir;

        @Option(names = "--device", defaultValue = "0", descriptionKey = "option.device")
        private int captureDevice = CaptureDefaults.DEFAULT_DEVICE_INDEX;

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
                listCaptureDevices();
                return 0;
            }

            Path outDir = outputDir.toAbsolutePath();
            new CaptureService(buildCaptureOptions(outDir), new CaptureListener() {
                @Override
                public void onLog(String line) {
                    System.out.println(line);
                }
            }).run();
            return 0;
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

        private CaptureOptions buildCaptureOptions(Path outDir) {
            return new CaptureOptions(
                    outDir,
                    captureDevice,
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
