package airbridge.query;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class QueryConfig {
    private static final Logger log = LoggerFactory.getLogger(QueryConfig.class);
    private static final String DEFAULT_FILE_NAME = "config.csv";

    // 필수 설정 키 목록
    private static final String[] REQUIRED_KEYS = {"db.url", "db.username", "db.password"};

    private final Map<String, String> settings = new HashMap<>();
    private Path calculatedOutputDir = null;

    // ─── QueryConfig key constants ──────────────────────────────────────────────────
    public static final String KEY_DB_URL                     = "db.url";
    public static final String KEY_DB_USERNAME                = "db.username";
    public static final String KEY_DB_PASSWORD                = "db.password";
    public static final String KEY_THREAD_COUNT               = "thread.count";
    public static final String KEY_FETCH_SIZE                 = "fetch.size";
    public static final String KEY_CSV_DELIMITER              = "csv.delimiter";
    public static final String KEY_CSV_BOM                    = "csv.bom";
    public static final String KEY_OUTPUT_DIR                 = "output.dir";
    public static final String KEY_QUERY_TIMEOUT_SECONDS      = "query.timeout.seconds";
    public static final String KEY_QUERY_RETRY_COUNT          = "query.retry.count";
    public static final String KEY_QUERY_RETRY_DELAY_SECONDS  = "query.retry.delay.seconds";
    public static final String KEY_PROGRESS_INTERVAL          = "progress.interval";
    public static final String KEY_DB_CONNECTION_TIMEOUT_SECONDS = "db.connection.timeout.seconds";

    /** 기본 파일명(config.csv)에서 설정을 로드합니다. */
    public static QueryConfig load() throws IOException {
        Path configPath = Paths.get(DEFAULT_FILE_NAME).toAbsolutePath();
        return loadFromPath(configPath);
    }

    /**
     * 지정한 경로의 CSV 파일에서 설정을 로드합니다.
     * 단위 테스트에서 임시 파일을 직접 지정할 때도 사용합니다.
     */
    public static QueryConfig loadFromPath(Path configPath) throws IOException {
        log.info("Loading config from: {}", configPath);

        // 디렉토리로 오인된 경우 즉시 실패
        if (Files.isDirectory(configPath)) {
            throw new IOException(
                "[설정 오류] config.csv 경로가 파일이 아닌 디렉토리입니다: " + configPath + "\n" +
                "가이드: 해당 디렉토리를 삭제하거나 이름을 바꾸고, 'sender query init' 으로 다시 생성하세요."
            );
        }

        // 심볼릭 링크 경고 (동작은 하되 경고만)
        if (Files.isSymbolicLink(configPath)) {
            log.warn("[설정 경고] config.csv 가 심볼릭 링크입니다: {} → {}",
                    configPath, Files.readSymbolicLink(configPath));
        }

        // 파일 존재 확인
        if (!Files.exists(configPath)) {
            throw new IOException(
                "[설정 오류] config.csv 파일을 찾을 수 없습니다: " + configPath + "\n" +
                "가이드: 'sender query init' 명령으로 기본 템플릿을 생성하세요."
            );
        }

        // 파일 읽기 권한 확인
        if (!Files.isReadable(configPath)) {
            throw new IOException(
                "[설정 오류] config.csv 파일에 읽기 권한이 없습니다: " + configPath + "\n" +
                "가이드: 파일 권한(Permission)을 확인하고 읽기(read) 권한을 부여해 주세요."
            );
        }

        // 빈 파일 확인
        if (Files.size(configPath) == 0) {
            throw new IOException(
                "[설정 오류] config.csv 파일이 비어 있습니다: " + configPath + "\n" +
                "가이드: 'sender query init' 으로 기본 양식을 다시 생성하거나 직접 내용을 채워주세요."
            );
        }

        // UTF-8 BOM (EF BB BF) 감지 및 제거 후 CSV 파싱
        // Windows 메모장 등에서 UTF-8로 저장하면 BOM이 붙는 경우가 있음
        byte[] rawBytes = Files.readAllBytes(configPath);
        String rawContent = new String(rawBytes, StandardCharsets.UTF_8);
        if (rawContent.startsWith("\uFEFF")) {
            log.warn("[설정 경고] config.csv에 UTF-8 BOM이 감지되어 제거합니다. (Windows 메모장 저장 파일에서 발생할 수 있습니다.)");
            rawContent = rawContent.substring(1);
        }

        QueryConfig config = new QueryConfig();
        try (java.io.StringReader stringReader = new java.io.StringReader(rawContent);
             CSVParser csvParser = new CSVParser(stringReader, CSVFormat.DEFAULT
                     .builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build())) {

            for (CSVRecord csvRecord : csvParser) {
                if (csvRecord.size() >= 2) {
                    String key = csvRecord.get(0);
                    String value = csvRecord.get(1);
                    if (key != null && !key.isBlank()) {
                        config.settings.put(key, value);
                    }
                } else if (csvRecord.size() == 1) {
                    log.warn("[설정 경고] config.csv의 {}번 행이 key만 있고 value가 없습니다 (행 무시): '{}'",
                            csvRecord.getRecordNumber(), csvRecord.get(0));
                }
            }
        } catch (AccessDeniedException e) {
            throw new IOException(
                "[설정 오류] config.csv 파일에 접근이 거부되었습니다: " + configPath + "\n" +
                "가이드: 파일의 읽기 권한을 확인해 주세요.", e
            );
        } catch (IOException e) {
            throw new IOException(
                "[설정 오류] config.csv 파일 파싱 중 오류가 발생했습니다: " + e.getMessage() + "\n" +
                "가이드: CSV 형식이 올바른지 확인하세요. 형식: key,value (예: db.url,jdbc:mysql://...)", e
            );
        }

        // 필수 키 누락 검사
        config.validateRequiredKeys();

        // 값 범위 검증 (경고 수준)
        config.validateValueRanges();

        return config;
    }

    /**
     * 필수 설정 키 누락 여부를 검사합니다.
     * 하나라도 빠져 있으면 IOException을 던집니다.
     */
    private void validateRequiredKeys() throws IOException {
        StringBuilder missing = new StringBuilder();
        for (String key : REQUIRED_KEYS) {
            String val = settings.get(key);
            // db.password는 빈 값(패스워드 없는 계정)을 허용, 나머지 필수 키는 blank 불가
            boolean isPasswordKey = "db.password".equals(key);
            if (!isPasswordKey && (val == null || val.isBlank())) {
                if (!missing.isEmpty()) missing.append(", ");
                missing.append("'").append(key).append("'");
            } else if (isPasswordKey && val == null) {
                // db.password 키 자체가 config.csv에 아예 없는 경우
                if (!missing.isEmpty()) missing.append(", ");
                missing.append("'db.password' (빈 값으로라도 항목이 존재해야 합니다)");
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException(
                "[설정 오류] config.csv에 필수 설정 값이 누락되어 있습니다: " + missing + "\n" +
                "가이드: config.csv 파일을 열어 누락된 항목들을 채워주세요.\n" +
                "  필수 항목: db.url (JDBC URL), db.username (DB 계정), db.password (DB 비밀번호)"
            );
        }
    }

    /**
     * 설정 값의 타당성을 검사하고, 비정상적인 값은 경고 로그를 남깁니다.
     */
    private void validateValueRanges() {
        // JDBC URL 형식 기초 검증
        String url = settings.get("db.url");
        if (url != null && !url.startsWith("jdbc:")) {
            log.warn("[설정 경고] db.url이 'jdbc:'로 시작하지 않습니다. 올바른 JDBC URL 형식인지 확인하세요: '{}'", url);
        }

        // thread.count 범위 검증
        int threadCount = getInt(KEY_THREAD_COUNT, 4);
        if (threadCount <= 0) {
            log.warn("[설정 경고] thread.count가 0 이하입니다 ({}). 기본값 4로 동작합니다.", threadCount);
        } else if (threadCount > 50) {
            log.warn("[설정 경고] thread.count가 매우 큽니다 ({}). DB 커넥션 과부하가 발생할 수 있습니다.", threadCount);
        }

        // fetch.size 범위 검증
        int fetchSize = getInt(KEY_FETCH_SIZE, 1000);
        if (fetchSize <= 0) {
            log.warn("[설정 경고] fetch.size가 0 이하입니다 ({}). 기본값 1000으로 동작합니다.", fetchSize);
        } else if (fetchSize > 100000) {
            log.warn("[설정 경고] fetch.size가 매우 큽니다 ({}). JVM 메모리 부족이 발생할 수 있습니다.", fetchSize);
        }

        // query.timeout.seconds 범위 검증 (0 = 타임아웃 없음)
        int queryTimeout = getInt(KEY_QUERY_TIMEOUT_SECONDS, 0);
        if (queryTimeout < 0) {
            log.warn("[설정 경고] query.timeout.seconds가 음수입니다 ({}). 0(타임아웃 없음)으로 동작합니다.", queryTimeout);
        } else if (queryTimeout > 0) {
            log.info("[설정] 쿼리 타임아웃 설정: {}초", queryTimeout);
        }

        // db.connection.timeout.seconds 범위 검증
        int connTimeout = getInt(KEY_DB_CONNECTION_TIMEOUT_SECONDS, 30);
        if (connTimeout <= 0) {
            log.warn("[설정 경고] db.connection.timeout.seconds가 0 이하입니다 ({}). 기본값 30초로 동작합니다.", connTimeout);
        }

        // query.retry.count 범위 검증
        int retryCount = getInt(KEY_QUERY_RETRY_COUNT, 0);
        if (retryCount < 0) {
            log.warn("[설정 경고] query.retry.count가 음수입니다 ({}). 0(재시도 없음)으로 동작합니다.", retryCount);
        } else if (retryCount > 10) {
            log.warn("[설정 경고] query.retry.count가 매우 큽니다 ({}). 재시도가 너무 많으면 처리 시간이 매우 길어질 수 있습니다.", retryCount);
        } else if (retryCount > 0) {
            int delaySeconds = getInt(KEY_QUERY_RETRY_DELAY_SECONDS, 5);
            log.info("[설정] 쿼리 재시도: {}회, 대기 {}초", retryCount, delaySeconds);
        }

        // csv.bom 설정 안내
        if (getBoolean(KEY_CSV_BOM, false)) {
            log.info("[설정] csv.bom=true: CSV 파일 앞에 UTF-8 BOM을 추가합니다. (Excel에서 한글 깨짐 방지)");
        }

        // output.dir 경로 검증
        String outputDir = get(KEY_OUTPUT_DIR);
        if (outputDir != null && !outputDir.isBlank()) {
            Path outPath = Paths.get(outputDir);
            if (Files.exists(outPath) && !Files.isDirectory(outPath)) {
                log.warn("[설정 경고] output.dir 경로가 디렉토리가 아닌 파일입니다: '{}'", outPath.toAbsolutePath());
            } else if (!Files.exists(outPath)) {
                log.info("[설정] output.dir '{}' 가 없어 실행 시 자동 생성합니다.", outPath.toAbsolutePath());
            }
        }
    }

    public String get(String key) {
        return settings.get(key);
    }

    public String get(String key, String defaultValue) {
        return settings.getOrDefault(key, defaultValue);
    }

    public void overrideOutputDir(Path outputDir) {
        if (outputDir == null) {
            return;
        }
        settings.put(KEY_OUTPUT_DIR, outputDir.toAbsolutePath().normalize().toString());
        calculatedOutputDir = null;
    }

    public int getInt(String key, int defaultValue) {
        String val = settings.get(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("[설정 경고] '{}' 설정의 값 '{}'이 숫자가 아닙니다. 기본값 {}을 사용합니다.", key, val, defaultValue);
            return defaultValue;
        }
    }

    public String getDbUrl()      { return get(KEY_DB_URL); }
    public String getDbUsername() { return get(KEY_DB_USERNAME); }

    public String getDbPassword() {
        String pw = get(KEY_DB_PASSWORD);
        if (pw != null && !pw.isBlank()) {
            return pw;
        }

        // 2순위: OS 환경변수 감지 (보안 권장)
        String envPw = System.getenv("DB_PASSWORD");
        if (envPw != null && !envPw.isBlank()) {
            return envPw;
        }
        
        // 3순위: JVM System Property 감지 (-Ddb.password)
        String propPw = System.getProperty("db.password");
        if (propPw != null && !propPw.isBlank()) {
            return propPw;
        }

        return "";
    }

    public int getThreadCount() {
        int count = getInt(KEY_THREAD_COUNT, 4);
        return (count <= 0) ? 4 : count;
    }

    public int getFetchSize() {
        int size = getInt(KEY_FETCH_SIZE, 1000);
        return (size <= 0) ? 1000 : size;
    }

    /** 쿼리 Statement 타임아웃 (초). 0이면 무제한. */
    public int getQueryTimeoutSeconds() {
        int t = getInt(KEY_QUERY_TIMEOUT_SECONDS, 0);
        return (t < 0) ? 0 : t;
    }

    /** DB 커넥션 획득 타임아웃 (초). 기본 30초. */
    public int getDbConnectionTimeoutSeconds() {
        int t = getInt(KEY_DB_CONNECTION_TIMEOUT_SECONDS, 30);
        return (t <= 0) ? 30 : t;
    }

    /** 쿼리 실패 시 재시도 횟수. 0이면 재시도 없음. */
    public int getQueryRetryCount() {
        int n = getInt(KEY_QUERY_RETRY_COUNT, 0);
        return (n < 0) ? 0 : Math.min(n, 10);
    }

    /** 재시도 전 대기 시간 (초). 기본 5초. */
    public int getQueryRetryDelaySeconds() {
        int d = getInt(KEY_QUERY_RETRY_DELAY_SECONDS, 5);
        return (d < 0) ? 0 : d;
    }

    /** CSV 파일 첫 바이트에 UTF-8 BOM을 쓸지 여부. Excel 한글 깨짐 방지. */
    public boolean isCsvBomEnabled() {
        return getBoolean(KEY_CSV_BOM, false);
    }

    /** 진행률 로그 출력 주기 (행 수). 0이면 출력 안 함. */
    public int getProgressInterval() {
        int v = getInt(KEY_PROGRESS_INTERVAL, 50_000);
        return (v <= 0) ? 0 : v;
    }

    /**
     * 결과 CSV 파일을 저장할 디렉토리.
     * 설정이 없으면 현재 실행 디렉토리 하위에 실행시각별 날짜 폴더("run_yyyyMMdd_HHmmss")를 생성하여 사용합니다.
     */
    public synchronized Path getOutputDir() {
        if (calculatedOutputDir != null) {
            return calculatedOutputDir;
        }
        String dir = get(KEY_OUTPUT_DIR);
        if (dir == null || dir.isBlank()) {
            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            calculatedOutputDir = Paths.get("run_" + ts).toAbsolutePath();
            log.info("[설정] output.dir이 지정되지 않아 실행시각별 날짜 폴더를 자동 생성합니다: {}", calculatedOutputDir);
        } else {
            calculatedOutputDir = Paths.get(dir).toAbsolutePath();
        }
        return calculatedOutputDir;
    }

    public char getCsvDelimiter() {
        String delim = get(KEY_CSV_DELIMITER, ",");
        if (delim == null || delim.isBlank()) {
            log.warn("[설정 경고] csv.delimiter가 비어있습니다. 기본 구분자 ',' 를 사용합니다.");
            return ',';
        }
        if (delim.length() > 1) {
            log.warn("[설정 경고] csv.delimiter는 단일 문자여야 합니다. '{}' 중 첫 번째 문자 '{}' 만 사용합니다.",
                    delim, delim.charAt(0));
        }
        return delim.charAt(0);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = settings.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(val.trim()) || "1".equals(val.trim()) || "yes".equalsIgnoreCase(val.trim());
    }

    public static void createDefaultTemplate(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("key,value\n");
            writer.write("# -- DB 접속 정보 --------------------------------------------------\n");
            writer.write("db.url,jdbc:mysql://localhost:3306/your_database?useSSL=false&serverTimezone=UTC&useCursorFetch=true\n");
            writer.write("db.username,root\n");
            writer.write("# db.password: 비워두고 DB_PASSWORD 환경변수 또는 -Ddb.password 사용을 권장\n");
            writer.write("db.password,\n");
            writer.write("# -- 실행 옵션 ----------------------------------------------------\n");
            writer.write("thread.count,4\n");
            writer.write("fetch.size,1000\n");
            writer.write("# query.timeout.seconds: 쿼리 타임아웃 (초). 0=무제한\n");
            writer.write("query.timeout.seconds,0\n");
            writer.write("# db.connection.timeout.seconds: DB 커넥션 획득 대기 타임아웃 (초). 기본 30초\n");
            writer.write("db.connection.timeout.seconds,30\n");
            writer.write("# query.retry.count: 쿼리 실패 시 재시도 횟수. 0=재시도 없음\n");
            writer.write("query.retry.count,0\n");
            writer.write("# query.retry.delay.seconds: 재시도 전 대기 시간 (초). 기본 5초\n");
            writer.write("query.retry.delay.seconds,5\n");
            writer.write("# progress.interval: 진행률 로그 출력 주기 (행 수). 0=없음, 기본 50000\n");
            writer.write("progress.interval,50000\n");
            writer.write("# -- 출력 옵션 ----------------------------------------------------\n");
            writer.write("csv.delimiter,\",\"\n");
            writer.write("# csv.bom: Excel에서 한글 깨짐 방지를 위한 UTF-8 BOM 추가 (true/false)\n");
            writer.write("csv.bom,false\n");
            writer.write("# output.dir: CSV 결과 저장 경로. 비워두면 run_yyyyMMdd_HHmmss 폴더 자동 생성\n");
            writer.write("output.dir,\n");
        } catch (AccessDeniedException e) {
            throw new IOException(
                "[INIT ERROR] 파일 쓰기 권한이 없어 config.csv를 생성할 수 없습니다: " + path + "\n" +
                "가이드: 현재 디렉토리의 쓰기 권한을 확인하거나, 권한이 있는 디렉토리로 이동 후 실행하세요.", e
            );
        }
    }
}
