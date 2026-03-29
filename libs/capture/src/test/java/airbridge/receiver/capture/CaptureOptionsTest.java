package airbridge.receiver.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CaptureOptionsTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorNormalizesPathAndMinimumValues() {
        CaptureOptions options = new CaptureOptions(
                tempDir.resolve("nested/../capture-out"),
                3,
                0,
                -5,
                0.0d,
                -1L,
                -2,
                0,
                -100L,
                0L,
                true
        );

        assertEquals(tempDir.resolve("capture-out").toAbsolutePath().normalize(), options.outputDir());
        assertEquals(3, options.deviceIndex());
        assertEquals(1, options.width());
        assertEquals(1, options.height());
        assertEquals(0.1d, options.fps());
        assertEquals(0L, options.durationSeconds());
        assertEquals(0, options.maxPayloads());
        assertEquals(1, options.decodeWorkers());
        assertEquals(0L, options.statusIntervalMs());
        assertEquals(1L, options.sameSignalSeconds());
        assertEquals(true, options.resume());
    }

    @Test
    void constructorPreservesAlreadyValidValues() {
        CaptureOptions options = new CaptureOptions(
                tempDir,
                1,
                1920,
                1080,
                15.0d,
                30L,
                200,
                4,
                5000L,
                60L,
                false
        );

        assertEquals(tempDir.toAbsolutePath().normalize(), options.outputDir());
        assertEquals(1920, options.width());
        assertEquals(1080, options.height());
        assertEquals(15.0d, options.fps());
        assertEquals(30L, options.durationSeconds());
        assertEquals(200, options.maxPayloads());
        assertEquals(4, options.decodeWorkers());
        assertEquals(5000L, options.statusIntervalMs());
        assertEquals(60L, options.sameSignalSeconds());
        assertFalse(options.resume());
    }
}
