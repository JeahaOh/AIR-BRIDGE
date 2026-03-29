package airbridge.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void collectSourceFilesRespectsExtensionsSkipDirsHiddenDirsAndExcludePaths() throws Exception {
        Path keepDir = Files.createDirectories(tempDir.resolve("keep"));
        Path excludedDir = Files.createDirectories(tempDir.resolve("excluded"));
        Path skippedDir = Files.createDirectories(tempDir.resolve("node_modules"));
        Files.createDirectories(tempDir.resolve(".hidden"));

        Path upperTxt = Files.writeString(keepDir.resolve("UPPER.TXT"), "alpha");
        Path csv = Files.writeString(keepDir.resolve("data.csv"), "beta");
        Files.writeString(keepDir.resolve("ignore.md"), "gamma");
        Files.writeString(excludedDir.resolve("skip.txt"), "delta");
        Files.writeString(skippedDir.resolve("skip-too.txt"), "epsilon");
        Files.writeString(tempDir.resolve(".hidden/hidden.txt"), "zeta");

        List<Path> files = SourceCollector.collectSourceFiles(
                tempDir,
                List.of("txt", ".csv"),
                List.of("node_modules"),
                List.of(excludedDir.toString())
        );

        assertEquals(List.of(upperTxt, csv).stream().sorted().toList(), files);
    }
}
