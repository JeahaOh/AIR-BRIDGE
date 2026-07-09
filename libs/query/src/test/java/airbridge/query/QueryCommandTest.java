package airbridge.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryCommandTest {
    @TempDir
    Path tempDir;

    @Test
    void initCreatesTemplates() {
        Path config = tempDir.resolve("config.csv");
        Path sql = tempDir.resolve("queries.sql");

        Result result = execute("init", "--config=" + config, "--sql=" + sql);

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(config));
        assertTrue(Files.exists(sql));
    }

    @Test
    void listModeParsesSqlWithoutConfig() throws Exception {
        Path sql = tempDir.resolve("queries.sql");
        Files.writeString(sql, """
                # 샘플_조회
                SELECT 1 AS id;
                """, StandardCharsets.UTF_8);

        Result result = execute("--list", "--sql=" + sql);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("샘플_조회"));
        assertTrue(result.stdout().contains("SELECT 1 AS id"));
    }

    @Test
    void commandExecutesQueriesToOutputDirectory() throws Exception {
        Path config = tempDir.resolve("config.csv");
        Path sql = tempDir.resolve("queries.sql");
        Path out = tempDir.resolve("out");
        Files.writeString(config, """
                key,value
                db.url,jdbc:h2:mem:query_command;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
                db.username,sa
                db.password,
                thread.count,1
                fetch.size,100
                csv.delimiter,","
                """, StandardCharsets.UTF_8);
        Files.writeString(sql, """
                # 명령_조회
                SELECT 'ok' AS status;
                """, StandardCharsets.UTF_8);

        Result result = execute("--config=" + config, "--sql=" + sql, "--out=" + out);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.exists(out.resolve("01_명령_조회.csv")));
        assertTrue(Files.exists(out.resolve("execution-report.txt")));
        assertTrue(Files.exists(out.resolve("execution-report.json")));
        assertTrue(Files.exists(out.resolve("_00_summary.csv")));
    }

    private static Result execute(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new QueryCommand()).execute(args);
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
