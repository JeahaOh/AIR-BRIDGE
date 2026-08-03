package airbridge.receiver.capture;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CaptureSupport {
    private static final long PROBE_TIMEOUT_MS = 4000L;

    private CaptureSupport() {
    }

    /**
     * Returns an operator-facing next step when OpenCV cannot open a capture device.
     * Native OpenCV diagnostics are often emitted only to stderr, which is not visible
     * from the Swing device picker.
     */
    public static String deviceAccessHelp() {
        return deviceAccessHelp(System.getProperty("os.name", ""));
    }

    static String deviceAccessHelp(String osName) {
        if (osName.toLowerCase(Locale.ROOT).contains("mac")) {
            return "카메라 장치에 접근할 수 없습니다. 시스템 설정 > 개인정보 보호 및 보안 > 카메라에서 "
                    + "receiver를 실행한 Terminal/iTerm의 권한을 허용하고, OBS 등 장치를 사용하는 앱을 종료한 뒤 다시 시도하세요.";
        }
        return "카메라 장치에 접근할 수 없습니다. 장치 연결, 다른 앱의 장치 점유, 카메라 권한을 확인한 뒤 다시 시도하세요.";
    }

    public static List<CaptureDeviceInfo> listDevices() {
        Map<Integer, String> deviceNames = listVideoDeviceNames();
        List<CaptureDeviceInfo> result = new ArrayList<>();

        if (!deviceNames.isEmpty()) {
            for (Map.Entry<Integer, String> entry : deviceNames.entrySet()) {
                result.add(new CaptureDeviceInfo(entry.getKey(), entry.getValue(), canOpenDevice(entry.getKey())));
            }
            return result;
        }

        for (int index = 0; index < 10; index++) {
            boolean available = canOpenDevice(index);
            if (available) {
                result.add(new CaptureDeviceInfo(index, "Device " + index, true));
            }
        }
        return result;
    }

    public static boolean canOpenDevice(int index) {
        // Opening a camera can block (busy/broken device, permission prompts), so cap each
        // probe with a timeout instead of hanging the whole device scan.
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qe-device-probe-" + index);
            thread.setDaemon(true);
            return thread;
        });
        Future<Boolean> future = executor.submit(() -> openAndGrab(index));
        try {
            return future.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean openAndGrab(int index) {
        try (OpenCVFrameGrabber grabber = new OpenCVFrameGrabber(index)) {
            grabber.setImageWidth(640);
            grabber.setImageHeight(480);
            grabber.setFrameRate(1);
            grabber.start();
            Frame frame = grabber.grab();
            return frame != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<Integer, String> listVideoDeviceNames() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return listMacAvFoundationVideoDeviceNames();
        }
        if (osName.contains("win")) {
            return listWindowsDirectShowVideoDeviceNames();
        }
        return Collections.emptyMap();
    }

    private static Map<Integer, String> listWindowsDirectShowVideoDeviceNames() {
        LinkedHashMap<Integer, String> result = new LinkedHashMap<>();
        Process process = null;
        try {
            // Mirrors the macOS path but for DirectShow. Relies on an "ffmpeg" binary on PATH;
            // when absent, the caller falls back to brute-force index probing.
            process = new ProcessBuilder(
                    "ffmpeg",
                    "-hide_banner",
                    "-f", "dshow",
                    "-list_devices", "true",
                    "-i", "dummy"
            ).redirectErrorStream(true).start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);

            // dshow lists devices by name, not index. Video devices appear in enumeration
            // order, which matches the integer index OpenCVFrameGrabber expects.
            int index = 0;
            for (String line : lines) {
                if (!line.contains("(video)")) {
                    continue;
                }
                int left = line.indexOf('"');
                int right = line.indexOf('"', left + 1);
                if (left < 0 || right < 0) {
                    continue;
                }
                String name = line.substring(left + 1, right).trim();
                if (!name.isEmpty()) {
                    result.put(index++, name);
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyMap();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return result;
    }

    private static Map<Integer, String> listMacAvFoundationVideoDeviceNames() {
        LinkedHashMap<Integer, String> result = new LinkedHashMap<>();
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "ffmpeg",
                    "-hide_banner",
                    "-f", "avfoundation",
                    "-list_devices", "true",
                    "-i", ""
            ).redirectErrorStream(true).start();

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);

            boolean inVideoSection = false;
            for (String line : lines) {
                if (line.contains("AVFoundation video devices")) {
                    inVideoSection = true;
                    continue;
                }
                if (line.contains("AVFoundation audio devices")) {
                    break;
                }
                if (!inVideoSection) {
                    continue;
                }

                int left = line.indexOf('[');
                int right = line.indexOf(']', left + 1);
                if (left < 0 || right < 0) {
                    continue;
                }
                String indexText = line.substring(left + 1, right).trim();
                if (!indexText.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                String name = line.substring(right + 1).trim();
                if (!name.isEmpty()) {
                    result.put(Integer.parseInt(indexText), name);
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyMap();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return result;
    }
}
