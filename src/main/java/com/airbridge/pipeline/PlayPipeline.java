package com.airbridge.pipeline;

import com.airbridge.core.Params;
import com.airbridge.ffmpeg.FfmpegRunner;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

public final class PlayPipeline {
    private enum ScaleMode {
        FIT,
        ORIGINAL,
        CUSTOM
    }

    private final Path input;
    private final Integer fpsOverride;

    public PlayPipeline(Path input, Integer fpsOverride) {
        this.input = input;
        this.fpsOverride = fpsOverride;
    }

    public void run() throws Exception {
        Path absInput = input.toAbsolutePath().normalize();
        Path tempFrames = Files.createTempDirectory("airbridge-play-");

        try {
            FfmpegRunner ffmpeg = FfmpegRunner.create();
            ffmpeg.extractPngSequence(absInput, tempFrames.resolve("frame_%06d.png"));

            List<Path> frames = listPngFrames(tempFrames);
            if (frames.isEmpty()) {
                throw new IOException("No frames extracted from: " + absInput);
            }

            int fps = fpsOverride != null ? fpsOverride : Params.default1080p().fps();
            if (fps <= 0) {
                throw new IllegalArgumentException("--fps must be > 0");
            }
            playFrames(frames, fps);
        } finally {
            deleteRecursively(tempFrames);
        }
    }

    private static void playFrames(List<Path> frames, int fps) throws Exception {
        BufferedImage first = ImageIO.read(frames.get(0).toFile());
        if (first == null) {
            throw new IOException("Unable to read first frame");
        }

        AtomicReference<BufferedImage> currentImage = new AtomicReference<>(first);
        AtomicInteger frameIndex = new AtomicInteger(0);
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<ScaleMode> scaleMode = new AtomicReference<>(ScaleMode.FIT);
        AtomicReference<Double> customScale = new AtomicReference<>(1.0);

        JFrame window = new JFrame("AirBridge Player");
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.setBackground(Color.BLACK);

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintFrame((Graphics2D) g, this, currentImage.get(), frameIndex.get(), frames.size(),
                        paused.get(), scaleMode.get(), customScale.get());
            }
        };
        panel.setBackground(Color.BLACK);
        panel.setDoubleBuffered(true);

        window.setContentPane(panel);
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running.set(false);
            }
        });

        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        installKeyBindings(
                window,
                device,
                panel,
                frames,
                currentImage,
                frameIndex,
                paused,
                running,
                scaleMode,
                customScale,
                fps
        );

        SwingUtilities.invokeAndWait(() -> {
            window.setVisible(true);
            if (device.isFullScreenSupported()) {
                device.setFullScreenWindow(window);
            } else {
                window.setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
            window.requestFocusInWindow();
            panel.requestFocusInWindow();
            panel.repaint();
        });

        long frameDelayNanos = Math.max(1L, 1_000_000_000L / Math.max(1, fps));
        try {
            while (running.get()) {
                long tickStart = System.nanoTime();

                if (!paused.get()) {
                    int nextIndex = frameIndex.get() + 1;
                    if (nextIndex >= frames.size()) {
                        running.set(false);
                        continue;
                    }

                    BufferedImage image = ImageIO.read(frames.get(nextIndex).toFile());
                    if (image != null) {
                        frameIndex.set(nextIndex);
                        currentImage.set(image);
                        SwingUtilities.invokeLater(panel::repaint);
                    }
                }

                long elapsed = System.nanoTime() - tickStart;
                long waitNanos = frameDelayNanos - elapsed;
                if (waitNanos > 0) {
                    LockSupport.parkNanos(waitNanos);
                } else {
                    Thread.yield();
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (device.isFullScreenSupported() && device.getFullScreenWindow() == window) {
                    device.setFullScreenWindow(null);
                }
                window.dispose();
            });
        }
    }

    private static void installKeyBindings(
            JFrame window,
            GraphicsDevice device,
            JPanel panel,
            List<Path> frames,
            AtomicReference<BufferedImage> currentImage,
            AtomicInteger frameIndex,
            AtomicBoolean paused,
            AtomicBoolean running,
            AtomicReference<ScaleMode> scaleMode,
            AtomicReference<Double> customScale,
            int fps
    ) {
        InputMap inputMap = window.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = window.getRootPane().getActionMap();

        bind(inputMap, actionMap, "exitFullscreen", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                exitFullscreenOnly(window, device, panel);
            }
        });

        bind(inputMap, actionMap, "exitPlayer", KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                running.set(false);
            }
        });
        bind(inputMap, actionMap, "enterFullscreen", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.ALT_DOWN_MASK), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                enterFullscreen(window, device, panel);
            }
        });

        bind(inputMap, actionMap, "togglePause", KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paused.set(!paused.get());
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "pause", KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paused.set(true);
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "start", KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paused.set(false);
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "restart", KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                jumpToFrame(frames, 0, currentImage, frameIndex, panel);
                paused.set(false);
            }
        });

        // AIR-PLAYER style seek: Left/Right jumps by 5 seconds.
        bind(inputMap, actionMap, "seekBackward5s", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                seekBySeconds(frames, -5, fps, currentImage, frameIndex, panel);
            }
        });
        bind(inputMap, actionMap, "seekForward5s", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                seekBySeconds(frames, +5, fps, currentImage, frameIndex, panel);
            }
        });

        // Precise frame stepping with Shift + arrows.
        bind(inputMap, actionMap, "stepBackwardFrame",
                KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK), new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        paused.set(true);
                        int prev = Math.max(0, frameIndex.get() - 1);
                        jumpToFrame(frames, prev, currentImage, frameIndex, panel);
                    }
                });
        bind(inputMap, actionMap, "stepForwardFrame",
                KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK), new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        paused.set(true);
                        int next = Math.min(frames.size() - 1, frameIndex.get() + 1);
                        jumpToFrame(frames, next, currentImage, frameIndex, panel);
                    }
                });

        bind(inputMap, actionMap, "fitMode", KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                scaleMode.set(ScaleMode.FIT);
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "originalMode", KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                scaleMode.set(ScaleMode.ORIGINAL);
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "fitOriginalToggle", KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (scaleMode.get() == ScaleMode.FIT) {
                    scaleMode.set(ScaleMode.ORIGINAL);
                } else {
                    scaleMode.set(ScaleMode.FIT);
                }
                panel.repaint();
            }
        });

        bind(inputMap, actionMap, "zoomInShiftPlus",
                KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK), new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        adjustZoom(+0.10, panel, currentImage.get(), scaleMode, customScale);
                    }
                });
        bind(inputMap, actionMap, "zoomInPlus",
                KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0), new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        adjustZoom(+0.10, panel, currentImage.get(), scaleMode, customScale);
                    }
                });
        bind(inputMap, actionMap, "zoomInNumpad",
                KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        adjustZoom(+0.10, panel, currentImage.get(), scaleMode, customScale);
                    }
                });

        bind(inputMap, actionMap, "zoomOutMinus", KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                adjustZoom(-0.10, panel, currentImage.get(), scaleMode, customScale);
            }
        });
        bind(inputMap, actionMap, "zoomOutNumpad", KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                adjustZoom(-0.10, panel, currentImage.get(), scaleMode, customScale);
            }
        });
    }

    private static void exitFullscreenOnly(JFrame window, GraphicsDevice device, JPanel panel) {
        if (device.isFullScreenSupported() && device.getFullScreenWindow() == window) {
            device.setFullScreenWindow(null);
            applyWindowedBounds(window);
        } else if ((window.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
            window.setExtendedState(JFrame.NORMAL);
            applyWindowedBounds(window);
        }

        window.toFront();
        window.requestFocusInWindow();
        panel.requestFocusInWindow();
        panel.repaint();
    }

    private static void enterFullscreen(JFrame window, GraphicsDevice device, JPanel panel) {
        if (device.isFullScreenSupported()) {
            if (device.getFullScreenWindow() != window) {
                device.setFullScreenWindow(window);
            }
        } else {
            window.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        window.toFront();
        window.requestFocusInWindow();
        panel.requestFocusInWindow();
        panel.repaint();
    }

    private static void applyWindowedBounds(JFrame window) {
        int width = 1280;
        int height = 720;
        if (window.getGraphicsConfiguration() != null) {
            int screenWidth = window.getGraphicsConfiguration().getBounds().width;
            int screenHeight = window.getGraphicsConfiguration().getBounds().height;
            width = Math.min(width, Math.max(640, screenWidth - 120));
            height = Math.min(height, Math.max(360, screenHeight - 120));
        }
        window.setSize(width, height);
        window.setLocationRelativeTo(null);
    }

    private static void seekBySeconds(
            List<Path> frames,
            int seconds,
            int fps,
            AtomicReference<BufferedImage> currentImage,
            AtomicInteger frameIndex,
            JPanel panel
    ) {
        int deltaFrames = Math.max(1, Math.abs(seconds) * Math.max(1, fps));
        int sign = seconds >= 0 ? 1 : -1;
        int current = frameIndex.get();
        int target = current + (sign * deltaFrames);
        if (target < 0) {
            target = 0;
        } else if (target >= frames.size()) {
            target = frames.size() - 1;
        }
        jumpToFrame(frames, target, currentImage, frameIndex, panel);
    }

    private static void bind(InputMap inputMap, ActionMap actionMap, String actionName, KeyStroke keyStroke, AbstractAction action) {
        inputMap.put(keyStroke, actionName);
        actionMap.put(actionName, action);
    }

    private static void adjustZoom(
            double delta,
            JPanel panel,
            BufferedImage currentImage,
            AtomicReference<ScaleMode> scaleMode,
            AtomicReference<Double> customScale
    ) {
        double current = currentScale(panel, currentImage, scaleMode.get(), customScale.get());
        double updated = clamp(current + delta, 0.10, 5.00);
        customScale.set(updated);
        scaleMode.set(ScaleMode.CUSTOM);
        panel.repaint();
    }

    private static void jumpToFrame(
            List<Path> frames,
            int targetIndex,
            AtomicReference<BufferedImage> currentImage,
            AtomicInteger frameIndex,
            JPanel panel
    ) {
        if (targetIndex < 0 || targetIndex >= frames.size()) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(frames.get(targetIndex).toFile());
            if (image != null) {
                currentImage.set(image);
                frameIndex.set(targetIndex);
                panel.repaint();
            }
        } catch (IOException ignored) {
            // Ignore single-frame seek errors and keep current frame.
        }
    }

    private static void paintFrame(
            Graphics2D g,
            JPanel panel,
            BufferedImage image,
            int frameIndex,
            int totalFrames,
            boolean paused,
            ScaleMode scaleMode,
            double customScale
    ) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, panel.getWidth(), panel.getHeight());

        if (image != null) {
            double scale = currentScale(panel, image, scaleMode, customScale);
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = (panel.getWidth() - drawWidth) / 2;
            int y = (panel.getHeight() - drawHeight) / 2;
            g.drawImage(image, x, y, drawWidth, drawHeight, null);
        }

        drawHud(g, frameIndex, totalFrames, paused, scaleMode, customScale);
    }

    private static double currentScale(JPanel panel, BufferedImage image, ScaleMode mode, double customScale) {
        if (image == null) {
            return 1.0;
        }

        return switch (mode) {
            case FIT -> {
                if (panel.getWidth() <= 0 || panel.getHeight() <= 0) {
                    yield 1.0;
                }
                yield clamp(Math.min(
                        (double) panel.getWidth() / (double) image.getWidth(),
                        (double) panel.getHeight() / (double) image.getHeight()
                ), 0.10, 5.00);
            }
            case ORIGINAL -> 1.0;
            case CUSTOM -> clamp(customScale, 0.10, 5.00);
        };
    }

    private static void drawHud(
            Graphics2D g,
            int frameIndex,
            int totalFrames,
            boolean paused,
            ScaleMode scaleMode,
            double customScale
    ) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 17));

        String state = paused ? "PAUSED" : "PLAYING";
        String scaleText = switch (scaleMode) {
            case FIT -> "FIT";
            case ORIGINAL -> "100%";
            case CUSTOM -> String.format("%.0f%%", customScale * 100.0);
        };

        String line1 = "State: " + state + " | Frame: " + (frameIndex + 1) + "/" + totalFrames + " | Scale: " + scaleText;
        String line2 = "Keys: Space S P R  <-/->=5s  Shift+<-/->=Step  +/-  0/1/F  Alt+Enter=FS ESC=ExitFS Q=Quit";

        int x = 16;
        int y = 28;
        int lineHeight = g.getFontMetrics().getHeight() + 4;
        int width = Math.max(g.getFontMetrics().stringWidth(line1), g.getFontMetrics().stringWidth(line2)) + 16;
        int height = lineHeight * 2 + 10;

        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x - 8, y - 20, width, height, 10, 10);
        g.setColor(new Color(255, 255, 255, 220));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x - 8, y - 20, width, height, 10, 10);

        g.setColor(Color.WHITE);
        g.drawString(line1, x, y);
        g.drawString(line2, x, y + lineHeight);
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static List<Path> listPngFrames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup for temp files.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup for temp files.
        }
    }
}
