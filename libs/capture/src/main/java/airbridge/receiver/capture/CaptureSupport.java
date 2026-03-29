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
import java.util.concurrent.TimeUnit;

public final class CaptureSupport {
    private CaptureSupport() {
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
        return Collections.emptyMap();
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
