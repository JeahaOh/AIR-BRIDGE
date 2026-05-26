package airbridge.common.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DirectoryChooserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsePathReturnsNormalizedAbsolutePath() {
        Path rawPath = tempDir.resolve("nested/../selected");

        Path parsed = DirectoryChooser.parsePath("  " + rawPath + "  ");

        assertEquals(tempDir.resolve("selected").toAbsolutePath().normalize(), parsed);
    }

    @Test
    void parsePathReturnsNullForBlankInput() {
        assertNull(DirectoryChooser.parsePath(null));
        assertNull(DirectoryChooser.parsePath(""));
        assertNull(DirectoryChooser.parsePath("   "));
    }

    @Test
    void appStartDirectoryUsesUserDir() {
        assertEquals(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
                DirectoryChooser.appStartDirectory());
    }
}
