package airbridge.receiver.gui;

import airbridge.common.gui.DirectoryChooser;
import airbridge.receiver.DecodeWorkflow;
import airbridge.receiver.capture.CaptureDefaults;
import airbridge.receiver.capture.CaptureDeviceInfo;
import airbridge.receiver.capture.CaptureListener;
import airbridge.receiver.capture.CaptureOptions;
import airbridge.receiver.capture.CaptureService;
import airbridge.receiver.capture.CaptureStatus;
import airbridge.receiver.capture.CaptureSummary;
import airbridge.receiver.capture.CaptureSupport;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ReceiverGui {
    private static final int DEFAULT_DECODE_WORKERS = 4;
    private static final double DEFAULT_PREVIEW_FPS = 15.0d;
    private static final double MIN_PREVIEW_FPS = 0.1d;
    private static final double MAX_PREVIEW_FPS = 15.0d;

    private ReceiverGui() {
    }

    public static JFrame createFrame() {
        JFrame frame = new JFrame("air-bridge receiver");
        ReceiverPanel panel = new ReceiverPanel();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(panel);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                panel.requestClose(frame);
            }
        });
        frame.setMinimumSize(new Dimension(960, 640));
        frame.setSize(1180, 760);
        frame.setLocationByPlatform(true);
        return frame;
    }

    private static final class ReceiverPanel extends JPanel {
        private final JTextField outputDirField = new JTextField(36);
        private final JSpinner deviceSpinner = intSpinner(CaptureDefaults.DEFAULT_DEVICE_INDEX, 0, 999, 1);
        private final JSpinner widthSpinner = intSpinner(CaptureDefaults.DEFAULT_WIDTH, 1, 16384, 1);
        private final JSpinner heightSpinner = intSpinner(CaptureDefaults.DEFAULT_HEIGHT, 1, 16384, 1);
        private final JSpinner fpsSpinner = doubleSpinner(CaptureDefaults.DEFAULT_FPS, 0.1d, 240.0d, 0.1d);
        private final JSpinner statusIntervalSpinner = longSpinner(CaptureDefaults.DEFAULT_STATUS_INTERVAL_MS, 0L, 3_600_000L, 1_000L);
        private final JSpinner captureDecodeWorkersSpinner = intSpinner(DEFAULT_DECODE_WORKERS, 1, 256, 1);
        private final JCheckBox resumeCheck = new JCheckBox("Resume");
        private final JCheckBox previewCheck = new JCheckBox("Preview", true);
        private final JSpinner previewFpsSpinner = doubleSpinner(DEFAULT_PREVIEW_FPS, MIN_PREVIEW_FPS, MAX_PREVIEW_FPS, 0.5d);
        private final JButton startButton = new JButton("Start");
        private final JButton stopButton = new JButton("Stop");
        private final JButton listDevicesButton = new JButton("Devices");
        private final JButton outputBrowseButton = new JButton("Browse");
        private final JLabel statusLabel = new JLabel("Idle");
        private final JTextArea logArea = new JTextArea();
        private final PreviewPanel previewPanel = new PreviewPanel();
        private final JTextField decodeInputDirField = new JTextField(36);
        private final JTextField decodeOutputDirField = new JTextField(36);
        private final JSpinner decodeWorkersSpinner = intSpinner(DEFAULT_DECODE_WORKERS, 1, 256, 1);
        private final JButton decodeInputBrowseButton = new JButton("Browse");
        private final JButton decodeOutputBrowseButton = new JButton("Browse");
        private final JButton decodeStartButton = new JButton("Decode");
        private final JLabel decodeStatusLabel = new JLabel("Idle");
        private final JTextArea decodeLogArea = new JTextArea();
        private final AtomicBoolean previewUpdateScheduled = new AtomicBoolean();
        private final AtomicBoolean savedImageUpdateScheduled = new AtomicBoolean();
        private final AtomicLong lastPreviewQueuedNanos = new AtomicLong();
        private final AtomicReference<BufferedImage> pendingPreviewImage = new AtomicReference<>();
        private final AtomicReference<SavedImageUpdate> pendingSavedImage = new AtomicReference<>();

        private volatile CaptureService activeService;
        private CaptureWorker captureWorker;
        private DecodeWorker decodeWorker;
        private volatile boolean previewEnabled = true;
        private volatile double previewFps = DEFAULT_PREVIEW_FPS;
        private boolean closeAfterCapture;
        private boolean closeAfterDecode;

        ReceiverPanel() {
            super(new BorderLayout(8, 8));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(buildTabs(), BorderLayout.CENTER);
        }

        private JTabbedPane buildTabs() {
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Capture", buildCapturePanel());
            tabs.addTab("Decode", buildDecodePanel());
            return tabs;
        }

        private JPanel buildCapturePanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.add(buildCaptureForm(), BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setLineWrap(false);
            JScrollPane logScroll = new JScrollPane(logArea);
            logScroll.setPreferredSize(new Dimension(420, 180));

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, previewPanel, logScroll);
            splitPane.setResizeWeight(0.65d);
            panel.add(splitPane, BorderLayout.CENTER);
            panel.add(statusLabel, BorderLayout.SOUTH);
            updateRunningState(false);
            return panel;
        }

        private JPanel buildCaptureForm() {
            JPanel wrapper = new JPanel(new BorderLayout(8, 8));
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createTitledBorder("Capture"));

            outputBrowseButton.addActionListener(event -> chooseOutputDirectory());
            previewCheck.addActionListener(event -> updatePreviewEnabled());
            previewFpsSpinner.addChangeListener(event -> updatePreviewFps());

            int row = 0;
            addField(form, row++, "Output", outputDirField, outputBrowseButton);
            addField(form, row++, "Device", deviceSpinner, widthSpinner, "Width");
            addField(form, row++, "Height", heightSpinner, fpsSpinner, "FPS");
            addField(form, row++, "Workers", captureDecodeWorkersSpinner, statusIntervalSpinner, "Status ms");
            addField(form, row++, "Preview", previewCheck, previewFpsSpinner, "Preview FPS");
            addField(form, row, "Resume", resumeCheck, new JLabel(), "");

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            startButton.addActionListener(event -> startCapture());
            stopButton.addActionListener(event -> stopCapture());
            listDevicesButton.addActionListener(event -> listDevices());
            buttons.add(startButton);
            buttons.add(stopButton);
            buttons.add(listDevicesButton);

            wrapper.add(form, BorderLayout.CENTER);
            wrapper.add(buttons, BorderLayout.SOUTH);
            return wrapper;
        }

        private JPanel buildDecodePanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.add(buildDecodeForm(), BorderLayout.NORTH);

            decodeLogArea.setEditable(false);
            decodeLogArea.setLineWrap(false);
            JScrollPane logScroll = new JScrollPane(decodeLogArea);
            panel.add(logScroll, BorderLayout.CENTER);
            panel.add(decodeStatusLabel, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildDecodeForm() {
            JPanel wrapper = new JPanel(new BorderLayout(8, 8));
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createTitledBorder("Decode"));

            decodeInputBrowseButton.addActionListener(event -> chooseDirectory(decodeInputDirField, "Choose QR image directory"));
            decodeOutputBrowseButton.addActionListener(event -> chooseDirectory(decodeOutputDirField, "Choose restore output directory"));

            int row = 0;
            addField(form, row++, "Input", decodeInputDirField, decodeInputBrowseButton);
            addField(form, row++, "Output", decodeOutputDirField, decodeOutputBrowseButton);
            addField(form, row, "Workers", decodeWorkersSpinner, new JLabel(), "");

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            decodeStartButton.addActionListener(event -> startDecode());
            buttons.add(decodeStartButton);

            wrapper.add(form, BorderLayout.CENTER);
            wrapper.add(buttons, BorderLayout.SOUTH);
            return wrapper;
        }

        private void addField(JPanel form, int row, String label, java.awt.Component field,
                              java.awt.Component secondField, String secondLabel) {
            GridBagConstraints c = baseConstraints(row);
            c.gridx = 0;
            c.weightx = 0.0d;
            form.add(new JLabel(label), c);

            c = baseConstraints(row);
            c.gridx = 1;
            c.weightx = 1.0d;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(field, c);

            c = baseConstraints(row);
            c.gridx = 2;
            c.weightx = 0.0d;
            form.add(new JLabel(secondLabel), c);

            c = baseConstraints(row);
            c.gridx = 3;
            c.weightx = 1.0d;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(secondField, c);
        }

        private void addField(JPanel form, int row, String label, java.awt.Component field,
                              JButton button) {
            GridBagConstraints c = baseConstraints(row);
            c.gridx = 0;
            c.weightx = 0.0d;
            form.add(new JLabel(label), c);

            c = baseConstraints(row);
            c.gridx = 1;
            c.gridwidth = 2;
            c.weightx = 1.0d;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(field, c);

            c = baseConstraints(row);
            c.gridx = 3;
            c.gridwidth = 1;
            c.weightx = 0.0d;
            form.add(button, c);
        }

        private static GridBagConstraints baseConstraints(int row) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.insets = new Insets(3, 3, 3, 3);
            c.anchor = GridBagConstraints.WEST;
            return c;
        }

        private void chooseOutputDirectory() {
            chooseDirectory(outputDirField, "Choose capture output directory");
        }

        private void chooseDirectory(JTextField targetField, String title) {
            Path selectedPath = DirectoryChooser.chooseDirectory(this, title, targetField.getText());
            if (selectedPath != null) {
                targetField.setText(selectedPath.toString());
            }
        }

        private void startCapture() {
            if (captureWorker != null && !captureWorker.isDone()) {
                appendLog("[GUI][WARN] capture is already running");
                return;
            }

            CaptureOptions options;
            try {
                options = buildOptions();
            } catch (RuntimeException e) {
                setStatus("Input error: " + e.getMessage());
                appendLog("[GUI][ERROR] " + e.getMessage());
                return;
            }

            logArea.setText("");
            pendingPreviewImage.set(null);
            pendingSavedImage.set(null);
            lastPreviewQueuedNanos.set(0L);
            previewPanel.setImage(null);
            setStatus("Starting capture");
            updateRunningState(true);
            captureWorker = new CaptureWorker(options);
            captureWorker.execute();
        }

        private CaptureOptions buildOptions() {
            String outputDir = outputDirField.getText().trim();
            if (outputDir.isEmpty()) {
                throw new IllegalArgumentException("Output directory is required");
            }
            return new CaptureOptions(
                    Path.of(outputDir),
                    intValue(deviceSpinner),
                    intValue(widthSpinner),
                    intValue(heightSpinner),
                    doubleValue(fpsSpinner),
                    CaptureDefaults.DEFAULT_DURATION_SECONDS,
                    CaptureDefaults.DEFAULT_MAX_PAYLOADS,
                    intValue(captureDecodeWorkersSpinner),
                    longValue(statusIntervalSpinner),
                    CaptureDefaults.DEFAULT_SAME_SIGNAL_SECONDS,
                    resumeCheck.isSelected()
            );
        }

        private void stopCapture() {
            CaptureService service = activeService;
            if (service != null) {
                service.requestStop();
                setStatus("Stopping capture");
                appendLog("[GUI][INFO] stop requested");
            }
        }

        private void requestClose(Window window) {
            if (captureWorker != null && !captureWorker.isDone()) {
                closeAfterCapture = true;
                stopCapture();
                setStatus("Stopping capture before close");
                return;
            }
            if (decodeWorker != null && !decodeWorker.isDone()) {
                closeAfterDecode = true;
                decodeStatusLabel.setText("Decode is running; window will close when finished");
                return;
            }
            window.dispose();
        }

        private void listDevices() {
            listDevicesButton.setEnabled(false);
            appendLog("[GUI][INFO] probing capture devices");
            new SwingWorker<List<CaptureDeviceInfo>, Void>() {
                @Override
                protected List<CaptureDeviceInfo> doInBackground() {
                    return CaptureSupport.listDevices();
                }

                @Override
                protected void done() {
                    try {
                        List<CaptureDeviceInfo> devices = get();
                        if (devices.isEmpty()) {
                            appendLog("[GUI][INFO] no devices found");
                            return;
                        }
                        for (CaptureDeviceInfo device : devices) {
                            appendLog(String.format("[GUI][DEVICE] index=%d name=%s status=%s",
                                    device.index(),
                                    device.name(),
                                    device.available() ? "available" : "unavailable"));
                        }
                    } catch (Exception e) {
                        appendLog("[GUI][ERROR] device probe failed: " + e.getMessage());
                    } finally {
                        listDevicesButton.setEnabled(!isCaptureRunning());
                    }
                }
            }.execute();
        }

        private boolean isCaptureRunning() {
            return captureWorker != null && !captureWorker.isDone();
        }

        private boolean isDecodeRunning() {
            return decodeWorker != null && !decodeWorker.isDone();
        }

        private void updateRunningState(boolean running) {
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
            listDevicesButton.setEnabled(!running);
            outputBrowseButton.setEnabled(!running);
            outputDirField.setEnabled(!running);
            deviceSpinner.setEnabled(!running);
            widthSpinner.setEnabled(!running);
            heightSpinner.setEnabled(!running);
            fpsSpinner.setEnabled(!running);
            captureDecodeWorkersSpinner.setEnabled(!running);
            statusIntervalSpinner.setEnabled(!running);
            resumeCheck.setEnabled(!running);
            updateDecodeRunningState(isDecodeRunning());
        }

        private void updatePreviewEnabled() {
            previewEnabled = previewCheck.isSelected();
            if (!previewEnabled) {
                pendingPreviewImage.set(null);
                previewPanel.setImage(null);
            }
        }

        private void updatePreviewFps() {
            previewFps = Math.max(MIN_PREVIEW_FPS, Math.min(MAX_PREVIEW_FPS, doubleValue(previewFpsSpinner)));
        }

        private void updateDecodeRunningState(boolean running) {
            decodeInputDirField.setEnabled(!running);
            decodeOutputDirField.setEnabled(!running);
            decodeWorkersSpinner.setEnabled(!running);
            decodeInputBrowseButton.setEnabled(!running);
            decodeOutputBrowseButton.setEnabled(!running);
            decodeStartButton.setEnabled(!running && !isCaptureRunning());
        }

        private void setStatus(String status) {
            statusLabel.setText(status);
        }

        private void appendLog(String line) {
            logArea.append(line);
            logArea.append(System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        private void queuePreviewFrame(BufferedImage image) {
            if (!previewEnabled) {
                return;
            }
            long now = System.nanoTime();
            long minIntervalNanos = (long) (1_000_000_000d / previewFps);
            long lastQueued = lastPreviewQueuedNanos.get();
            if (now - lastQueued < minIntervalNanos) {
                return;
            }
            if (!lastPreviewQueuedNanos.compareAndSet(lastQueued, now)) {
                return;
            }
            pendingPreviewImage.set(image);
            if (previewUpdateScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::flushPreviewFrame);
            }
        }

        private void flushPreviewFrame() {
            if (!previewEnabled) {
                pendingPreviewImage.set(null);
                previewUpdateScheduled.set(false);
                previewPanel.setImage(null);
                return;
            }
            BufferedImage image = pendingPreviewImage.getAndSet(null);
            if (image != null) {
                previewPanel.setImage(image);
            }
            previewUpdateScheduled.set(false);
            if (pendingPreviewImage.get() != null && previewUpdateScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::flushPreviewFrame);
            }
        }

        private void queueSavedImage(Path imagePath, int savedCount) {
            pendingSavedImage.set(new SavedImageUpdate(imagePath, savedCount));
            if (savedImageUpdateScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::flushSavedImage);
            }
        }

        private void flushSavedImage() {
            SavedImageUpdate update = pendingSavedImage.getAndSet(null);
            if (update != null) {
                appendLog("[CAPTURE][SAVE] " + update.imagePath().getFileName());
                setStatus("Saved " + update.savedCount() + " image(s)");
            }
            savedImageUpdateScheduled.set(false);
            if (pendingSavedImage.get() != null && savedImageUpdateScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::flushSavedImage);
            }
        }

        private void startDecode() {
            if (isCaptureRunning()) {
                appendDecodeLog("[GUI][WARN] decode is disabled while capture is running");
                return;
            }
            if (decodeWorker != null && !decodeWorker.isDone()) {
                appendDecodeLog("[GUI][WARN] decode is already running");
                return;
            }

            String inputDir = decodeInputDirField.getText().trim();
            String outputDir = decodeOutputDirField.getText().trim();
            if (inputDir.isEmpty() || outputDir.isEmpty()) {
                decodeStatusLabel.setText("Input and output directories are required");
                appendDecodeLog("[GUI][ERROR] Input and output directories are required");
                return;
            }

            decodeLogArea.setText("");
            decodeStatusLabel.setText("Starting decode");
            updateDecodeRunningState(true);
            decodeWorker = new DecodeWorker(
                    Path.of(inputDir),
                    Path.of(outputDir),
                    intValue(decodeWorkersSpinner)
            );
            decodeWorker.execute();
        }

        private void appendDecodeLog(String line) {
            decodeLogArea.append(line);
            decodeLogArea.append(System.lineSeparator());
            decodeLogArea.setCaretPosition(decodeLogArea.getDocument().getLength());
        }

        private record SavedImageUpdate(Path imagePath, int savedCount) {
        }

        private final class CaptureWorker extends SwingWorker<CaptureSummary, Void> {
            private final CaptureOptions options;

            private CaptureWorker(CaptureOptions options) {
                this.options = options;
            }

            @Override
            protected CaptureSummary doInBackground() throws Exception {
                CaptureService service = new CaptureService(options, new CaptureListener() {
                    @Override
                    public void onLog(String line) {
                        SwingUtilities.invokeLater(() -> appendLog(line));
                    }

                    @Override
                    public void onPreviewFrame(BufferedImage image) {
                        queuePreviewFrame(image);
                    }

                    @Override
                    public void onStatus(CaptureStatus status) {
                        SwingUtilities.invokeLater(() -> setStatus(formatStatus(status)));
                    }

                    @Override
                    public void onSavedImage(Path imagePath, String payload, int savedCount) {
                        queueSavedImage(imagePath, savedCount);
                    }

                    @Override
                    public void onFinished(CaptureSummary summary) {
                        SwingUtilities.invokeLater(() -> setStatus(formatSummary(summary)));
                    }
                });
                activeService = service;
                return service.run();
            }

            @Override
            protected void done() {
                activeService = null;
                updateRunningState(false);
                try {
                    CaptureSummary summary = get();
                    appendLog("[GUI][DONE] " + formatSummary(summary));
                    setStatus(formatSummary(summary));
                } catch (Exception e) {
                    appendLog("[GUI][ERROR] " + e.getMessage());
                    setStatus("Capture failed");
                }
                if (closeAfterCapture) {
                    Window window = SwingUtilities.getWindowAncestor(ReceiverPanel.this);
                    if (window != null) {
                        window.dispose();
                    }
                }
            }
        }

        private final class DecodeWorker extends SwingWorker<DecodeWorkflow.Result, Void> {
            private final Path sourceDir;
            private final Path outputDir;
            private final int workers;

            private DecodeWorker(Path sourceDir, Path outputDir, int workers) {
                this.sourceDir = sourceDir;
                this.outputDir = outputDir;
                this.workers = workers;
            }

            @Override
            protected DecodeWorkflow.Result doInBackground() throws Exception {
                return new DecodeWorkflow(workers).decode(
                        sourceDir,
                        outputDir,
                        line -> SwingUtilities.invokeLater(() -> appendDecodeLog(line))
                );
            }

            @Override
            protected void done() {
                updateDecodeRunningState(false);
                try {
                    DecodeWorkflow.Result result = get();
                    decodeStatusLabel.setText(formatDecodeResult(result));
                    appendDecodeLog("[GUI][DONE] " + formatDecodeResult(result));
                } catch (Exception e) {
                    decodeStatusLabel.setText("Decode failed");
                    appendDecodeLog("[GUI][ERROR] " + e.getMessage());
                }
                if (closeAfterDecode) {
                    Window window = SwingUtilities.getWindowAncestor(ReceiverPanel.this);
                    if (window != null) {
                        window.dispose();
                    }
                }
            }
        }

        private static String formatStatus(CaptureStatus status) {
            return String.format("Frames %d, analyzed %d, decoded %d, saved %d, failures %d",
                    status.totalFrames(),
                    status.analyzedFrames(),
                    status.decodedFrames(),
                    status.savedImages(),
                    status.decodeFailures());
        }

        private static String formatSummary(CaptureSummary summary) {
            return String.format("Finished: %s, saved %d image(s), unique payloads %d",
                    summary.stopReason(),
                    summary.savedImages(),
                    summary.uniquePayloads());
        }

        private static String formatDecodeResult(DecodeWorkflow.Result result) {
            if (result.status() != DecodeWorkflow.Status.COMPLETED) {
                return result.message();
            }
            return String.format("Decoded %d QR image(s): restored %d, incomplete %d, hash mismatch %d, errors %d",
                    result.qrFileCount(),
                    result.restoredCount(),
                    result.incompleteCount(),
                    result.hashMismatchCount(),
                    result.decodeErrorCount());
        }

        private static int intValue(JSpinner spinner) {
            return ((Number) spinner.getValue()).intValue();
        }

        private static long longValue(JSpinner spinner) {
            return ((Number) spinner.getValue()).longValue();
        }

        private static double doubleValue(JSpinner spinner) {
            return ((Number) spinner.getValue()).doubleValue();
        }

        private static JSpinner intSpinner(int value, int min, int max, int step) {
            return new JSpinner(new SpinnerNumberModel(value, min, max, step));
        }

        private static JSpinner longSpinner(long value, long min, long max, long step) {
            return new JSpinner(new SpinnerNumberModel(value, min, max, step));
        }

        private static JSpinner doubleSpinner(double value, double min, double max, double step) {
            return new JSpinner(new SpinnerNumberModel(value, min, max, step));
        }
    }

    private static final class PreviewPanel extends JPanel {
        private BufferedImage image;

        PreviewPanel() {
            setPreferredSize(new Dimension(640, 360));
            setMinimumSize(new Dimension(320, 180));
            setBackground(Color.BLACK);
            setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        }

        void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (image == null) {
                graphics.setColor(Color.LIGHT_GRAY);
                graphics.drawString("Preview", 16, 24);
                return;
            }

            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int panelWidth = getWidth();
                int panelHeight = getHeight();
                double scale = Math.min(
                        panelWidth / (double) image.getWidth(),
                        panelHeight / (double) image.getHeight()
                );
                int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
                int x = (panelWidth - drawWidth) / 2;
                int y = (panelHeight - drawHeight) / 2;
                g.drawImage(image, x, y, drawWidth, drawHeight, null);
            } finally {
                g.dispose();
            }
        }
    }
}
