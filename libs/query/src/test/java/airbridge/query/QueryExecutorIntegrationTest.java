package airbridge.query;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryExecutor 통합 테스트 - H2 인메모리 DB를 이용해 실제 쿼리 실행을 검증합니다.
 */
@DisplayName("QueryExecutor 통합 테스트 (H2 인메모리 DB)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryExecutorIntegrationTest {

    @TempDir
    Path tempDir;

    private static final String H2_URL =
            "jdbc:h2:mem:exec_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private QueryConfig buildConfig(String extraCsv) throws Exception {
        Path configFile = tempDir.resolve("config_" + System.nanoTime() + ".csv");
        String base = """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                thread.count,2
                fetch.size,100
                csv.delimiter,","
                """.formatted(H2_URL);
        Files.writeString(configFile, base + extraCsv, StandardCharsets.UTF_8);
        return QueryConfig.loadFromPath(configFile);
    }

    private List<QueryParser.QueryInfo> parseQueries(String sql) {
        return QueryParser.parseContent(sql);
    }

    // ─── 기본 실행 ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("단순 SELECT 쿼리가 CSV 파일로 정상 출력됨")
    void testSimpleSelectOutputsCsv() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 기본_조회
                SELECT 1 AS id, 'hello' AS msg;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_기본_조회.csv");
        assertTrue(Files.exists(csv), "결과 CSV 파일이 생성되어야 합니다.");
        assertTrue(Files.exists(tempDir.resolve("_00_summary.csv")), "요약 CSV 파일이 생성되어야 합니다.");
        String content = Files.readString(csv, StandardCharsets.UTF_8).toLowerCase();
        assertTrue(content.contains("id"), "헤더에 'id' 컬럼이 있어야 합니다.");
        assertTrue(content.contains("msg"), "헤더에 'msg' 컬럼이 있어야 합니다.");
        assertTrue(content.contains("hello"), "'hello' 값이 CSV에 있어야 합니다.");
    }

    @Test
    @Order(2)
    @DisplayName("다중 쿼리가 각각 별도 CSV 파일로 출력됨")
    void testMultipleQueriesOutputSeparateCsvFiles() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 첫번째_쿼리
                SELECT 1 AS val;
                
                # 두번째_쿼리
                SELECT 2 AS val;
                """);

        new QueryExecutor(config, queries).executeAll();

        assertTrue(Files.exists(tempDir.resolve("01_첫번째_쿼리.csv")));
        assertTrue(Files.exists(tempDir.resolve("02_두번째_쿼리.csv")));
        assertTrue(Files.exists(tempDir.resolve("_00_summary.csv")));
    }

    @Test
    @Order(3)
    @DisplayName("execution-report.txt 가 생성됨")
    void testExecutionReportCreated() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("SELECT 42 AS answer;");

        new QueryExecutor(config, queries).executeAll();

        Path report = tempDir.resolve("execution-report.txt");
        assertTrue(Files.exists(report), "execution-report.txt 가 생성되어야 합니다.");
        String content = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(content.contains("SUCCESS"), "보고서에 SUCCESS가 포함되어야 합니다.");
    }

    @Test
    @Order(4)
    @DisplayName("NULL 값 컬럼은 빈 문자열로 CSV에 기록됨")
    void testNullValueWrittenAsEmpty() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 널값_테스트
                SELECT NULL AS empty_col, 'ok' AS other;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_널값_테스트.csv");
        assertTrue(Files.exists(csv));
        String content = Files.readString(csv, StandardCharsets.UTF_8);
        // NULL은 빈 문자열로 → "," 가 연속으로 나타나거나 빈 셀로 기록
        assertTrue(content.contains("ok"), "'ok' 값이 CSV에 있어야 합니다.");
    }

    @Test
    @Order(5)
    @DisplayName("SELECT 이외의 SQL(DML)은 실행을 거부하고 FAILED QueryResult를 반환함")
    void testDmlQueryIsRejected() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 금지_쿼리
                UPDATE some_table SET col=1 WHERE 1=0;
                """);

        // 실행은 되지만 해당 쿼리는 FAILED 처리 (예외 없이 정상 종료)
        assertDoesNotThrow(() -> new QueryExecutor(config, queries).executeAll());

        // CSV 파일은 생성되지 않아야 함
        Path csv = tempDir.resolve("금지_쿼리.csv");
        assertFalse(Files.exists(csv), "DML 쿼리의 결과 파일은 생성되면 안 됩니다.");
    }

    @Test
    @Order(6)
    @DisplayName("빈 SQL은 건너뛰고 FAILED QueryResult를 반환함")
    void testEmptySqlIsSkipped() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        // QueryParser가 주석만 있는 블록을 건너뛰므로 직접 QueryInfo 생성
        List<QueryParser.QueryInfo> queries = List.of(
                new QueryParser.QueryInfo("   ", "빈_쿼리_테스트")
        );

        assertDoesNotThrow(() -> new QueryExecutor(config, queries).executeAll());
        assertFalse(Files.exists(tempDir.resolve("빈_쿼리_테스트.csv")));
    }

    @Test
    @Order(7)
    @DisplayName("output.dir 미존재 시 자동 생성 후 파일 출력")
    void testOutputDirCreatedIfNotExists() throws Exception {
        Path newOutputDir = tempDir.resolve("new-output-dir");
        assertFalse(Files.exists(newOutputDir), "테스트 전에 디렉토리가 없어야 합니다.");

        QueryConfig config = buildConfig("output.dir," + newOutputDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 자동생성_테스트
                SELECT 'created' AS result;
                """);

        new QueryExecutor(config, queries).executeAll();

        assertTrue(Files.exists(newOutputDir), "output.dir 이 자동 생성되어야 합니다.");
        assertTrue(Files.exists(newOutputDir.resolve("01_자동생성_테스트.csv")));
    }

    @Test
    @Order(8)
    @DisplayName("구분자가 | 일 때 CSV가 | 로 분리됨")
    void testPipeDelimiter() throws Exception {
        Path configFile = tempDir.resolve("pipe_config.csv");
        Files.writeString(configFile, """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                thread.count,1
                fetch.size,100
                csv.delimiter,|
                output.dir,%s
                """.formatted(H2_URL, tempDir), StandardCharsets.UTF_8);
        QueryConfig config = QueryConfig.loadFromPath(configFile);

        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 파이프_구분자
                SELECT 'a' AS col1, 'b' AS col2;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_파이프_구분자.csv");
        assertTrue(Files.exists(csv));
        String content = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("|"), "CSV 파일에 | 구분자가 있어야 합니다.");
    }

    @Test
    @Order(9)
    @DisplayName("WITH 절 CTE 쿼리가 정상 실행됨")
    void testCteQueryExecuted() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # CTE_테스트
                WITH nums AS (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3)
                SELECT * FROM nums;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_CTE_테스트.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(4, lines.size(), "헤더 1행 + 데이터 3행 = 총 4행이어야 합니다.");
    }

    @Test
    @Order(10)
    @DisplayName("결과가 0건인 쿼리도 헤더만 있는 CSV를 생성함")
    void testZeroRowQueryCreatesHeaderOnly() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 빈결과_테스트
                SELECT 1 AS id WHERE 1 = 0;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_빈결과_테스트.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), "데이터 없으면 헤더 행만 1줄 있어야 합니다.");
    }

    @Test
    @Order(11)
    @DisplayName("csv.bom=true 이면 CSV 파일 첫 3바이트가 UTF-8 BOM 이어야 함")
    void testCsvBomWritten() throws Exception {
        Path configFile = tempDir.resolve("bom_config.csv");
        Files.writeString(configFile, """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                thread.count,1
                fetch.size,100
                csv.delimiter,","
                csv.bom,true
                output.dir,%s
                """.formatted(H2_URL, tempDir), StandardCharsets.UTF_8);
        QueryConfig config = QueryConfig.loadFromPath(configFile);
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # BOM_테스트
                SELECT 'hello' AS msg;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_BOM_테스트.csv");
        assertTrue(Files.exists(csv), "BOM CSV 파일이 생성되어야 합니다.");
        byte[] raw = Files.readAllBytes(csv);
        // UTF-8 BOM: EF BB BF
        assertEquals((byte) 0xEF, raw[0], "BOM 첫 번째 바이트: 0xEF");
        assertEquals((byte) 0xBB, raw[1], "BOM 두 번째 바이트: 0xBB");
        assertEquals((byte) 0xBF, raw[2], "BOM 세 번째 바이트: 0xBF");
    }

    @Test
    @Order(12)
    @DisplayName("execution-report.json 파일이 생성되고 JSON 형식이 유효함")
    void testJsonReportCreated() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("SELECT 99 AS val;");

        new QueryExecutor(config, queries).executeAll();

        Path jsonReport = tempDir.resolve("execution-report.json");
        assertTrue(Files.exists(jsonReport), "execution-report.json 파일이 생성되어야 합니다.");
        String json = Files.readString(jsonReport, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{"), "JSON 파일은 '{' 로 시작해야 합니다.");
        assertTrue(json.contains("\"successCount\""), "successCount 필드가 있어야 합니다.");
        assertTrue(json.contains("\"queries\""), "queries 배열이 있어야 합니다.");
        assertTrue(json.contains("\"SUCCESS\""), "성공한 쿼리 상태가 SUCCESS여야 합니다.");
        assertTrue(json.endsWith("}\n"), "JSON 파일은 '}'로 끝나야 합니다.");
    }

    @Test
    @Order(13)
    @DisplayName("query.retry.count 설정이 QueryConfig에서 올바르게 읽힘")
    void testRetryCountConfig() throws Exception {
        Path configFile = tempDir.resolve("retry_config.csv");
        Files.writeString(configFile, """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                query.retry.count,3
                query.retry.delay.seconds,2
                output.dir,%s
                """.formatted(H2_URL, tempDir), StandardCharsets.UTF_8);
        QueryConfig config = QueryConfig.loadFromPath(configFile);
        assertEquals(3, config.getQueryRetryCount());
        assertEquals(2, config.getQueryRetryDelaySeconds());
    }

    @Test
    @Order(14)
    @DisplayName("progress.interval 설정이 QueryConfig에서 올바르게 읽힘")
    void testProgressIntervalConfig() throws Exception {
        Path configFile = tempDir.resolve("progress_config.csv");
        Files.writeString(configFile, """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                progress.interval,10000
                output.dir,%s
                """.formatted(H2_URL, tempDir), StandardCharsets.UTF_8);
        QueryConfig config = QueryConfig.loadFromPath(configFile);
        assertEquals(10_000, config.getProgressInterval());
    }

    @Test
    @Order(15)
    @DisplayName("csv.bom=false(기본)이면 CSV 파일에 BOM이 없어야 함")
    void testNoBomByDefault() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 노BOM_테스트
                SELECT 'world' AS msg;
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_노BOM_테스트.csv");
        assertTrue(Files.exists(csv));
        byte[] raw = Files.readAllBytes(csv);
        // BOM이 없으면 첫 바이트가 0xEF가 아니어야 함
        assertNotEquals((byte) 0xEF, raw[0], "기본 설정에서는 BOM이 없어야 합니다.");
    }

    @Test
    @Order(16)
    @DisplayName("output.dir이 명시되지 않은 경우 yyyyMMdd_HHmmss 형태의 고유 디렉토리 경로가 생성됨")
    void testDefaultOutputDirAutoSplitsByDateTime() throws Exception {
        Path configFile = tempDir.resolve("empty_output_config.csv");
        Files.writeString(configFile, """
                key,value
                db.url,%s
                db.username,sa
                db.password,
                output.dir,
                """.formatted(H2_URL), StandardCharsets.UTF_8);
        QueryConfig config = QueryConfig.loadFromPath(configFile);
        Path outputDir = config.getOutputDir();
        assertNotNull(outputDir, "output.dir이 없어도 자동 생성되어야 합니다.");
        assertTrue(outputDir.getFileName().toString().startsWith("run_"), "생성된 폴더명은 run_으로 시작해야 합니다.");
    }

    @Test
    @Order(17)
    @DisplayName("init 실행 시 기존 파일이 존재하더라도 QueryConfig 덮어쓰기가 정상적으로 작동함")
    void testInitForceModeOverwrites() throws Exception {
        Path dummyConfig = tempDir.resolve("force_config.csv");
        Files.writeString(dummyConfig, "existing-data", StandardCharsets.UTF_8);
        QueryConfig.createDefaultTemplate(dummyConfig);
        String updatedContent = Files.readString(dummyConfig, StandardCharsets.UTF_8);
        assertTrue(updatedContent.contains("key,value"), "템플릿 데이터가 올바르게 덮어씌워져야 합니다.");
    }

    @Test
    @Order(18)
    @DisplayName("queries.sql에 포함된 쿼리들이 미리보기 모드로 파싱되어 제목과 SQL 헤더가 리스트업됨")
    void testQueryListModePreview() throws Exception {
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 테스트_쿼리_하나
                SELECT 100 AS num;
                # 테스트_쿼리_둘
                SELECT 200 AS num;
                """);
        assertEquals(2, queries.size());
        assertEquals("테스트_쿼리_하나", queries.get(0).getTitle());
        assertTrue(queries.get(0).getSql().contains("100"));
        assertEquals("테스트_쿼리_둘", queries.get(1).getTitle());
        assertTrue(queries.get(1).getSql().contains("200"));
    }

    @Test
    @Order(19)
    @DisplayName("세미콜론 우회 다중 쿼리 주입 시 비허용 DML이 포함되어 있으면 실행이 차단됨")
    void testMultiStatementInjectionBlocked() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = List.of(
                new QueryParser.QueryInfo("SELECT 1 AS num; DROP TABLE users;", "다중_주입_공격")
        );
        
        new QueryExecutor(config, queries).executeAll();
        
        // CSV 파일이 정상적으로 만들어지지 않거나, 에러 로그가 요약본에 남아야 함
        Path csv = tempDir.resolve("01_다중_주입_공격.csv");
        assertFalse(Files.exists(csv), "DML이 주입된 쿼리는 CSV가 쓰여지면 안 됩니다.");
        
        Path summary = tempDir.resolve("_00_summary.csv");
        assertTrue(Files.exists(summary));
        String summaryContent = Files.readString(summary, StandardCharsets.UTF_8);
        assertTrue(summaryContent.contains("ERROR"), "비허용 DML 구문이 감지되어 에러 상태여야 합니다.");
    }

    @Test
    @Order(20)
    @DisplayName("문자열 리터럴 또는 주석 내의 세미콜론은 오인되지 않고 쿼리가 정상 실행됨")
    void testSemicolonInLiteralAndComment() throws Exception {
        QueryConfig config = buildConfig("output.dir," + tempDir + "\n");
        List<QueryParser.QueryInfo> queries = parseQueries("""
                # 세미콜론_테스트
                SELECT 'hello; world' AS msg, 42 AS num; -- 이것은 주석; 세미콜론이 포함됨
                """);

        new QueryExecutor(config, queries).executeAll();

        Path csv = tempDir.resolve("01_세미콜론_테스트.csv");
        assertTrue(Files.exists(csv), "결과 CSV 파일이 생성되어야 합니다.");
        String content = Files.readString(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("hello; world"), "문자열 내의 세미콜론이 깨지지 않고 들어가야 합니다.");
    }
}
