package airbridge.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodeWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsMissingInputDirectoryWithoutCreatingOutput() throws Exception {
        Path inputDir = tempDir.resolve("missing");
        Path outputDir = tempDir.resolve("qr");
        List<String> logs = new ArrayList<>();

        EncodeWorkflow.Result result = new EncodeWorkflow()
                .encode(EncodeWorkflow.Request.defaults(inputDir, outputDir), logs::add);

        assertEquals(EncodeWorkflow.Status.INPUT_MISSING, result.status());
        assertEquals(0, result.sourceFileCount());
        assertTrue(logs.getFirst().contains("소스 디렉토리가 존재하지 않습니다"));
    }

    @Test
    void reportsEmptyInputDirectoryWithoutCreatingOutput() throws Exception {
        Path inputDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("qr");
        Files.createDirectories(inputDir);
        List<String> logs = new ArrayList<>();

        EncodeWorkflow.Result result = new EncodeWorkflow()
                .encode(EncodeWorkflow.Request.defaults(inputDir, outputDir), logs::add);

        assertEquals(EncodeWorkflow.Status.NO_SOURCE_FILES, result.status());
        assertEquals(0, result.sourceFileCount());
        assertTrue(logs.getFirst().contains("대상 소스파일이 없습니다"));
    }

    @Test
    void returnsCancelledWhenCancellationIsRequested() throws Exception {
        Path inputDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("qr");
        Files.createDirectories(inputDir);
        Files.writeString(inputDir.resolve("sample.txt"), "sample");
        List<String> logs = new ArrayList<>();

        EncodeWorkflow.Result result = new EncodeWorkflow()
                .encode(EncodeWorkflow.Request.defaults(inputDir, outputDir), logs::add, () -> true);

        assertEquals(EncodeWorkflow.Status.CANCELLED, result.status());
        assertEquals(1, result.sourceFileCount());
        assertTrue(logs.stream().anyMatch(line -> line.contains("encode cancelled")));
    }
}
