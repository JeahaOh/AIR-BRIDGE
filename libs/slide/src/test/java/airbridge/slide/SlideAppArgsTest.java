package airbridge.slide;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SlideAppArgsTest {
    @Test
    void parsesPositionalInputDirectory() {
        assertEquals(Path.of("qr-out"), SlideApp.initialInputDirFromArgs(new String[]{"qr-out"}));
    }

    @Test
    void parsesNamedInputDirectory() {
        assertEquals(Path.of("qr-out"), SlideApp.initialInputDirFromArgs(new String[]{"--in", "qr-out"}));
        assertEquals(Path.of("qr-out"), SlideApp.initialInputDirFromArgs(new String[]{"--input=qr-out"}));
    }

    @Test
    void ignoresMissingInputDirectoryOptionValue() {
        assertNull(SlideApp.initialInputDirFromArgs(new String[]{"--in"}));
    }
}
