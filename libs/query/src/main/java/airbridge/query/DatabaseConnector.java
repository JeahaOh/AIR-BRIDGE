package airbridge.query;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

public final class DatabaseConnector implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConnector.class);

    private final QueryConfig config;
    private HikariDataSource dataSource;

    public DatabaseConnector(QueryConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    private static String detectDriverClassName(String url) {
        if (url.startsWith("jdbc:mysql:"))       return "com.mysql.cj.jdbc.Driver";
        if (url.startsWith("jdbc:postgresql:"))   return "org.postgresql.Driver";
        if (url.startsWith("jdbc:oracle:"))       return "oracle.jdbc.OracleDriver";
        if (url.startsWith("jdbc:sqlserver:"))    return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (url.startsWith("jdbc:mariadb:"))      return "org.mariadb.jdbc.Driver";
        if (url.startsWith("jdbc:db2:"))          return "com.ibm.db2.jcc.DB2Driver";
        if (url.startsWith("jdbc:h2:"))           return "org.h2.Driver";
        return null;
    }

    public synchronized DataSource getDataSource() {
        if (dataSource != null) {
            return dataSource;
        }

        // 필수 값 null 체크 (QueryConfig.validateRequiredKeys() 이후이지만 방어 코드로 추가)
        String url = config.getDbUrl();
        String username = config.getDbUsername();
        String password = config.getDbPassword();

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                "[DB 연결 오류] db.url이 비어 있습니다. config.csv에서 db.url 항목을 올바르게 설정하세요."
            );
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException(
                "[DB 연결 오류] db.username이 비어 있습니다. config.csv에서 db.username 항목을 설정하세요."
            );
        }

        int threadCount = config.getThreadCount();
        int maxPoolSize = threadCount + 2; // 스레드 수보다 약간 여유있게 커넥션 설정

        log.info("Initializing HikariCP connection pool with URL: {}", maskSensitiveUrlParts(url));
        log.info("Username: {}, Connection Pool Size: {}", username, maxPoolSize);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(maxPoolSize);
        hikariConfig.setMinimumIdle(Math.min(2, maxPoolSize));
        int connTimeoutMs = config.getDbConnectionTimeoutSeconds() * 1000;
        hikariConfig.setIdleTimeout(30_000);
        hikariConfig.setConnectionTimeout(connTimeoutMs);
        // 커넥션 획득 실패 시 최대 대기 시간: 설정 시간을 넘기면 DB 문제로 간주
        hikariConfig.setInitializationFailTimeout(connTimeoutMs);

        // JDBC 드라이버 감지 및 명시 로드
        String driverClassName = detectDriverClassName(url);
        if (driverClassName != null) {
            log.info("Detected JDBC Driver: {}", driverClassName);
            try {
                Class.forName(driverClassName);
                hikariConfig.setDriverClassName(driverClassName);
            } catch (ClassNotFoundException e) {
                // 내장 드라이버 JAR이 누락된 경우 (일반적으로 발생하지 않음)
                throw new IllegalStateException(
                    "[DB 연결 오류] JDBC 드라이버 클래스를 찾을 수 없습니다: " + driverClassName + "\n" +
                    "가이드: 해당 DB 드라이버 JAR이 클래스패스에 포함되어 있는지 확인하세요. " +
                    "sender query는 MySQL/PostgreSQL/Oracle/MSSQL/MariaDB/DB2/H2 드라이버를 내장하고 있습니다.", e
                );
            }
        } else {
            log.warn("[DB 연결 경고] JDBC URL 형식을 인식하지 못했습니다: '{}'\n" +
                    "가이드: 지원 드라이버: MySQL, PostgreSQL, Oracle, SQL Server, MariaDB, IBM DB2, H2\n" +
                    "      지원하지 않는 DB의 경우 드라이버 JAR을 별도로 추가하고 -cp 옵션으로 실행해야 합니다.",
                    maskSensitiveUrlParts(url));
        }

        // MySQL/MariaDB 전용 성능 속성 (다른 DB는 무시됨)
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }

        try {
            dataSource = new HikariDataSource(hikariConfig);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("Connection refused") || msg.contains("connect")) {
                throw new RuntimeException(
                    "[DB 연결 오류] 데이터베이스에 연결할 수 없습니다.\n" +
                    "가이드: 다음 사항을 확인하세요.\n" +
                    "  1. db.url의 호스트/포트가 올바른지 (현재: " + maskSensitiveUrlParts(url) + ")\n" +
                    "  2. DB 서버가 실행 중인지\n" +
                    "  3. 방화벽이나 네트워크 정책으로 접속이 차단되지 않았는지", e
                );
            } else if (msg.contains("Access denied") || msg.contains("password")) {
                throw new RuntimeException(
                    "[DB 연결 오류] 데이터베이스 인증에 실패했습니다.\n" +
                    "가이드: config.csv의 db.username과 db.password가 올바른지 확인하세요.", e
                );
            } else if (msg.contains("Unknown database") || msg.contains("does not exist")) {
                throw new RuntimeException(
                    "[DB 연결 오류] 데이터베이스(스키마)가 존재하지 않습니다.\n" +
                    "가이드: db.url에 지정된 데이터베이스 이름이 실제로 존재하는지 확인하세요.", e
                );
            } else {
                throw new RuntimeException(
                    "[DB 연결 오류] 커넥션 풀 초기화에 실패했습니다: " + msg + "\n" +
                    "가이드: config.csv의 db.url, db.username, db.password 설정을 다시 확인하세요.", e
                );
            }
        }

        return dataSource;
    }

    @Override
    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            log.info("Closing connection pool...");
            try {
                dataSource.close();
            } catch (Exception e) {
                log.warn("[경고] 커넥션 풀 종료 중 오류가 발생했습니다 (무시): {}", e.getMessage());
            } finally {
                dataSource = null;
            }
        }
    }

    private static String maskSensitiveUrlParts(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password|pwd)=([^;&\\s]+)", "$1=****");
    }
}
