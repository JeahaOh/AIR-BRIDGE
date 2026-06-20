package airbridge.sender.gui;

import airbridge.common.AppPaths;
import airbridge.common.gui.DirectoryChooser;
import airbridge.sender.EncodeWorkflow;
import airbridge.slide.SlideApp;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SenderGui {

    private static void applySystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keep the default look and feel
        }
    }

    private SenderGui() {
    }

    public static JFrame createFrame() {
        applySystemLookAndFeel();
        JFrame frame = new JFrame("air-bridge sender");
        SenderPanel panel = new SenderPanel();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setContentPane(panel);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                panel.requestClose(frame);
            }
        });
        frame.setMinimumSize(new java.awt.Dimension(920, 620));
        frame.setSize(1100, 720);
        frame.setLocationByPlatform(true);
        return frame;
    }

    private static final class SenderPanel extends JPanel {
        private final JTextField sourceDirField = new JTextField(AppPaths.sourceDir().toString(), 36);
        private final JTextField outputDirField = new JTextField(AppPaths.encodedDir().toString(), 36);
        private final JTextField encodeRootField = new JTextField(36);
        private final JSpinner chunkDataSizeSpinner = intSpinner(EncodeWorkflow.DEFAULT_CHUNK_DATA_SIZE, 1, Integer.MAX_VALUE, 100);
        private final JSpinner qrImageSizeSpinner = intSpinner(EncodeWorkflow.DEFAULT_QR_IMAGE_SIZE, 1, 16_384, 10);
        private final JSpinner labelHeightSpinner = intSpinner(EncodeWorkflow.DEFAULT_LABEL_HEIGHT, 0, 2_000, 10);
        private final JSpinner filesPerFolderSpinner = intSpinner(EncodeWorkflow.DEFAULT_FILES_PER_FOLDER, 1, Integer.MAX_VALUE, 10);
        private final JSpinner encodeWorkersSpinner = intSpinner(EncodeWorkflow.DEFAULT_ENCODE_WORKERS, 1, 256, 1);
        private final JSpinner repairOverheadSpinner = doubleSpinner(EncodeWorkflow.DEFAULT_REPAIR_OVERHEAD, 0.0, 5.0, 0.1);
        private final JComboBox<ErrorCorrectionLevel> errorLevelCombo = new JComboBox<>(ErrorCorrectionLevel.values());
        private final JTextField targetExtensionsField = new JTextField(String.join(",", EncodeWorkflow.DEFAULT_TARGET_EXTENSIONS), 36);
        private final JTextField skipDirsField = new JTextField(String.join(",", EncodeWorkflow.DEFAULT_SKIP_DIRS), 36);
        private final JTextField excludePathsField = new JTextField(36);
        private final JCheckBox convertXlsxToCsvCheck = new JCheckBox("XLSX to CSV");
        private final JCheckBox convertOfficeToTextCheck = new JCheckBox("Office to text");
        private final JCheckBox folderStructureCheck = new JCheckBox("Folder structure", EncodeWorkflow.DEFAULT_FOLDER_STRUCTURE);
        private final JButton encodeButton = new JButton("Encode");
        private final JButton stopEncodeButton = new JButton("Stop");
        private final JButton slideButton = new JButton("Slide");
        private final JButton sourceBrowseButton = new JButton("Browse");
        private final JButton outputBrowseButton = new JButton("Browse");
        private final JButton rootBrowseButton = new JButton("Browse");
        private final JLabel statusLabel = new JLabel("Idle");
        private final JTextArea logArea = new JTextArea();
        private final JTextField slideInputDirField = new JTextField(AppPaths.encodedDir().toString(), 36);
        private final JButton useEncodeOutputButton = new JButton("Use encode output");
        private final JButton launchSlideButton = new JButton("Launch slide");
        private final JLabel slideStatusLabel = new JLabel("Idle");

        private EncodeWorker encodeWorker;
        private final AtomicBoolean encodeStopRequested = new AtomicBoolean();
        private boolean closeAfterEncode;

        SenderPanel() {
            super(new BorderLayout(8, 8));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            errorLevelCombo.setSelectedItem(EncodeWorkflow.DEFAULT_QR_ERROR_LEVEL);
            add(buildTabs(), BorderLayout.CENTER);
        }

        private JTabbedPane buildTabs() {
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Encode", buildEncodePanel());
            tabs.addTab("Slide", buildSlidePanel());
            return tabs;
        }

        private JPanel buildEncodePanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.add(buildEncodeForm(), BorderLayout.NORTH);

            logArea.setEditable(false);
            logArea.setLineWrap(false);
            panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
            panel.add(statusLabel, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildEncodeForm() {
            JPanel wrapper = new JPanel(new BorderLayout(8, 8));
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createTitledBorder("Encode"));

            sourceBrowseButton.addActionListener(event -> chooseDirectory(sourceDirField, "Choose source directory"));
            outputBrowseButton.addActionListener(event -> chooseDirectory(outputDirField, "Choose QR output directory"));
            rootBrowseButton.addActionListener(event -> chooseDirectory(encodeRootField, "Choose encode root directory"));

            int row = 0;
            addField(form, row++, "Input", sourceDirField, sourceBrowseButton);
            addField(form, row++, "Output", outputDirField, outputBrowseButton);
            addField(form, row++, "Encode root", encodeRootField, rootBrowseButton);
            addField(form, row++, "Chunk size", chunkDataSizeSpinner, qrImageSizeSpinner, "QR size");
            addField(form, row++, "Label height", labelHeightSpinner, filesPerFolderSpinner, "Files/folder");
            addField(form, row++, "Encode workers", encodeWorkersSpinner, errorLevelCombo, "Error");
            addField(form, row++, "Repair overhead", repairOverheadSpinner, new JLabel(), "");
            addField(form, row++, "Targets", targetExtensionsField, new JLabel(), "");
            addField(form, row++, "Skip dirs", skipDirsField, new JLabel(), "");
            addField(form, row++, "Exclude", excludePathsField, new JLabel(), "");
            addField(form, row, "Options", convertXlsxToCsvCheck, convertOfficeToTextCheck, "");

            JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            toggles.add(folderStructureCheck);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            encodeButton.addActionListener(event -> startEncode());
            stopEncodeButton.addActionListener(event -> stopEncode());
            slideButton.addActionListener(event -> launchSlide());
            buttons.add(encodeButton);
            buttons.add(stopEncodeButton);
            buttons.add(slideButton);

            JPanel lower = new JPanel(new BorderLayout(8, 8));
            lower.add(toggles, BorderLayout.CENTER);
            lower.add(buttons, BorderLayout.SOUTH);

            wrapper.add(form, BorderLayout.CENTER);
            wrapper.add(lower, BorderLayout.SOUTH);
            updateEncodeRunningState(false);
            return wrapper;
        }

        private JPanel buildSlidePanel() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.add(buildSlideForm(), BorderLayout.NORTH);
            panel.add(slideStatusLabel, BorderLayout.SOUTH);
            return panel;
        }

        private JPanel buildSlideForm() {
            JPanel wrapper = new JPanel(new BorderLayout(8, 8));
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createTitledBorder("Slide"));

            JButton browseButton = new JButton("Browse");
            browseButton.addActionListener(event -> chooseDirectory(slideInputDirField, "Choose slide input directory"));

            addField(form, 0, "Input", slideInputDirField, browseButton);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            useEncodeOutputButton.addActionListener(event -> useEncodeOutputForSlide());
            launchSlideButton.addActionListener(event -> launchSlideFromSlideTab());
            buttons.add(useEncodeOutputButton);
            buttons.add(launchSlideButton);

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

        private void chooseDirectory(JTextField targetField, String title) {
            Path selectedPath = DirectoryChooser.chooseDirectory(this, title, targetField.getText());
            if (selectedPath != null) {
                targetField.setText(selectedPath.toString());
            }
        }

        private void startEncode() {
            if (encodeWorker != null && !encodeWorker.isDone()) {
                appendLog("[GUI][WARN] encode is already running");
                return;
            }

            EncodeWorkflow.Request request;
            try {
                request = buildRequest();
            } catch (RuntimeException e) {
                statusLabel.setText("Input error: " + e.getMessage());
                appendLog("[GUI][ERROR] " + e.getMessage());
                return;
            }

            logArea.setText("");
            statusLabel.setText("Starting encode");
            encodeStopRequested.set(false);
            updateEncodeRunningState(true);
            encodeWorker = new EncodeWorker(request);
            encodeWorker.execute();
        }

        private void stopEncode() {
            EncodeWorker worker = encodeWorker;
            if (worker == null || worker.isDone()) {
                return;
            }
            encodeStopRequested.set(true);
            worker.cancel(true);
            statusLabel.setText("Stopping encode");
            appendLog("[GUI][WARN] stop requested");
        }

        private void updateEncodeRunningState(boolean running) {
            sourceDirField.setEnabled(!running);
            outputDirField.setEnabled(!running);
            encodeRootField.setEnabled(!running);
            chunkDataSizeSpinner.setEnabled(!running);
            qrImageSizeSpinner.setEnabled(!running);
            labelHeightSpinner.setEnabled(!running);
            filesPerFolderSpinner.setEnabled(!running);
            encodeWorkersSpinner.setEnabled(!running);
            repairOverheadSpinner.setEnabled(!running);
            errorLevelCombo.setEnabled(!running);
            targetExtensionsField.setEnabled(!running);
            skipDirsField.setEnabled(!running);
            excludePathsField.setEnabled(!running);
            convertXlsxToCsvCheck.setEnabled(!running);
            convertOfficeToTextCheck.setEnabled(!running);
            folderStructureCheck.setEnabled(!running);
            sourceBrowseButton.setEnabled(!running);
            outputBrowseButton.setEnabled(!running);
            rootBrowseButton.setEnabled(!running);
            encodeButton.setEnabled(!running);
            stopEncodeButton.setEnabled(running);
        }

        private EncodeWorkflow.Request buildRequest() {
            String sourceDir = sourceDirField.getText().trim();
            String outputDir = outputDirField.getText().trim();
            if (sourceDir.isEmpty() || outputDir.isEmpty()) {
                throw new IllegalArgumentException("Input and output directories are required");
            }

            String encodeRoot = encodeRootField.getText().trim();
            return new EncodeWorkflow.Request(
                    Path.of(sourceDir),
                    Path.of(outputDir),
                    encodeRoot.isEmpty() ? null : Path.of(encodeRoot),
                    intValue(chunkDataSizeSpinner),
                    intValue(qrImageSizeSpinner),
                    (ErrorCorrectionLevel) errorLevelCombo.getSelectedItem(),
                    intValue(labelHeightSpinner),
                    convertXlsxToCsvCheck.isSelected(),
                    convertOfficeToTextCheck.isSelected(),
                    folderStructureCheck.isSelected(),
                    intValue(filesPerFolderSpinner),
                    intValue(encodeWorkersSpinner),
                    doubleValue(repairOverheadSpinner),
                    parseCsv(targetExtensionsField.getText(), true),
                    parseCsv(skipDirsField.getText(), true),
                    parseCsv(excludePathsField.getText(), false)
            );
        }

        private void launchSlide() {
            String outputDir = outputDirField.getText().trim();
            if (outputDir.isEmpty()) {
                openSlide(null);
                return;
            }
            slideInputDirField.setText(outputDir);
            openSlide(Path.of(outputDir));
        }

        private void useEncodeOutputForSlide() {
            String outputDir = outputDirField.getText().trim();
            if (outputDir.isEmpty()) {
                slideStatusLabel.setText("Encode output directory is empty");
                return;
            }
            slideInputDirField.setText(outputDir);
            slideStatusLabel.setText("Slide input set from encode output");
        }

        private void launchSlideFromSlideTab() {
            String inputDir = slideInputDirField.getText().trim();
            if (inputDir.isEmpty()) {
                openSlide(null);
                return;
            }
            openSlide(Path.of(inputDir));
        }

        private void openSlide(Path inputDir) {
            if (inputDir == null) {
                slideStatusLabel.setText("Opening slide player");
                SwingUtilities.invokeLater(() -> SlideApp.launch(new String[0]));
                return;
            }
            slideStatusLabel.setText("Opening slide player: " + inputDir);
            SwingUtilities.invokeLater(() -> SlideApp.launch(inputDir));
        }

        private void requestClose(Window window) {
            if (encodeWorker != null && !encodeWorker.isDone()) {
                closeAfterEncode = true;
                stopEncode();
                statusLabel.setText("Stopping encode before close");
                return;
            }
            window.dispose();
        }

        private void appendLog(String line) {
            logArea.append(line);
            logArea.append(System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }

        private final class EncodeWorker extends SwingWorker<EncodeWorkflow.Result, Void> {
            private final EncodeWorkflow.Request request;

            private EncodeWorker(EncodeWorkflow.Request request) {
                this.request = request;
            }

            @Override
            protected EncodeWorkflow.Result doInBackground() throws Exception {
                return new EncodeWorkflow().encode(
                        request,
                        line -> SwingUtilities.invokeLater(() -> appendLog(line)),
                        encodeStopRequested::get
                );
            }

            @Override
            protected void done() {
                updateEncodeRunningState(false);
                try {
                    EncodeWorkflow.Result result = get();
                    statusLabel.setText(formatEncodeResult(result));
                    appendLog("[GUI][DONE] " + formatEncodeResult(result));
                } catch (CancellationException e) {
                    statusLabel.setText("Encode stopped");
                    appendLog("[GUI][CANCELLED] encode stopped");
                } catch (Exception e) {
                    statusLabel.setText("Encode failed");
                    appendLog("[GUI][ERROR] " + e.getMessage());
                }
                if (closeAfterEncode) {
                    Window window = SwingUtilities.getWindowAncestor(SenderPanel.this);
                    if (window != null) {
                        window.dispose();
                    }
                }
            }
        }

        private static String formatEncodeResult(EncodeWorkflow.Result result) {
            if (result.status() != EncodeWorkflow.Status.COMPLETED) {
                return result.message();
            }
            return String.format("Encoded %d file(s) into %d QR image(s)",
                    result.totalFileCount(),
                    result.totalQrCount());
        }

        private static List<String> parseCsv(String value, boolean normalizeLowerCase) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(item -> normalizeLowerCase ? item.toLowerCase(Locale.ROOT) : item)
                    .toList();
        }

        private static int intValue(JSpinner spinner) {
            return ((Number) spinner.getValue()).intValue();
        }

        private static double doubleValue(JSpinner spinner) {
            return ((Number) spinner.getValue()).doubleValue();
        }

        private static JSpinner intSpinner(int value, int min, int max, int step) {
            return new JSpinner(new SpinnerNumberModel(value, min, max, step));
        }

        private static JSpinner doubleSpinner(double value, double min, double max, double step) {
            return new JSpinner(new SpinnerNumberModel(value, min, max, step));
        }
    }
}
