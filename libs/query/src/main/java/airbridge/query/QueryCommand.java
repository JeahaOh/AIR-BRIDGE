package airbridge.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "query",
        mixinStandardHelpOptions = false,
        description = "Run SELECT queries and write CSV files for later encoding.",
        subcommands = QueryCommand.InitCommand.class
)
public final class QueryCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(QueryCommand.class);
    private static final Path DEFAULT_CONFIG = Paths.get("config.csv");
    private static final Path DEFAULT_SQL = Paths.get("queries.sql");

    @Option(names = "--config", paramLabel = "FILE", description = "Query config CSV path (default: config.csv)")
    private Path configPath = DEFAULT_CONFIG;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
    private boolean help;

    @Option(names = "--sql", paramLabel = "FILE", description = "SQL file path (default: queries.sql)")
    private Path sqlPath = DEFAULT_SQL;

    @Option(names = {"--out", "--out-dir"}, paramLabel = "DIR",
            description = "Directory for query CSV and report output. Overrides config.csv output.dir")
    private Path outputDir;

    @Option(names = {"--list", "--dryrun"}, description = "List parsed queries without opening a DB connection")
    private boolean listOnly;

    @Override
    public Integer call() {
        if (listOnly) {
            return runListMode(resolved(sqlPath));
        }
        return runExecutionMode(resolved(configPath), resolved(sqlPath), outputDir);
    }

    private static Integer runExecutionMode(Path configPath, Path sqlPath, Path outputDir) {
        try {
            QueryConfig config = QueryConfig.loadFromPath(configPath);
            if (outputDir != null) {
                config.overrideOutputDir(outputDir);
            }

            List<QueryParser.QueryInfo> queries = QueryParser.parseFromPath(sqlPath);
            if (queries.isEmpty()) {
                log.warn("queries.sql에서 실행할 쿼리를 찾을 수 없습니다: {}", sqlPath);
                return 0;
            }

            log.info("[QUERY] config: {}", configPath);
            log.info("[QUERY] sql: {}", sqlPath);
            log.info("[QUERY] out: {}", config.getOutputDir());
            log.info("[QUERY] count: {}", queries.size());

            QueryExecutor.QuerySummary summary = new QueryExecutor(config, queries).executeAll();
            if (summary.failedCount() > 0) {
                log.error("[QUERY] failed: {}/{} queries", summary.failedCount(), summary.totalQueries());
                return 1;
            }
            log.info("[QUERY] complete: {}", summary.outputDir());
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.error("[QUERY] {}", e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            log.error("[QUERY] {}", messageOf(e));
            return 1;
        } catch (IOException e) {
            log.error("[QUERY] {}", e.getMessage());
            return 1;
        }
    }

    private static Integer runListMode(Path sqlPath) {
        try {
            List<QueryParser.QueryInfo> queries = QueryParser.parseFromPath(sqlPath);
            if (queries.isEmpty()) {
                System.out.println("[QUERY] 실행 가능한 쿼리가 없습니다: " + sqlPath);
                return 0;
            }

            System.out.println();
            System.out.println("실행 예정 쿼리 목록 (" + queries.size() + "건):");
            int width = Math.max(2, String.valueOf(queries.size()).length());
            for (int i = 0; i < queries.size(); i++) {
                QueryParser.QueryInfo query = queries.get(i);
                String cleanSql = query.getSql().replaceAll("\\s+", " ").trim();
                if (cleanSql.length() > 80) {
                    cleanSql = cleanSql.substring(0, 77) + "...";
                }
                System.out.printf("  [%0" + width + "d] %-20s | %s%n", i + 1, query.getTitle(), cleanSql);
            }
            System.out.println();
            return 0;
        } catch (IOException e) {
            System.err.println("[QUERY][ERROR] " + e.getMessage());
            return 1;
        }
    }

    private static Path resolved(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Command(name = "init", mixinStandardHelpOptions = false,
            description = "Create default config.csv and queries.sql templates.")
    public static final class InitCommand implements Callable<Integer> {
        @Option(names = "--config", paramLabel = "FILE", description = "Config template path (default: config.csv)")
        private Path configPath = DEFAULT_CONFIG;

        @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
        private boolean help;

        @Option(names = "--sql", paramLabel = "FILE", description = "SQL template path (default: queries.sql)")
        private Path sqlPath = DEFAULT_SQL;

        @Option(names = {"--force", "-f"}, description = "Overwrite existing template files")
        private boolean force;

        @Override
        public Integer call() {
            Path config = resolved(configPath);
            Path sql = resolved(sqlPath);
            if (!force && (Files.exists(config) || Files.exists(sql))) {
                if (Files.exists(config)) {
                    System.err.println("[QUERY][ERROR] already exists: " + config);
                }
                if (Files.exists(sql)) {
                    System.err.println("[QUERY][ERROR] already exists: " + sql);
                }
                System.err.println("[QUERY] use --force to overwrite template files");
                return 1;
            }

            try {
                createParentDirectories(config);
                createParentDirectories(sql);
                QueryConfig.createDefaultTemplate(config);
                QueryParser.createDefaultTemplate(sql);
                System.out.println("[QUERY] created: " + config);
                System.out.println("[QUERY] created: " + sql);
                return 0;
            } catch (IOException e) {
                System.err.println("[QUERY][ERROR] " + e.getMessage());
                return 1;
            }
        }

        private static void createParentDirectories(Path file) throws IOException {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }
    }
}
