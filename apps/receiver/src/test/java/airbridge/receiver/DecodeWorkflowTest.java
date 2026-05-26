package airbridge.receiver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecodeWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsMissingInputDirectoryWithoutCreatingOutput() throws Exception {
        Path inputDir = tempDir.resolve("missing");
        Path outputDir = tempDir.resolve("restored");
        List<String> logs = new ArrayList<>();

        DecodeWorkflow.Result result = new DecodeWorkflow(1).decode(inputDir, outputDir, logs::add);

        assertEquals(DecodeWorkflow.Status.INPUT_MISSING, result.status());
        assertEquals(0, result.qrFileCount());
        assertTrue(logs.getFirst().contains("QR 입력 디렉토리가 존재하지 않습니다"));
    }

    @Test
    void reportsEmptyInputDirectoryWithoutCreatingOutput() throws Exception {
        Path inputDir = tempDir.resolve("qr");
        Path outputDir = tempDir.resolve("restored");
        Files.createDirectories(inputDir);
        List<String> logs = new ArrayList<>();

        DecodeWorkflow.Result result = new DecodeWorkflow(1).decode(inputDir, outputDir, logs::add);

        assertEquals(DecodeWorkflow.Status.NO_QR_FILES, result.status());
        assertEquals(0, result.qrFileCount());
        assertTrue(logs.getFirst().contains("대상 QR PNG 파일이 없습니다"));
    }
}
