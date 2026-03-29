package airbridge.slide;

import javax.swing.JFileChooser;
import javax.swing.JFrame;

import java.awt.FileDialog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

final class SlideDirectoryChooser {
    private SlideDirectoryChooser() {
    }

    static Path chooseDirectory(JFrame frame, String rawCurrentPath) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return chooseDirectoryWithFileDialog(frame, rawCurrentPath);
        }
        return chooseDirectoryWithChooser(frame, rawCurrentPath);
    }

    static Path parsePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return Paths.get(raw.trim()).toAbsolutePath().normalize();
    }

    private static Path chooseDirectoryWithFileDialog(JFrame frame, String rawCurrentPath) {
        String oldValue = System.getProperty("apple.awt.fileDialogForDirectories");
        try {
            System.setProperty("apple.awt.fileDialogForDirectories", "true");
            return chooseDirectoryWithFileDialogOrNull(frame, rawCurrentPath);
        } finally {
            if (oldValue == null) {
                System.clearProperty("apple.awt.fileDialogForDirectories");
            } else {
                System.setProperty("apple.awt.fileDialogForDirectories", oldValue);
            }
        }
    }

    private static Path chooseDirectoryWithFileDialogOrNull(JFrame frame, String rawCurrentPath) {
        FileDialog dialog = new FileDialog(frame, "Select image directory", FileDialog.LOAD);
        Path currentPath = parsePath(rawCurrentPath);
        if (currentPath != null) {
            Path initialDir = Files.isDirectory(currentPath) ? currentPath : currentPath.getParent();
            if (initialDir != null && Files.isDirectory(initialDir)) {
                dialog.setDirectory(initialDir.toString());
            }
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
        Path parent = selected.getParent();
        return parent != null ? parent.toAbsolutePath().normalize() : null;
    }

    private static Path chooseDirectoryWithChooser(JFrame frame, String rawCurrentPath) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select image directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        Path currentPath = parsePath(rawCurrentPath);
        if (currentPath != null) {
            Path initialDir = Files.isDirectory(currentPath) ? currentPath : currentPath.getParent();
            if (initialDir != null && Files.isDirectory(initialDir)) {
                chooser.setCurrentDirectory(initialDir.toFile());
                chooser.setSelectedFile(initialDir.toFile());
            }
        }

        int result = chooser.showOpenDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }
}
