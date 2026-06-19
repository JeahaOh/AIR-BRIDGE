package airbridge.slide;

import airbridge.common.AppPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void defaultsToEncodedDirWhenNoInputGiven() {
        assertEquals(AppPaths.encodedDir(), SlideApp.initialInputDirFromArgs(new String[0]));
        assertEquals(AppPaths.encodedDir(), SlideApp.initialInputDirFromArgs(new String[]{"--in"}));
    }
}
