package airbridge.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelativePathSupportTest {
    @Test
    void normalizeRelativePathKeepsValidNestedPath() {
        assertEquals("docs/sample.txt", RelativePathSupport.normalizeRelativePath("docs/sample.txt"));
    }

    @Test
    void normalizeRelativePathRejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> RelativePathSupport.normalizeRelativePath("../sample.txt"));
    }

    @Test
    void normalizeRelativePathRejectsAbsoluteAndBackslashPaths() {
        assertThrows(IllegalArgumentException.class, () -> RelativePathSupport.normalizeRelativePath("/tmp/sample.txt"));
        assertThrows(IllegalArgumentException.class, () -> RelativePathSupport.normalizeRelativePath("..\\sample.txt"));
        assertThrows(IllegalArgumentException.class, () -> RelativePathSupport.normalizeRelativePath("C:/tmp/sample.txt"));
    }

    @Test
    void resolveUnderRootRejectsEscapingPath() {
        assertThrows(IllegalArgumentException.class,
                () -> RelativePathSupport.resolveUnderRoot(Path.of("/tmp/root"), "../escape.txt"));
    }
}
