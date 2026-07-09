package airbridge.query;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class QueryExecutor {
    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** SELECT/WITH 이외의 문장은 실행 거부 (DDL/DML 방지) */
    private static final Pattern ALLOWED_SQL_PATTERN =
            Pattern.compile("^\\s*(SELECT|WITH)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 재시도 가능한 SQLState 접두사 (연결 관련 오류) */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("08", "57");

    private final QueryConfig     config;
    private final List<QueryParser.QueryInfo> queries;
    private final DatabaseConnector connector;
    private final DataSource dataSource;
    private final Path       outputDir;

    public QueryExecutor(QueryConfig config, List<QueryParser.QueryInfo> queries) {
        if (config == null)  throw new IllegalArgumentException("config 는 null 이 될 수 없습니다.");
        if (queries == null) throw new IllegalArgumentException("queries 는 null 이 될 수 없습니다.");

        this.config    = config;
        this.queries   = Collections.unmodifiableList(new ArrayList<>(queries));
        this.outputDir = config.getOutputDir().normalize();  // ← /path/./ 같은 어색한 경로 정리
        this.connector = new DatabaseConnector(config);
        this.dataSource = connector.getDataSource();
    }

    // ─── 전체 실행 진입점 ─────────────────────────────────────────────────────

    public QuerySummary executeAll() {
        int threadCount = config.getThreadCount();
        log.info("Starting execution of {} queries using {} threads.", queries.size(), threadCount);
        log.info("Output directory: {}", outputDir);

        ensureOutputDir();
        detectDuplicateTitles();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<QueryResult>> tasks = new ArrayList<>();
        
        int index = 1;
        for (QueryParser.QueryInfo query : queries) {
            final int idx = index++;
            tasks.add(() -> executeQueryWithRetry(query, idx));
        }

        Instant overallStart = Instant.now();
        List<QueryResult> results = new ArrayList<>();

        try {
            List<Future<QueryResult>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                int currentSeq = i + 1;
                QueryParser.QueryInfo queryInfo = queries.get(i);
                try {
                    results.add(futureGetWithTimeout(futures.get(i), 24, TimeUnit.HOURS)); // 타임아웃 방어막 24시간
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    String msg = (cause != null)
                            ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                            : e.getMessage();
                    log.error("[스레드 실행 오류] 쿼리 작업 스레드가 비정상 종료되었습니다: {}", msg, e);
                    results.add(new QueryResult(currentSeq, queryInfo.getTitle(), false, 0, Duration.ZERO, msg, null, false, queryInfo.getSql()));
                }
            }
        } catch (InterruptedException e) {
            log.error("[실행 중단] 쿼리 실행이 외부 신호에 의해 중단되었습니다 (Interrupted).");
            log.error("가이드: 프로그램이 강제 종료(Ctrl+C 또는 kill 시그널)되었을 가능성이 있습니다.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("[경고] 일부 스레드가 10초 안에 종료되지 않아 강제 종료합니다.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            connector.close();
        }

        // 결과 정렬 (seq 순)
        results.sort(Comparator.comparingInt(QueryResult::getSeq));

        Duration totalDuration = Duration.between(overallStart, Instant.now());
        printSummary(results, totalDuration);
        writeTextReport(results, totalDuration);
        writeJsonReport(results, totalDuration);
        writeCsvSummaryReport(results); // _00_summary.csv 벤치마킹 보고서 생성
        long successCount = results.stream().filter(QueryResult::isSuccess).count();
        return new QuerySummary(results.size(), successCount, results.size() - successCount, totalDuration, outputDir);
    }

    private static <T> T futureGetWithTimeout(Future<T> future, long timeout, TimeUnit unit) throws Exception {
        try {
            return future.get(timeout, unit);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    // ─── 재시도 래퍼 ─────────────────────────────────────────────────────────

    private QueryResult executeQueryWithRetry(QueryParser.QueryInfo queryInfo, int index) {
        int maxRetries  = config.getQueryRetryCount();
        long delayMs    = config.getQueryRetryDelaySeconds() * 1000L;

        QueryResult result = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                log.warn("[{}] ◷ 재시도 {}/{}: {}초 후 재시도합니다...",
                        queryInfo.getTitle(), attempt, maxRetries, config.getQueryRetryDelaySeconds());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            result = executeQuery(queryInfo, index);

            if (result.isSuccess()) break;               // 성공 → 즉시 종료
            if (!result.isRetryable()) break;            // 재시도 불가 오류 → 즉시 종료
            if (attempt < maxRetries) {
                log.warn("[{}] 재시도 가능한 오류 ({}). 재시도합니다...",
                        queryInfo.getTitle(), result.getErrorMessage());
            }
        }

        return result;
    }

    // ─── 단일 쿼리 실행 ──────────────────────────────────────────────────────

    private QueryResult executeQuery(QueryParser.QueryInfo queryInfo, int index) {
        String title = queryInfo.getTitle();
        String sql   = queryInfo.getSql();
        
        // 쿼리 파일명 앞 순번(Index Prefix) 자릿수 조절하여 부여
        int totalQueries = queries.size();
        int width = Math.max(2, String.valueOf(totalQueries).length());
        String prefix = String.format("%0" + width + "d_", index);
        Path outputPath = outputDir.resolve(prefix + title + ".csv");

        // ① SQL 내용 비어있음
        if (sql == null || sql.isBlank()) {
            log.warn("[{}] 실행할 SQL 내용이 비어 있습니다. 건너뜁니다.", title);
            return new QueryResult(index, title, false, 0, Duration.ZERO, "SQL 내용이 비어 있습니다.", null, false, sql);
        }

        // ② SELECT/WITH 이외 문장 차단 (다중 쿼리 주입 공격 대비 전체 구절 검사)
        String[] subStatements = QueryParser.splitQueries(sql);
        for (String sub : subStatements) {
            String trimmedSub = sub.trim();
            if (trimmedSub.isEmpty()) continue;
            if (!ALLOWED_SQL_PATTERN.matcher(trimmedSub).matches()) {
                String firstToken = trimmedSub.split("\\s+")[0].toUpperCase();
                log.error("[{}] SELECT/WITH 이외의 SQL 문장은 실행하지 않습니다: '{}'", title, firstToken);
                log.error("[{}] 가이드: 이 도구는 SELECT 조회 전용입니다. DML/DDL은 지원하지 않습니다.", title);
                return new QueryResult(index, title, false, 0, Duration.ZERO,
                        "비허용 SQL 문: " + firstToken + " (SELECT/WITH 만 허용)", null, false, sql);
            }
        }

        // ③ SQL이 비정상적으로 긴 경우 경고
        if (sql.length() > 1_000_000) {
            log.warn("[{}] SQL 문장이 매우 깁니다 ({}자). 성능에 영향을 줄 수 있습니다.", title, sql.length());
        }

        // ④ 출력 파일 경로 체크
        if (Files.isDirectory(outputPath)) {
            String msg = "출력 경로가 파일이 아닌 디렉토리입니다: " + outputPath;
            log.error("[{}] {}", title, msg);
            return new QueryResult(index, title, false, 0, Duration.ZERO, msg, null, false, sql);
        }
        if (Files.exists(outputPath)) {
            log.warn("[{}] 출력 파일이 이미 존재합니다. 덮어씁니다: {}", title, outputPath);
        }

        log.info("[{}] 쿼리 실행 시작. 결과 파일: {}", title, outputPath);
        Instant start    = Instant.now();
        long rowCount    = 0;
        boolean success  = false;
        boolean retryable = false;
        String errorMessage = null;

        int queryTimeout  = config.getQueryTimeoutSeconds();
        int progressEvery = config.getProgressInterval();
        boolean writeBom  = config.isCsvBomEnabled();
        char delimiter    = config.getCsvDelimiter();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\n")
                .build();

        try (Connection conn = dataSource.getConnection();
             BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {

            // ⑤ BOM 추가 (csv.bom=true 이고 구분자가 , 일 때 → Excel 한글 깨짐 방지)
            if (writeBom) {
                writer.write('\uFEFF');
                log.debug("[{}] UTF-8 BOM을 CSV 앞에 기록했습니다.", title);
            }

            try {
                conn.setReadOnly(true);
            } catch (SQLException readOnlyError) {
                log.warn("[{}] 읽기 전용 커넥션 설정을 적용하지 못했습니다: {}", title, readOnlyError.getMessage());
            }
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.setFetchSize(config.getFetchSize());

                if (queryTimeout > 0) {
                    stmt.setQueryTimeout(queryTimeout);
                    log.debug("[{}] 쿼리 타임아웃 설정: {}초", title, queryTimeout);
                }

                try (ResultSet rs = stmt.executeQuery(sql)) {
                    ResultSetMetaData metaData   = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    if (columnCount == 0) {
                        log.warn("[{}] 쿼리 결과에 컬럼이 없습니다. 헤더만 있는 빈 CSV가 생성됩니다.", title);
                    }

                    // 헤더 작성
                    List<String> headers    = new ArrayList<>(columnCount);
                    int[] columnTypes       = new int[columnCount + 1];
                    for (int i = 1; i <= columnCount; i++) {
                        String label = metaData.getColumnLabel(i);
                        headers.add((label != null && !label.isBlank()) ? label : "col_" + i);
                        columnTypes[i] = metaData.getColumnType(i);
                    }
                    csvPrinter.printRecord(headers);

                    // 데이터 스트리밍 기록
                    List<Object> row = new ArrayList<>(columnCount);
                    long lastLogTime = System.currentTimeMillis();
                    while (rs.next()) {
                        row.clear();
                        for (int i = 1; i <= columnCount; i++) {
                            row.add(safeGetValue(rs, i, columnTypes[i], title));
                        }
                        csvPrinter.printRecord(row);
                        rowCount++;

                        // ① 진행률 표시 (progress.interval 마다 + 최소 2초 간격 시간 스로틀링)
                        if (progressEvery > 0 && rowCount % progressEvery == 0) {
                            long now = System.currentTimeMillis();
                            if (now - lastLogTime >= 2000) {
                                double elapsed = Duration.between(start, Instant.now()).toMillis() / 1000.0;
                                long speed = elapsed > 0 ? (long)(rowCount / elapsed) : 0;
                                log.info("[{}] 진행 중: {}건 | 소요: {}s | 속도: ~{}건/초",
                                        title,
                                        String.format("%,d", rowCount),
                                        String.format("%.1f", elapsed),
                                        String.format("%,d", speed));
                                lastLogTime = now;
                            }
                        }
                    }

                    conn.commit();
                    success = true;

                } catch (SQLTimeoutException e) {
                    safeRollback(conn, title);
                    errorMessage = String.format("쿼리 실행 시간 초과 (%d초 타임아웃): %s", queryTimeout, e.getMessage());
                    retryable = (queryTimeout == 0); // 타임아웃 설정 없이 발생했으면 재시도 가능
                    log.error("[{}] {}", title, errorMessage);
                    log.error("[{}] 가이드: config.csv의 query.timeout.seconds 값을 늘리거나, " +
                            "쿼리에 인덱스가 활용되는지 점검하세요.", title);
                    tryDeleteFile(outputPath, title);

                } catch (SQLException e) {
                    safeRollback(conn, title);
                    throw e;
                }

            } // end try(Statement)

        } catch (SQLException e) {
            success = false;
            errorMessage = e.getMessage();
            String sqlState  = e.getSQLState();
            int    errorCode = e.getErrorCode();

            // 재시도 가능 여부 판단 (연결 오류, DB 강제 종료 등)
            retryable = sqlState != null &&
                    RETRYABLE_SQL_STATES.stream().anyMatch(sqlState::startsWith);

            log.error("[{}] DB 실행 오류 (SQLState: {}, ErrorCode: {}): {}", title, sqlState, errorCode, errorMessage);

            if (sqlState != null && sqlState.startsWith("08")) {
                log.error("[{}] 가이드: DB 연결이 끊어졌습니다. 네트워크 상태 및 DB 서버 상태를 확인하세요.", title);
            } else if (sqlState != null && (sqlState.startsWith("42") || "S0002".equals(sqlState))) {
                if (errorMessage != null && (errorMessage.toLowerCase().contains("syntax error") ||
                        errorMessage.toLowerCase().contains("unexpected token"))) {
                    log.error("[{}] 가이드: SQL 문법 오류입니다. 쿼리 구문을 확인하세요.", title);
                } else {
                    log.error("[{}] 가이드: 테이블 또는 컬럼이 존재하지 않거나 접근 권한이 없습니다.", title);
                    log.error("[{}]         → 테이블/컬럼명의 철자, 스키마 이름, 계정 권한을 확인하세요.", title);
                }
            } else if (sqlState != null && sqlState.startsWith("28")) {
                log.error("[{}] 가이드: DB 접근 권한이 거부되었습니다. 해당 테이블에 SELECT 권한이 있는지 확인하세요.", title);
            } else if (sqlState != null && sqlState.startsWith("53")) {
                log.error("[{}] 가이드: DB 서버의 리소스(메모리·디스크)가 부족합니다. DBA에 문의하세요.", title);
            } else if (sqlState != null && sqlState.startsWith("57")) {
                log.error("[{}] 가이드: DB 서버에서 쿼리가 강제 종료(pg_cancel_backend 등)되었습니다.", title);
            }

            if (e.getNextException() != null) {
                log.error("[{}] 연쇄 SQL 예외: {}", title, e.getNextException().getMessage());
            }
            tryDeleteFile(outputPath, title);

        } catch (AccessDeniedException e) {
            success = false;
            errorMessage = "결과 파일 쓰기 권한 없음: " + outputPath;
            log.error("[{}] {}", title, errorMessage);
            log.error("[{}] 가이드: output.dir 디렉토리에 쓰기 권한이 있는지 확인하세요.", title);

        } catch (IOException e) {
            success = false;
            errorMessage = e.getMessage();
            log.error("[{}] 결과 파일 I/O 오류: {}", title, errorMessage);
            String msgLower = errorMessage != null ? errorMessage.toLowerCase() : "";
            if (msgLower.contains("no space left") || msgLower.contains("disk full")) {
                log.error("[{}] 가이드: 디스크 공간이 부족합니다. 불필요한 파일을 삭제하거나 다른 드라이브로 이동하세요.", title);
            } else if (msgLower.contains("too many open files")) {
                log.error("[{}] 가이드: 파일 디스크립터 한도 초과. 'ulimit -n' 값을 늘려주세요.", title);
            }
            tryDeleteFile(outputPath, title);

        } catch (OutOfMemoryError e) {
            success = false;
            errorMessage = "JVM 메모리 부족 (OutOfMemoryError)";
            log.error("[{}] {}: {}", title, errorMessage, e.getMessage());
            log.error("[{}] 가이드: 'java -Xmx2g -jar sender-<version>.jar query' 처럼 힙 크기를 늘려 실행하세요.", title);
            log.error("[{}]   또는 config.csv의 fetch.size 를 줄여 한 번에 가져오는 행 수를 제한하세요.", title);
            tryDeleteFile(outputPath, title);

        } catch (StackOverflowError e) {
            success = false;
            errorMessage = "스택 오버플로 (StackOverflowError)";
            log.error("[{}] {}", title, errorMessage, e);
            tryDeleteFile(outputPath, title);

        } catch (Error e) {
            success = false;
            errorMessage = "JVM 오류 (" + e.getClass().getSimpleName() + "): " + e.getMessage();
            log.error("[{}] 치명적인 JVM 오류 발생: {}", title, errorMessage, e);
            tryDeleteFile(outputPath, title);

        } catch (Exception e) {
            success = false;
            errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[{}] 예상치 못한 예외 발생 ({}): {}", title, e.getClass().getSimpleName(), errorMessage, e);
            tryDeleteFile(outputPath, title);
        }

        Duration duration = Duration.between(start, Instant.now());
        if (success) {
            log.info("[{}] 완료. {}건 기록, 소요 시간: {}s → {}",
                    title, String.format("%,d", rowCount),
                    String.format("%.2f", duration.toMillis() / 1000.0), outputPath);
        } else {
            log.error("[{}] 실패. 소요 시간: {}s / 원인: {}",
                    title, String.format("%.2f", duration.toMillis() / 1000.0), errorMessage);
        }

        return new QueryResult(index, title, success, rowCount, duration, errorMessage,
                success ? outputPath : null, retryable, sql);
    }

    // ─── safeGetValue ────────────────────────────────────────────────────────

    private Object safeGetValue(ResultSet rs, int columnIndex, int sqlType, String title) {
        try {
            Object value = rs.getObject(columnIndex);
            if (rs.wasNull() || value == null) return "";

            if (value instanceof Clob clob) {
                try {
                    long len = clob.length();
                    if (len > 10 * 1024 * 1024) {
                        return String.format("[CLOB: %.2fMB (OOM 방지를 위해 생략됨)]", len / (1024.0 * 1024.0));
                    }
                } catch (Exception ignore) {}
                try (Reader reader = clob.getCharacterStream()) {
                    StringBuilder sb = new StringBuilder((int) Math.min(clob.length(), 8192));
                    char[] buf = new char[4096];
                    int read;
                    while ((read = reader.read(buf)) != -1) sb.append(buf, 0, read);
                    return sb.toString();
                } catch (Exception ex) {
                    log.warn("[{}] 컬럼 {} CLOB 읽기 실패: {}", title, columnIndex, ex.getMessage());
                    return "[CLOB 읽기 오류]";
                }
            }
            if (value instanceof Blob blob) {
                try {
                    long len = blob.length();
                    if (len > 10 * 1024 * 1024) {
                        return String.format("[BLOB: %.2fMB (OOM 방지를 위해 생략됨)]", len / (1024.0 * 1024.0));
                    }
                } catch (Exception ignore) {}
                try (InputStream is = blob.getBinaryStream()) {
                    return Base64.getEncoder().encodeToString(is.readAllBytes());
                } catch (Exception ex) {
                    log.warn("[{}] 컬럼 {} BLOB 읽기 실패: {}", title, columnIndex, ex.getMessage());
                    return "[BLOB 읽기 오류]";
                }
            }
            if (value instanceof byte[] bytes) {
                if (bytes.length > 10 * 1024 * 1024) {
                    return String.format("[BYTE[]: %.2fMB (OOM 방지를 위해 생략됨)]", bytes.length / (1024.0 * 1024.0));
                }
                return Base64.getEncoder().encodeToString(bytes);
            }
            if (value instanceof BigDecimal bd)  return bd.toPlainString();
            if (sqlType == Types.SQLXML) {
                try {
                    SQLXML xml = rs.getSQLXML(columnIndex);
                    return xml != null ? xml.getString() : "";
                } catch (Exception ex) {
                    log.warn("[{}] 컬럼 {} SQLXML 읽기 실패: {}", title, columnIndex, ex.getMessage());
                    return "[SQLXML 읽기 오류]";
                }
            }
            return value;

        } catch (SQLException e) {
            log.warn("[{}] 컬럼 {} 값 읽기 중 SQL 오류 (오류 표시로 대체): {}", title, columnIndex, e.getMessage());
            return "[읽기 오류]";
        } catch (Exception e) {
            log.warn("[{}] 컬럼 {} 값 변환 중 오류 (오류 표시로 대체): {}", title, columnIndex, e.getMessage());
            return "[변환 오류]";
        }
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    private void ensureOutputDir() {
        if (!Files.exists(outputDir)) {
            try {
                Files.createDirectories(outputDir);
                log.info("[출력 디렉토리 생성] {}", outputDir);
            } catch (IOException e) {
                throw new RuntimeException("출력 디렉토리 생성 실패: " + outputDir +
                        "\n가이드: output.dir 경로의 상위 디렉토리가 존재하고 쓰기 권한이 있는지 확인하세요.", e);
            }
        } else if (!Files.isDirectory(outputDir)) {
            throw new RuntimeException("[출력 디렉토리 오류] output.dir 경로가 디렉토리가 아닙니다: " + outputDir);
        } else if (!Files.isWritable(outputDir)) {
            throw new RuntimeException("[출력 디렉토리 오류] output.dir 에 쓰기 권한이 없습니다: " + outputDir);
        }
    }

    private void detectDuplicateTitles() {
        Set<String> seen = new HashSet<>();
        for (QueryParser.QueryInfo q : queries) {
            if (!seen.add(q.getTitle())) {
                log.warn("[중복 제목 경고] '{}' 제목이 여러 쿼리에서 사용됩니다. " +
                        "같은 이름의 CSV 파일이 덮어써질 수 있습니다.", q.getTitle());
            }
        }
    }

    private void safeRollback(Connection conn, String title) {
        try {
            if (conn != null && !conn.isClosed()) conn.rollback();
        } catch (SQLException e) {
            log.warn("[{}] 롤백 중 오류 발생 (무시): {}", title, e.getMessage());
        }
    }

    private void tryDeleteFile(Path path, String title) {
        if (path == null) return;
        try {
            if (Files.deleteIfExists(path)) {
                log.debug("[{}] 불완전한 결과 파일 삭제 완료: {}", title, path);
            }
        } catch (IOException e) {
            log.warn("[{}] 불완전한 결과 파일 삭제 실패: {}", title, e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    // ─── 콘솔 요약 ───────────────────────────────────────────────────────────

    private void printSummary(List<QueryResult> results, Duration totalDuration) {
        if (results.isEmpty()) { log.warn("실행된 쿼리 결과가 없습니다."); return; }
        long successCount = results.stream().filter(QueryResult::isSuccess).count();
        long failedCount = results.size() - successCount;

        log.info("=========================================");
        log.info("            EXECUTION SUMMARY            ");
        log.info("=========================================");
        if (results.size() <= 10) {
            for (QueryResult res : results) {
                String dur = String.format("%.2f", res.getDuration().toMillis() / 1000.0) + "s";
                if (res.isSuccess()) {
                    log.info("- [{}] SUCCESS: {}건 기록, {} → {}",
                            res.getTitle(), String.format("%,d", res.getRowCount()), dur, res.getOutputPath());
                } else {
                    log.error("- [{}] FAILED: {} (소요: {})", res.getTitle(), res.getErrorMessage(), dur);
                }
            }
        } else {
            log.info("성공한 쿼리: {}건 / 실패한 쿼리: {}건 (총 {}건)", successCount, failedCount, results.size());
            if (failedCount > 0) {
                log.error("실패한 쿼리 목록:");
                for (QueryResult res : results) {
                    if (!res.isSuccess()) {
                        String dur = String.format("%.2f", res.getDuration().toMillis() / 1000.0) + "s";
                        log.error("  - [{}] FAILED: {} (소요: {})", res.getTitle(), res.getErrorMessage(), dur);
                    }
                }
            } else {
                log.info("모든 쿼리가 성공적으로 수행되었습니다.");
            }
            log.info("자세한 실행 내역은 아래 보고서 파일을 확인하세요:");
            log.info("  - 텍스트 보고서: {}", outputDir.resolve("execution-report.txt"));
            log.info("  - JSON 보고서  : {}", outputDir.resolve("execution-report.json"));
            log.info("  - CSV 요약본   : {}", outputDir.resolve("_00_summary.csv"));
        }
        log.info("-----------------------------------------");
        log.info("Total: {}, Success: {}, Failed: {}, 전체소요: {}s",
                results.size(), successCount, failedCount,
                String.format("%.2f", totalDuration.toMillis() / 1000.0));
        log.info("=========================================");
    }

    // ─── 텍스트 보고서 ───────────────────────────────────────────────────────

    private void writeTextReport(List<QueryResult> results, Duration totalDuration) {
        Path reportPath   = outputDir.resolve("execution-report.txt");
        String now        = LocalDateTime.now().format(TIMESTAMP_FMT);
        long successCount = results.stream().filter(QueryResult::isSuccess).count();

        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            writer.write("===========================================\n");
            writer.write("  air-bridge sender query - Execution Report\n");
            writer.write("===========================================\n");
            writer.write("실행 시각  : " + now + "\n");
            writer.write("출력 경로  : " + outputDir + "\n");
            writer.write("쿼리 수    : " + results.size() + "\n");
            writer.write("성공       : " + successCount + "\n");
            writer.write("실패       : " + (results.size() - successCount) + "\n");
            writer.write("전체 소요  : " + String.format("%.2f", totalDuration.toMillis() / 1000.0) + "s\n");
            writer.write("-------------------------------------------\n");
            writer.write(String.format("%-4s %-22s %-8s %12s %10s  %s\n",
                    "순번", "제목", "결과", "행 수", "소요(s)", "파일 경로 / 오류"));
            writer.write("-------------------------------------------\n");
            for (QueryResult res : results) {
                String status = res.isSuccess() ? "SUCCESS" : "FAILED";
                String dur    = String.format("%.2f", res.getDuration().toMillis() / 1000.0);
                String detail = res.isSuccess() ? String.valueOf(res.getOutputPath()) : res.getErrorMessage();
                writer.write(String.format("%-4d %-22s %-8s %12s %10s  %s\n",
                        res.getSeq(),
                        truncate(res.getTitle(), 22), status,
                        String.format("%,d", res.getRowCount()), dur, detail));
            }
            writer.write("===========================================\n");
            log.info("[보고서] 텍스트 보고서 저장 완료: {}", reportPath);
        } catch (IOException e) {
            log.warn("[보고서] 텍스트 보고서 파일 저장 실패 (무시): {}", e.getMessage());
        }
    }

    // ─── JSON 보고서 ─────────────────────────────────────────────────────────

    private void writeJsonReport(List<QueryResult> results, Duration totalDuration) {
        Path reportPath   = outputDir.resolve("execution-report.json");
        String now        = LocalDateTime.now().format(TIMESTAMP_FMT);
        long successCount = results.stream().filter(QueryResult::isSuccess).count();

        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"executedAt\": " + jsonStr(now) + ",\n");
            writer.write("  \"outputDir\": " + jsonStr(outputDir.toString()) + ",\n");
            writer.write("  \"totalQueries\": " + results.size() + ",\n");
            writer.write("  \"successCount\": " + successCount + ",\n");
            writer.write("  \"failedCount\": " + (results.size() - successCount) + ",\n");
            writer.write("  \"totalElapsedSeconds\": " +
                    String.format("%.3f", totalDuration.toMillis() / 1000.0) + ",\n");
            writer.write("  \"queries\": [\n");

            for (int i = 0; i < results.size(); i++) {
                QueryResult res = results.get(i);
                String comma = (i < results.size() - 1) ? "," : "";
                writer.write("    {\n");
                writer.write("      \"seq\": " + res.getSeq() + ",\n");
                writer.write("      \"title\": " + jsonStr(res.getTitle()) + ",\n");
                writer.write("      \"status\": " + jsonStr(res.isSuccess() ? "SUCCESS" : "FAILED") + ",\n");
                writer.write("      \"rowCount\": " + res.getRowCount() + ",\n");
                writer.write("      \"elapsedSeconds\": " +
                        String.format("%.3f", res.getDuration().toMillis() / 1000.0) + ",\n");
                if (res.isSuccess()) {
                    writer.write("      \"outputPath\": " + jsonStr(String.valueOf(res.getOutputPath())) + "\n");
                } else {
                    writer.write("      \"outputPath\": null,\n");
                    writer.write("      \"errorMessage\": " + jsonStr(res.getErrorMessage()) + "\n");
                }
                writer.write("    }" + comma + "\n");
            }
            writer.write("  ]\n");
            writer.write("}\n");
            log.info("[보고서] JSON 보고서 저장 완료: {}", reportPath);
        } catch (IOException e) {
            log.warn("[보고서] JSON 보고서 파일 저장 실패 (무시): {}", e.getMessage());
        }
    }

    // ─── CSV 요약 보고서 (_00_summary.csv) ───────────────────────────────────

    private void writeCsvSummaryReport(List<QueryResult> results) {
        Path reportPath = outputDir.resolve("_00_summary.csv");
        boolean writeBom = config.isCsvBomEnabled();
        char delimiter = config.getCsvDelimiter();

        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\r\n") // Excel 표준 줄바꿈
                .build();

        try (BufferedWriter writer = Files.newBufferedWriter(reportPath, StandardCharsets.UTF_8);
             CSVPrinter csvPrinter = new CSVPrinter(writer, csvFormat)) {

            if (writeBom) {
                writer.write('\uFEFF');
            }

            // 헤더 작성
            csvPrinter.printRecord("seq", "title", "status", "row_count", "elapsed_ms", "sql_head", "error_message");

            for (QueryResult res : results) {
                String cleanSql = res.getSql() != null ? res.getSql().replaceAll("\\s+", " ").trim() : "";
                String sqlHead = cleanSql.length() > 200 ? cleanSql.substring(0, 197) + "..." : cleanSql;
                String status = res.isSuccess() ? "OK" : "ERROR";
                String err = res.isSuccess() ? "" : res.getErrorMessage();

                csvPrinter.printRecord(
                        res.getSeq(),
                        res.getTitle(),
                        status,
                        res.isSuccess() ? String.valueOf(res.getRowCount()) : "",
                        String.valueOf(res.getDuration().toMillis()),
                        sqlHead,
                        err
                );
            }
            log.info("[보고서] CSV 요약 보고서 저장 완료: {}", reportPath);
        } catch (IOException e) {
            log.warn("[보고서] CSV 요약 보고서 파일 저장 실패 (무시): {}", e.getMessage());
        }
    }

    /** JSON 문자열 이스케이프 헬퍼 */
    private static String jsonStr(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }

    // ─── Inner class: QueryResult ─────────────────────────────────────────────

    public record QuerySummary(int totalQueries, long successCount, long failedCount,
                               Duration totalDuration, Path outputDir) {
    }

    static class QueryResult {
        private final int      seq;
        private final String   title;
        private final boolean  success;
        private final long     rowCount;
        private final Duration duration;
        private final String   errorMessage;
        private final Path     outputPath;
        private final boolean  retryable;
        private final String   sql;

        public QueryResult(int seq, String title, boolean success, long rowCount,
                           Duration duration, String errorMessage, Path outputPath,
                           boolean retryable, String sql) {
            this.seq          = seq;
            this.title        = title;
            this.success      = success;
            this.rowCount     = rowCount;
            this.duration     = duration;
            this.errorMessage = errorMessage;
            this.outputPath   = outputPath;
            this.retryable    = retryable;
            this.sql          = sql;
        }

        public int      getSeq()          { return seq; }
        public String   getTitle()        { return title; }
        public boolean  isSuccess()       { return success; }
        public long     getRowCount()     { return rowCount; }
        public Duration getDuration()     { return duration; }
        public String   getErrorMessage() { return errorMessage; }
        public Path     getOutputPath()   { return outputPath; }
        public boolean  isRetryable()     { return retryable; }
        public String   getSql()          { return sql; }
    }
}
