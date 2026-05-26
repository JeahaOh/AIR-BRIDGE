package airbridge.slide;

import airbridge.common.gui.DirectoryChooser;

import javax.swing.JFrame;
import java.nio.file.Path;

final class SlideDirectoryChooser {
    private SlideDirectoryChooser() {
    }

    static Path chooseDirectory(JFrame frame, String rawCurrentPath) {
        return DirectoryChooser.chooseDirectory(frame, "Select image directory", rawCurrentPath);
    }

    static Path parsePath(String raw) {
        return DirectoryChooser.parsePath(raw);
    }
}
