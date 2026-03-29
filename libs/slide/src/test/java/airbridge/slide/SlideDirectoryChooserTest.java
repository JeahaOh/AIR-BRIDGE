package airbridge.slide;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlideDirectoryChooserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsePathReturnsNormalizedAbsolutePath() {
        Path rawPath = tempDir.resolve("nested/../slides");

        Path parsed = SlideDirectoryChooser.parsePath("  " + rawPath + "  ");

        assertEquals(tempDir.resolve("slides").toAbsolutePath().normalize(), parsed);
    }

    @Test
    void parsePathReturnsNullForBlankInput() {
        assertNull(SlideDirectoryChooser.parsePath(null));
        assertNull(SlideDirectoryChooser.parsePath(""));
        assertNull(SlideDirectoryChooser.parsePath("   "));
    }
}
