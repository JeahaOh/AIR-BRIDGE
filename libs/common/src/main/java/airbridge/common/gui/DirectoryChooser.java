package airbridge.common.gui;

import javax.swing.JFileChooser;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class DirectoryChooser {
    private DirectoryChooser() {
    }

    public static Path chooseDirectory(Component parent, String title, String rawCurrentPath) {
        return chooseDirectory(parent, title, rawCurrentPath, appStartDirectory());
    }

    public static Path chooseDirectory(Component parent, String title, String rawCurrentPath, Path fallbackDirectory) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return chooseDirectoryWithMacFileDialog(parent, title, rawCurrentPath, fallbackDirectory);
        }
        if (osName.contains("win")) {
            return chooseDirectoryWithWindowsFileDialogOrFallback(parent, title, rawCurrentPath, fallbackDirectory);
        }
        return chooseDirectoryWithChooser(parent, title, rawCurrentPath, fallbackDirectory);
    }

    public static Path parsePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return Paths.get(raw.trim()).toAbsolutePath().normalize();
    }

    public static Path appStartDirectory() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static Path chooseDirectoryWithMacFileDialog(Component parent, String title, String rawCurrentPath,
                                                         Path fallbackDirectory) {
        String oldValue = System.getProperty("apple.awt.fileDialogForDirectories");
        try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true");
            return chooseDirectoryWithFileDialogOrNull(parent, title, rawCurrentPath, fallbackDirectory, true);
        } finally {
            if (oldValue == null) {
                System.clearProperty("apple.awt.fileDialogForDirectories");
            } else {
                System.setProperty("apple.awt.fileDialogForDirectories", oldValue);
            }
        }
    }

    private static Path chooseDirectoryWithWindowsFileDialogOrFallback(Component parent, String title,
                                                                       String rawCurrentPath, Path fallbackDirectory) {
        try {
            Path selected = chooseDirectoryWithFileDialogOrNull(parent, title, rawCurrentPath, fallbackDirectory, false);
            if (selected != null) {
                return selected;
            }
        } catch (RuntimeException | Error e) {
            // Fall through to the Swing chooser when the native dialog is unavailable or rejects the selection.
        }
        return chooseDirectoryWithChooser(parent, title, rawCurrentPath, fallbackDirectory);
    }

    private static Path chooseDirectoryWithFileDialogOrNull(Component parent, String title, String rawCurrentPath,
                                                            Path fallbackDirectory, boolean allowParentFallback) {
        FileDialog dialog = createFileDialog(parent, title);
        Path initialDir = initialDirectory(rawCurrentPath, fallbackDirectory);
        if (initialDir != null) {
            dialog.setDirectory(initialDir.toString());
        }
        dialog.setVisible(true);
        String directory = dialog.getDirectory();
        String file = dialog.getFile();
        dialog.dispose();

        if (directory == null) {
            return null;
        }

        Path selected = file == null
                ? Paths.get(directory)
                : Paths.get(directory, file);
        if (Files.isDirectory(selected)) {
            return selected.toAbsolutePath().normalize();
        }
        if (!allowParentFallback) {
            return null;
        }
        Path parentPath = selected.getParent();
        return parentPath != null ? parentPath.toAbsolutePath().normalize() : null;
    }

    private static FileDialog createFileDialog(Component parent, String title) {
        Window owner = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        if (owner instanceof Frame frame) {
            return new FileDialog(frame, title, FileDialog.LOAD);
        }
        if (owner instanceof Dialog dialog) {
            return new FileDialog(dialog, title, FileDialog.LOAD);
        }
        return new FileDialog((Frame) null, title, FileDialog.LOAD);
    }

    private static Path chooseDirectoryWithChooser(Component parent, String title, String rawCurrentPath,
                                                   Path fallbackDirectory) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        Path initialDir = initialDirectory(rawCurrentPath, fallbackDirectory);
        if (initialDir != null) {
            chooser.setCurrentDirectory(initialDir.toFile());
            chooser.setSelectedFile(initialDir.toFile());
        }

        int result = chooser.showOpenDialog(parent);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }

    private static Path initialDirectory(String rawCurrentPath, Path fallbackDirectory) {
        Path currentPath = parsePath(rawCurrentPath);
        if (currentPath != null) {
            Path currentInitial = Files.isDirectory(currentPath) ? currentPath : currentPath.getParent();
            if (currentInitial != null && Files.isDirectory(currentInitial)) {
                return currentInitial.toAbsolutePath().normalize();
            }
        }
        if (fallbackDirectory != null && Files.isDirectory(fallbackDirectory)) {
            return fallbackDirectory.toAbsolutePath().normalize();
        }
        return null;
    }
}
