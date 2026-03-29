package airbridge.receiver;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileChunksTest {

    @Test
    void addChunkTracksMissingChunksAndJoinsDataInChunkOrder() {
        FileChunks fileChunks = new FileChunks("TESTPROJ", "docs/sample.bin", 3, "hash-1234");

        fileChunks.addChunk(new QrDecodedChunk("TESTPROJ", "docs/sample.bin", 2, 3, "hash-1234", "B"), Path.of("qr-2.png"));
        fileChunks.addChunk(new QrDecodedChunk("TESTPROJ", "docs/sample.bin", 1, 3, "hash-1234", "A"), Path.of("qr-1.png"));

        assertEquals(List.of(3), fileChunks.findMissingChunks());

        fileChunks.addChunk(new QrDecodedChunk("TESTPROJ", "docs/sample.bin", 3, 3, "hash-1234", "C"), Path.of("qr-3.png"));

        assertEquals(List.of(), fileChunks.findMissingChunks());
        assertEquals("ABC", fileChunks.joinEncodedData());
        assertEquals(List.of(Path.of("qr-2.png"), Path.of("qr-1.png"), Path.of("qr-3.png")), fileChunks.qrFiles());
    }

    @Test
    void addChunkRejectsMismatchedMetadata() {
        FileChunks fileChunks = new FileChunks("TESTPROJ", "docs/sample.bin", 2, "hash-1234");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                fileChunks.addChunk(
                        new QrDecodedChunk("OTHER", "docs/sample.bin", 1, 2, "hash-1234", "A"),
                        Path.of("qr-1.png")
                )
        );

        assertEquals("동일 파일에 대한 메타데이터가 일치하지 않습니다: qr-1.png", error.getMessage());
    }
}
