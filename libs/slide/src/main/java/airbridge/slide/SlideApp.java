package airbridge.slide;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.ChangeListener;
import javax.swing.JFormattedTextField;
import javax.swing.text.DefaultFormatter;
import javax.swing.text.NumberFormatter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import airbridge.common.VersionSupport;

public class SlideApp {
    public static void launch(String[] args) {
        if (args != null && Arrays.stream(args).anyMatch(arg -> "--help".equals(arg) || "-h".equals(arg))) {
            System.out.println("Usage: sender slide");
            System.out.println("Launch the Swing slide player bundled inside the sender app.");
            return;
        }
        main(args);
    }

    private static final Color COLOR_BG = Color.BLACK;
    private static final Color COLOR_BAR = new Color(24, 24, 24);
    private static final Color COLOR_PANEL = new Color(18, 18, 18);
    private static final Color COLOR_STATUS = new Color(28, 28, 28);
    private static final Color COLOR_TEXT = new Color(245, 245, 245);
    private static final Color COLOR_ACCENT = new Color(181, 122, 63);
    private static final Font FONT_UI = new Font("Dialog", Font.PLAIN, 13);
    private static final Font FONT_UI_BOLD = new Font("Dialog", Font.BOLD, 13);

    private final List<Path> imageFiles = new ArrayList<>();
    private final Map<Path, BufferedImage> imageCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
            return size() > SlideDefaults.MAX_CACHE_SIZE;
        }
    };
    private final Map<Path, Integer> loadingImages = new HashMap<>();
    private final Map<Path, DefaultMutableTreeNode> treeNodeIndex = new LinkedHashMap<>();
    private final AtomicInteger imageLoadGeneration = new AtomicInteger();
    private final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread thread = new Thread(r, "slide-image-loader");
                thread.setDaemon(true);
                return thread;
            }
    );

    private JFrame frame;
    private JPanel topBar;
    private JPanel rightPanel;
    private JTextField inputDirField;
    private JSpinner pageDisplaySpinner;
    private JSpinner blackFrameSpinner;
    private JSpinner loopCountSpinner;
    private JCheckBox fullScreenCheckBox;
    private JCheckBox alwaysOnTopCheckBox;
    private JButton playPauseButton;
    private JLabel imageCountLabel;
    private JLabel statusLabel;
    private SlideCanvas slideCanvas;
    private JTree imageTree;
    private JSplitPane splitPane;
    private JPanel centerHost;
    private JComponent centerView;
    private Timer displayTimer;
    private Timer blackTimer;
    private Timer closeTimer;
    private Timer foregroundRecoveryTimer;
    private ScheduledExecutorService mouseJiggleExecutor;
    private Rectangle windowedBounds;
    private boolean fullScreenActive;
    private boolean playing;
    private boolean showingBlackFrame;
    private boolean selectionSyncing;
    private boolean postFinishBlackout;
    private boolean controlsVisible = true;
    private int currentIndex;
    private int completedLoops;
    private int lastNonBlackIndex = -1;
    private int savedDividerLocation = 1080;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // fall back to default look and feel
            }
            new SlideApp().start();
        });
    }

    private void start() {
        frame = new JFrame(VersionSupport.displayVersion("air-bridge slide"));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestSafeExit();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                scheduleForegroundRecovery();
            }

            @Override
            public void windowIconified(WindowEvent e) {
                scheduleForegroundRecovery();
            }
        });

        topBar = buildTopBar();
        rightPanel = buildRightPanel();
        slideCanvas = new SlideCanvas();
        centerView = wrapCenter(slideCanvas);
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerView, rightPanel);
        splitPane.setResizeWeight(1.0d);
        splitPane.setDividerLocation(savedDividerLocation);
        splitPane.setDividerSize(SlideDefaults.VISIBLE_DIVIDER_SIZE);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setBackground(COLOR_BG);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(COLOR_BG);
        root.add(topBar, BorderLayout.NORTH);
        centerHost = new JPanel(new BorderLayout());
        centerHost.setBackground(COLOR_BG);
        centerHost.add(splitPane, BorderLayout.CENTER);
        root.add(centerHost, BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        bindShortcuts(root);

        frame.setContentPane(root);
        frame.setSize(1500, 920);
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        alwaysOnTopCheckBox.setSelected(true);
        frame.setVisible(true);
        printShortcutHelp(System.out);
        SwingUtilities.invokeLater(() -> {
            setFullScreen(true);
            focusMainWindow();
        });
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(COLOR_BAR);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        inputDirField = new JTextField(32);
        inputDirField.setFont(FONT_UI);
        inputDirField.setEditable(false);
        inputDirField.setFocusable(false);

        JButton browseButton = new JButton("Browse");
        browseButton.addActionListener(event -> chooseDirectory());

        JButton reloadButton = new JButton("Reload");
        reloadButton.addActionListener(event -> loadImagesFromInput());

        pageDisplaySpinner = new JSpinner(new SpinnerNumberModel(SlideDefaults.DEFAULT_PAGE_DISPLAY_MS, 50, 10_000, 50));
        blackFrameSpinner = new JSpinner(new SpinnerNumberModel(SlideDefaults.DEFAULT_BLACK_FRAME_MS, 0, 2_000, 10));
        loopCountSpinner = new JSpinner(new SpinnerNumberModel(SlideDefaults.DEFAULT_LOOP_COUNT, 0, 9_999, 1));
        installSpinnerBehavior(pageDisplaySpinner);
        installSpinnerBehavior(blackFrameSpinner);
        installSpinnerBehavior(loopCountSpinner);

        fullScreenCheckBox = new JCheckBox("Full Screen", true);
        fullScreenCheckBox.addActionListener(event -> setFullScreen(fullScreenCheckBox.isSelected()));

        alwaysOnTopCheckBox = new JCheckBox("Always On Top", true);
        alwaysOnTopCheckBox.addActionListener(event -> frame.setAlwaysOnTop(alwaysOnTopCheckBox.isSelected()));

        playPauseButton = new JButton("Play");
        playPauseButton.addActionListener(event -> onPlayPause());

        imageCountLabel = new JLabel("Images: 0");

        bar.add(createBarLabel("Input"));
        bar.add(inputDirField);
        bar.add(browseButton);
        bar.add(reloadButton);
        bar.add(createBarLabel("Page(ms)"));
        bar.add(pageDisplaySpinner);
        bar.add(createBarLabel("Black(ms)"));
        bar.add(blackFrameSpinner);
        bar.add(createBarLabel("Loop"));
        bar.add(loopCountSpinner);
        bar.add(styleCheckBox(fullScreenCheckBox));
        bar.add(styleCheckBox(alwaysOnTopCheckBox));
        bar.add(playPauseButton);
        bar.add(styleLabel(imageCountLabel));
        return bar;
    }

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(COLOR_PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(360, 600));
        panel.setMinimumSize(new Dimension(320, 200));

        JLabel header = new JLabel("Pages");
        header.setForeground(COLOR_TEXT);
        header.setFont(FONT_UI_BOLD);
        panel.add(header, BorderLayout.NORTH);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Pages");
        imageTree = new JTree(new DefaultTreeModel(root));
        imageTree.setRootVisible(false);
        imageTree.setShowsRootHandles(true);
        imageTree.setBackground(COLOR_PANEL);
        imageTree.setForeground(COLOR_TEXT);
        imageTree.setFont(FONT_UI);
        imageTree.setRowHeight(22);
        imageTree.setCellRenderer(new SlideTreeCellRenderer());
        imageTree.addTreeSelectionListener(this::onTreeSelection);

        JScrollPane treeScrollPane = new JScrollPane(imageTree);
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder());
        treeScrollPane.getViewport().setBackground(COLOR_PANEL);
        panel.add(treeScrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(COLOR_STATUS);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        statusLabel = new JLabel("[PAUSE] no file");
        statusLabel.setForeground(COLOR_TEXT);
        statusLabel.setFont(FONT_UI);
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    private JComponent wrapCenter(JComponent component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(COLOR_BG);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private JLabel createBarLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(COLOR_TEXT);
        label.setFont(FONT_UI_BOLD);
        return label;
    }

    private JLabel styleLabel(JLabel label) {
        label.setForeground(COLOR_TEXT);
        label.setFont(FONT_UI);
        return label;
    }

    private JCheckBox styleCheckBox(JCheckBox checkBox) {
        checkBox.setBackground(COLOR_BAR);
        checkBox.setForeground(COLOR_TEXT);
        checkBox.setFont(FONT_UI);
        return checkBox;
    }

    private void bindShortcuts(JComponent root) {
        bindAction(root, "SPACE", "togglePlayPause", this::onPlayPause);
        bindAction(root, "LEFT", "showPreviousImage", () -> navigateRelative(-1));
        bindAction(root, "RIGHT", "showNextImage", () -> navigateRelative(1));
        bindAction(root, "PAGE_UP", "jumpBackwardHundred", () -> navigateRelative(-100));
        bindAction(root, "PAGE_DOWN", "jumpForwardHundred", () -> navigateRelative(100));
        bindAction(root, "F", "toggleFullScreen", this::toggleFullScreen);
        bindAction(root, "T", "togglePanel", this::togglePanel);
        bindAction(root, "Q", "exitSlideAlt", this::requestSafeExit);
    }

    private void bindAction(JComponent root, String key, String actionName, Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), actionName);
        root.getActionMap().put(actionName, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private void chooseDirectory() {
        boolean wasFullScreen = isFullScreenActive();
        boolean wasAlwaysOnTop = frame.isAlwaysOnTop();
        if (wasAlwaysOnTop) {
            frame.setAlwaysOnTop(false);
        }
        if (wasFullScreen) {
            setFullScreen(false);
        }

        Path selectedPath = SlideDirectoryChooser.chooseDirectory(frame, inputDirField.getText());

        if (wasAlwaysOnTop) {
            frame.setAlwaysOnTop(true);
        }
        if (wasFullScreen) {
            setFullScreen(true);
        }

        if (selectedPath != null) {
            inputDirField.setText(selectedPath.toString());
            loadImagesFromInput();
        }
        focusMainWindow();
    }

    private void loadImagesFromInput() {
        pausePlayback(false);
        imageLoadGeneration.incrementAndGet();
        imageFiles.clear();
        imageCache.clear();
        loadingImages.clear();
        treeNodeIndex.clear();
        slideCanvas.setImage(null);
        showingBlackFrame = false;
        postFinishBlackout = false;
        currentIndex = 0;
        completedLoops = 0;
        lastNonBlackIndex = -1;

        Path inputDir = SlideDirectoryChooser.parsePath(inputDirField.getText());
        if (inputDir == null || !Files.isDirectory(inputDir)) {
            imageTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Pages")));
            updateImageCountLabel();
            setStatusText("[PAUSE] input directory not found");
            return;
        }

        try {
            SlideImageCatalog.SlideImageCatalogResult catalog = SlideImageCatalog.load(inputDir);
            imageFiles.addAll(catalog.imageFiles());
            treeNodeIndex.putAll(catalog.treeNodeIndex());
            imageTree.setModel(new DefaultTreeModel(catalog.treeRoot()));
        } catch (Exception e) {
            imageTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Pages")));
            updateImageCountLabel();
            setStatusText("[PAUSE] failed to load images: " + e.getMessage());
            return;
        }
        expandTree();
        updateImageCountLabel();

        if (imageFiles.isEmpty()) {
            setStatusText("[PAUSE] no supported images found");
            return;
        }

        preloadInitial();
        updateImageCountLabel();
        showCurrentImage();
        System.out.printf("[SLIDE] preload initial=%d cache=%d%n", Math.min(SlideDefaults.PRELOAD_COUNT, imageFiles.size()), imageCache.size());
    }

    private void onPlayPause() {
        if (playing) {
            pausePlayback(true);
            return;
        }

        if (imageFiles.isEmpty()) {
            loadImagesFromInput();
            if (imageFiles.isEmpty()) {
                return;
            }
        }

        startPlayback();
    }

    private void startPlayback() {
        postFinishBlackout = false;
        cancelCloseTimer();
        playing = true;
        startMouseJiggle();
        playPauseButton.setText("Pause");
        showCurrentImage();
        scheduleDisplayPhase();
        focusMainWindow();
    }

    private void pausePlayback(boolean keepImage) {
        playing = false;
        stopMouseJiggle();
        cancelTimers();
        cancelCloseTimer();
        playPauseButton.setText("Play");
        if (!postFinishBlackout) {
            showingBlackFrame = false;
        }
        if (keepImage && !imageFiles.isEmpty()) {
            showCurrentImage();
        }
        focusMainWindow();
    }

    private void scheduleDisplayPhase() {
        cancelTimers();
        displayTimer = new Timer(getSpinnerValue(pageDisplaySpinner), event -> {
            if (!playing) {
                return;
            }
            if (getSpinnerValue(blackFrameSpinner) > 0 && imageFiles.size() > 1) {
                showBlackFrame();
                scheduleBlackPhase();
            } else {
                advanceToNext();
            }
        });
        displayTimer.setRepeats(false);
        displayTimer.start();
    }

    private void scheduleBlackPhase() {
        blackTimer = new Timer(getSpinnerValue(blackFrameSpinner), event -> {
            if (!playing) {
                return;
            }
            advanceToNext();
        });
        blackTimer.setRepeats(false);
        blackTimer.start();
    }

    private void advanceToNext() {
        showingBlackFrame = false;
        currentIndex++;
        if (currentIndex >= imageFiles.size()) {
            completedLoops++;
            int loopLimit = getSpinnerValue(loopCountSpinner);
            if (loopLimit > 0 && completedLoops >= loopLimit) {
                currentIndex = Math.max(0, imageFiles.size() - 1);
                enterPostFinishBlackout();
                return;
            }
            currentIndex = 0;
        }
        showCurrentImage();
        scheduleDisplayPhase();
    }

    private void navigateRelative(int delta) {
        if (imageFiles.isEmpty()) {
            return;
        }
        boolean wasShowingBlackFrame = showingBlackFrame;
        postFinishBlackout = false;
        int targetIndex = Math.max(0, Math.min(imageFiles.size() - 1, currentIndex + delta));
        if (targetIndex == currentIndex && !wasShowingBlackFrame) {
            focusMainWindow();
            return;
        }
        showingBlackFrame = false;
        currentIndex = targetIndex;
        showCurrentImage();
        if (playing) {
            scheduleDisplayPhase();
        }
        focusMainWindow();
    }

    private void showCurrentImage() {
        if (imageFiles.isEmpty()) {
            return;
        }
        int generation = imageLoadGeneration.get();
        Path current = imageFiles.get(currentIndex);
        BufferedImage currentImage = getCachedImage(current);
        if (currentImage != null || isImageCached(current)) {
            slideCanvas.setImage(currentImage);
        } else {
            queueImageLoad(current, generation, true);
            if (slideCanvas.getImage() == null) {
                slideCanvas.setImage(null);
            }
        }
        showingBlackFrame = false;
        lastNonBlackIndex = currentIndex;
        syncSelection(currentIndex);
        prefetchAround(currentIndex);
        setStatusText(buildStatusPrefix(playing ? "PLAY" : "PAUSE") + currentRelativePath());
    }

    private void showBlackFrame() {
        slideCanvas.setImage(null);
        showingBlackFrame = true;
        setStatusText(buildStatusPrefix(playing ? "PLAY" : "PAUSE") + currentRelativePath());
    }

    private void onTreeSelection(TreeSelectionEvent event) {
        if (selectionSyncing) {
            return;
        }
        TreePath path = event.getNewLeadSelectionPath();
        if (path == null) {
            return;
        }
        Object nodeObj = path.getLastPathComponent();
        if (!(nodeObj instanceof DefaultMutableTreeNode node)) {
            return;
        }
        Object userObject = node.getUserObject();
        if (!(userObject instanceof Path selectedPath) || !Files.isRegularFile(selectedPath)) {
            return;
        }

        int selectedIndex = imageFiles.indexOf(selectedPath);
        if (selectedIndex < 0) {
            return;
        }

        currentIndex = selectedIndex;
        showCurrentImage();
        if (playing) {
            scheduleDisplayPhase();
        }
    }

    private void syncSelection(int index) {
        if (index < 0 || index >= imageFiles.size()) {
            return;
        }
        selectionSyncing = true;
        DefaultMutableTreeNode node = treeNodeIndex.get(imageFiles.get(index));
        if (node != null) {
            TreePath treePath = new TreePath(node.getPath());
            imageTree.expandPath(treePath.getParentPath());
            imageTree.setSelectionPath(treePath);
            imageTree.scrollPathToVisible(treePath);
        }
        selectionSyncing = false;
    }

    private void preloadInitial() {
        int generation = imageLoadGeneration.get();
        int limit = Math.min(SlideDefaults.PRELOAD_COUNT, imageFiles.size());
        for (int i = 0; i < limit; i++) {
            Path path = imageFiles.get(i);
            queueImageLoad(path, generation, i == 0);
        }
    }

    private void prefetchAround(int index) {
        int generation = imageLoadGeneration.get();
        for (int i = 1; i <= SlideDefaults.PREFETCH_COUNT; i++) {
            int nextIndex = index + i;
            if (nextIndex < imageFiles.size()) {
                queueImageLoad(imageFiles.get(nextIndex), generation, false);
            }
        }
        updateImageCountLabel();
    }

    private BufferedImage getCachedImage(Path path) {
        synchronized (imageCache) {
            return imageCache.get(path);
        }
    }

    private boolean isImageCached(Path path) {
        synchronized (imageCache) {
            return imageCache.containsKey(path);
        }
    }

    private void queueImageLoad(Path path, int generation, boolean repaintIfCurrent) {
        synchronized (imageCache) {
            if (imageCache.containsKey(path)) {
                return;
            }
            Integer existingGeneration = loadingImages.get(path);
            if (existingGeneration != null && existingGeneration == generation) {
                return;
            }
            loadingImages.put(path, generation);
        }

        imageLoadExecutor.submit(() -> {
            BufferedImage image = readImage(path);
            boolean shouldRefreshCurrent;
            synchronized (imageCache) {
                Integer existingGeneration = loadingImages.get(path);
                if (existingGeneration != null && existingGeneration == generation) {
                    loadingImages.remove(path);
                }
                if (generation != imageLoadGeneration.get()) {
                    if (image != null) {
                        image.flush();
                    }
                    return;
                }
                if (!imageCache.containsKey(path)) {
                    imageCache.put(path, image);
                }
                shouldRefreshCurrent = repaintIfCurrent
                        && !showingBlackFrame
                        && currentIndex >= 0
                        && currentIndex < imageFiles.size()
                        && path.equals(imageFiles.get(currentIndex));
            }

            SwingUtilities.invokeLater(() -> {
                updateImageCountLabel();
                if (shouldRefreshCurrent) {
                    slideCanvas.setImage(getCachedImage(path));
                    slideCanvas.repaint();
                }
            });
        });
    }

    private static BufferedImage readImage(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    private void togglePanel() {
        controlsVisible = !controlsVisible;
        topBar.setVisible(controlsVisible);
        rebuildCenterLayout();
        if (controlsVisible) {
            syncSelection(currentIndex);
        }
        focusMainWindow();
    }

    private void enterPostFinishBlackout() {
        playing = false;
        stopMouseJiggle();
        cancelTimers();
        postFinishBlackout = true;
        playPauseButton.setText("Play");
        showBlackFrame();
        controlsVisible = false;
        topBar.setVisible(false);
        rebuildCenterLayout();
        setStatusText(buildStatusPrefix("POST-FINISH BLACKOUT") + "auto-exit in 5m");
        closeTimer = new Timer(SlideDefaults.POST_FINISH_BLACKOUT_MS, event -> requestSafeExit());
        closeTimer.setRepeats(false);
        closeTimer.start();
        System.out.println("[SLIDE] post-finish blackout started. auto-exit in 5 minutes.");
        frame.revalidate();
        frame.repaint();
    }

    private String currentRelativePath() {
        if (imageFiles.isEmpty()) {
            return "no file";
        }
        int index = lastNonBlackIndex >= 0 ? lastNonBlackIndex : Math.max(0, Math.min(currentIndex, imageFiles.size() - 1));
        return imageFiles.get(index).getFileName().toString();
    }

    private String buildStatusPrefix(String state) {
        int loopValue = getSpinnerValue(loopCountSpinner);
        String loopText = loopValue <= 0 ? "inf" : String.valueOf(loopValue);
        int currentLoop = imageFiles.isEmpty() ? 0 : completedLoops + 1;
        return String.format("[%s] %d/%d page=%dms black=%dms loop=%d/%s cache=%d | ",
                state,
                imageFiles.isEmpty() ? 0 : currentIndex + 1,
                imageFiles.size(),
                getSpinnerValue(pageDisplaySpinner),
                getSpinnerValue(blackFrameSpinner),
                currentLoop,
                loopText,
                imageCache.size()
        );
    }

    private void setStatusText(String text) {
        statusLabel.setText(text);
    }

    private void updateImageCountLabel() {
        imageCountLabel.setText(String.format("Images: %d | Cache: %d/%d",
                imageFiles.size(), imageCache.size(), SlideDefaults.MAX_CACHE_SIZE));
    }

    private void printShortcutHelp(PrintStream out) {
        out.println("Slide shortcuts:");
        out.println("  Space        : Play/Pause toggle");
        out.println("  Left / Right : Previous/Next image");
        out.println("  PgUp / PgDn  : Back/Forward 100 images");
        out.println("  F            : Fullscreen toggle");
        out.println("  T            : Panel toggle");
        out.println("  Q            : Exit");
    }

    private void requestSafeExit() {
        stopMouseJiggle();
        cancelTimers();
        cancelCloseTimer();
        cancelForegroundRecovery();
        imageLoadExecutor.shutdownNow();
        frame.setAlwaysOnTop(false);
        frame.dispose();
    }

    private void cancelTimers() {
        if (displayTimer != null) {
            displayTimer.stop();
            displayTimer = null;
        }
        if (blackTimer != null) {
            blackTimer.stop();
            blackTimer = null;
        }
    }

    private void cancelCloseTimer() {
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }
    }

    private void scheduleForegroundRecovery() {
        if (!shouldRecoverForeground()) {
            return;
        }
        cancelForegroundRecovery();
        foregroundRecoveryTimer = new Timer(150, event -> recoverForeground());
        foregroundRecoveryTimer.setRepeats(false);
        foregroundRecoveryTimer.start();
    }

    private void cancelForegroundRecovery() {
        if (foregroundRecoveryTimer != null) {
            foregroundRecoveryTimer.stop();
            foregroundRecoveryTimer = null;
        }
    }

    private boolean shouldRecoverForeground() {
        return frame != null
                && frame.isDisplayable()
                && (playing || postFinishBlackout);
    }

    private void recoverForeground() {
        cancelForegroundRecovery();
        if (!shouldRecoverForeground()) {
            return;
        }
        boolean keepOnTop = alwaysOnTopCheckBox != null && alwaysOnTopCheckBox.isSelected();
        if (keepOnTop) {
            frame.setAlwaysOnTop(false);
            frame.setAlwaysOnTop(true);
        }
        if ((playing || postFinishBlackout) && !frame.isVisible()) {
            frame.setVisible(true);
        }
        frame.toFront();
        frame.repaint();
        focusMainWindow();
    }

    private void startMouseJiggle() {
        if (mouseJiggleExecutor != null && !mouseJiggleExecutor.isShutdown()) {
            return;
        }
        mouseJiggleExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "slide-mouse-jiggle");
            thread.setDaemon(true);
            return thread;
        });
        mouseJiggleExecutor.scheduleAtFixedRate(SlideApp::nudgeMousePointer, 60, 60, TimeUnit.SECONDS);
    }

    private void stopMouseJiggle() {
        if (mouseJiggleExecutor == null) {
            return;
        }
        mouseJiggleExecutor.shutdownNow();
        mouseJiggleExecutor = null;
    }

    private static void nudgeMousePointer() {
        try {
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            if (pointerInfo == null) {
                return;
            }
            Point point = pointerInfo.getLocation();
            Robot robot = new Robot();
            robot.mouseMove(point.x + 1, point.y);
            robot.mouseMove(point.x, point.y);
        } catch (Exception ignored) {
            // best-effort keep-awake helper
        }
    }

    private void expandTree() {
        for (int i = 0; i < imageTree.getRowCount(); i++) {
            imageTree.expandRow(i);
        }
    }

    private void setFullScreen(boolean fullScreen) {
        if (fullScreen) {
            if (fullScreenActive) {
                return;
            }
            windowedBounds = frame.getBounds();
            Rectangle screenBounds = currentScreenBounds();
            frame.dispose();
            frame.setUndecorated(true);
            frame.setBounds(screenBounds);
            frame.setVisible(true);
            frame.setAlwaysOnTop(alwaysOnTopCheckBox.isSelected());
            fullScreenActive = true;
        } else {
            frame.dispose();
            frame.setUndecorated(false);
            if (windowedBounds != null) {
                frame.setBounds(windowedBounds);
            }
            frame.setVisible(true);
            frame.setAlwaysOnTop(alwaysOnTopCheckBox.isSelected());
            fullScreenActive = false;
        }
        focusMainWindow();
    }

    private void toggleFullScreen() {
        boolean fullScreen = !isFullScreenActive();
        fullScreenCheckBox.setSelected(fullScreen);
        setFullScreen(fullScreen);
    }

    private boolean isFullScreenActive() {
        return fullScreenActive;
    }

    private int getSpinnerValue(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (Exception ignored) {
            // keep last valid value
        }
        return ((Number) spinner.getValue()).intValue();
    }

    private void installSpinnerBehavior(JSpinner spinner) {
        spinner.setFont(FONT_UI);
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
            configureNumericSpinnerEditor(spinner, defaultEditor);
        }
        ChangeListener listener = event -> onTimingSettingChanged();
        spinner.addChangeListener(listener);
    }

    static void configureNumericSpinnerEditor(JSpinner spinner, JSpinner.DefaultEditor defaultEditor) {
        defaultEditor.getTextField().setColumns(5);
        defaultEditor.getTextField().setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);

        if (spinner.getModel() instanceof SpinnerNumberModel numberModel) {
            if (defaultEditor.getTextField().getFormatter() instanceof NumberFormatter numberFormatter) {
                numberFormatter.setAllowsInvalid(false);
                numberFormatter.setCommitsOnValidEdit(true);
                numberFormatter.setMinimum(numberModel.getMinimum());
                numberFormatter.setMaximum(numberModel.getMaximum());
                numberFormatter.setValueClass(numberModel.getNumber().getClass());
            } else if (defaultEditor.getTextField().getFormatter() instanceof DefaultFormatter defaultFormatter) {
                defaultFormatter.setAllowsInvalid(false);
                defaultFormatter.setCommitsOnValidEdit(true);
            }
        }
    }

    private void onTimingSettingChanged() {
        if (!playing || postFinishBlackout) {
            setStatusText(buildStatusPrefix("PAUSE") + currentRelativePath());
            return;
        }
        if (showingBlackFrame) {
            scheduleBlackPhase();
        } else {
            scheduleDisplayPhase();
        }
        setStatusText(buildStatusPrefix("PLAY") + currentRelativePath());
    }

    private void rebuildCenterLayout() {
        centerHost.removeAll();
        if (controlsVisible && !postFinishBlackout) {
            if (splitPane.getWidth() > 0) {
                savedDividerLocation = splitPane.getDividerLocation();
            }
            splitPane.setLeftComponent(centerView);
            splitPane.setRightComponent(rightPanel);
            splitPane.setDividerSize(SlideDefaults.VISIBLE_DIVIDER_SIZE);
            centerHost.add(splitPane, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(savedDividerLocation));
        } else {
            centerHost.add(centerView, BorderLayout.CENTER);
        }
        frame.revalidate();
        frame.repaint();
    }

    private Rectangle currentScreenBounds() {
        Rectangle fallback = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
        if (frame == null || frame.getGraphicsConfiguration() == null) {
            return fallback;
        }
        return frame.getGraphicsConfiguration().getBounds();
    }

    private void focusMainWindow() {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            frame.requestFocus();
            if (frame.getRootPane() != null) {
                frame.getRootPane().requestFocusInWindow();
            }
            slideCanvas.requestFocusInWindow();
        });
    }

    private static final class SlideCanvas extends JPanel {
        private BufferedImage image;

        private SlideCanvas() {
            setBackground(COLOR_BG);
            setOpaque(true);
        }

        private void setImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        private BufferedImage getImage() {
            return image;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int availableWidth = Math.max(1, getWidth() - 40);
            int availableHeight = Math.max(1, getHeight() - 40);
            double scale = Math.min(availableWidth / (double) image.getWidth(), availableHeight / (double) image.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = (getWidth() - drawWidth) / 2;
            int y = (getHeight() - drawHeight) / 2;
            g2.drawImage(image, x, y, drawWidth, drawHeight, null);
            g2.dispose();
        }
    }

    private static final class SlideTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Component component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setBackgroundNonSelectionColor(COLOR_PANEL);
            setBackgroundSelectionColor(new Color(52, 73, 94));
            setTextNonSelectionColor(COLOR_TEXT);
            setTextSelectionColor(Color.WHITE);
            setBorderSelectionColor(new Color(52, 73, 94));
            setFont(FONT_UI);

            if (value instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (leaf && userObject instanceof Path path) {
                    setText(path.getFileName().toString());
                    if (path.getFileName().toString().toLowerCase(Locale.ROOT).contains("session-start")
                            || path.getFileName().toString().toLowerCase(Locale.ROOT).contains("session-end")) {
                        setForeground(COLOR_ACCENT);
                    }
                } else if (userObject instanceof Path path) {
                    setText(path.getFileName() != null ? path.getFileName().toString() : path.toString());
                }
            }
            return component;
        }
    }
}
