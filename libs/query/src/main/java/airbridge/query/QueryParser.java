package airbridge.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class QueryParser {
    private static final Logger log = LoggerFactory.getLogger(QueryParser.class);
    private static final String DEFAULT_FILE_NAME = "queries.sql";
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("[^a-zA-Z0-9가-힣ㄱ-ㅎㅏ-ㅣ\\s_\\-]");

    public static class QueryInfo {
        private final String sql;
        private final String title;

        public QueryInfo(String rawSql, String title) {
            // '#' 주석 라인 제거: '#' 비지원 DB(H2, Oracle 등)에서의 구문 에러 방지
            this.sql = rawSql.replaceAll("(?m)^\\s*#.*$\\n?", "").trim();
            this.title = title;
        }

        public String getSql() {
            return sql;
        }

        public String getTitle() {
            return title;
        }

        @Override
        public String toString() {
            return "QueryInfo{title='" + title + "', sql='" + sql.trim().replace("\n", " ") + "'}";
        }
    }

    /** 기본 파일명(queries.sql)에서 쿼리를 파싱합니다. */
    public static List<QueryInfo> parse() throws IOException {
        return parseFromPath(Paths.get(DEFAULT_FILE_NAME).toAbsolutePath());
    }

    /**
     * 지정한 경로의 SQL 파일에서 쿼리를 파싱합니다.
     * --sql=path CLI 옵션 또는 단위 테스트에서 사용합니다.
     */
    public static List<QueryInfo> parseFromPath(Path sqlPath) throws IOException {

        // 디렉토리로 오인된 경우 즉시 실패
        if (Files.isDirectory(sqlPath)) {
            throw new IOException(
                "[SQL 파일 오류] queries.sql 경로가 파일이 아닌 디렉토리입니다: " + sqlPath + "\n" +
                "가이드: 해당 디렉토리를 삭제하거나 이름을 바꾸고, 'sender query init' 으로 다시 생성하세요."
            );
        }

        // 심볼릭 링크 경고
        if (Files.isSymbolicLink(sqlPath)) {
            log.warn("[SQL 파일 경고] queries.sql 이 심볼릭 링크입니다: {} → {}",
                    sqlPath, Files.readSymbolicLink(sqlPath));
        }

        // 파일 존재 확인
        if (!Files.exists(sqlPath)) {
            throw new IOException(
                "[SQL 파일 오류] queries.sql 파일을 찾을 수 없습니다: " + sqlPath + "\n" +
                "가이드: 'sender query init' 명령으로 기본 SQL 템플릿을 생성하세요."
            );
        }

        // 파일 읽기 권한 확인
        if (!Files.isReadable(sqlPath)) {
            throw new IOException(
                "[SQL 파일 오류] queries.sql 파일에 읽기 권한이 없습니다: " + sqlPath + "\n" +
                "가이드: 파일 권한(Permission)을 확인하고 읽기(read) 권한을 부여해 주세요."
            );
        }

        // 빈 파일 확인
        long fileSize = Files.size(sqlPath);
        if (fileSize == 0) {
            throw new IOException(
                "[SQL 파일 오류] queries.sql 파일이 비어 있습니다: " + sqlPath + "\n" +
                "가이드: 실행할 SELECT 쿼리를 입력하고 각 쿼리를 세미콜론(;)으로 구분하세요.\n" +
                "       쿼리 직전에 '# 제목' 형식의 주석을 달면 해당 이름으로 CSV 파일이 생성됩니다."
            );
        }

        // 파일이 비정상적으로 큰 경우 경고 (>10MB)
        if (fileSize > 10 * 1024 * 1024) {
            log.warn("[SQL 파일 경고] queries.sql 파일 크기가 매우 큽니다 ({} MB). " +
                    "쿼리가 많거나 주석이 과도하게 많은지 확인하세요.",
                    String.format("%.1f", fileSize / (1024.0 * 1024.0)));
        }

        // 파일 읽기 및 UTF-8 BOM 제거
        byte[] rawBytes;
        try {
            rawBytes = Files.readAllBytes(sqlPath);
        } catch (AccessDeniedException e) {
            throw new IOException("[SQL 파일 오류] queries.sql 파일 접근이 거부되었습니다: " + sqlPath, e);
        }

        String content = new String(rawBytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            log.warn("[SQL 파일 경고] queries.sql에 UTF-8 BOM이 감지되어 제거합니다. (Windows 메모장 저장 파일에서 발생할 수 있습니다.)");
            content = content.substring(1);
        }

        // 공백/주석만 있는 경우 (2차 확인)
        if (content.isBlank()) {
            throw new IOException(
                "[SQL 파일 오류] queries.sql 파일에 실행 가능한 내용이 없습니다.\n" +
                "가이드: SELECT 쿼리를 입력하고 각 쿼리를 세미콜론(;)으로 구분하세요."
            );
        }

        List<QueryInfo> queries = parseContent(content);

        // 파싱 결과가 없는 경우 경고 (SELECT 없이 주석만 있는 경우 등)
        if (queries.isEmpty()) {
            log.warn("[SQL 파싱 경고] queries.sql에서 실행 가능한 쿼리를 찾을 수 없습니다.");
            log.warn("가이드: 각 쿼리가 세미콜론(;)으로 끝나는지, SELECT 문이 존재하는지 확인하세요.");
        }

        return queries;
    }

    public static List<QueryInfo> parseContent(String content) {
        List<QueryInfo> queries = new ArrayList<>();
        String[] rawQueries = splitQueries(content);

        int index = 1;
        for (String rawSql : rawQueries) {
            String trimmedSql = rawSql.trim();
            if (trimmedSql.isEmpty()) {
                continue;
            }

            // 모든 주석(#, --, /* */)이 제외된 실제 SQL 구문이 비어있으면 건너뜀
            String sqlWithoutComments = trimmedSql
                    .replaceAll("(?m)^\\s*#.*$\\n?", "")
                    .replaceAll("(?m)^\\s*--.*$\\n?", "")
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .trim();
            if (sqlWithoutComments.isEmpty()) {
                log.debug("쿼리 블록 {} 는 주석만 포함하고 있어 건너뜁니다.", index);
                continue;
            }

            String title = extractTitle(trimmedSql, index);
            queries.add(new QueryInfo(trimmedSql, title));
            index++;
        }

        return queries;
    }

    public static String[] splitQueries(String content) {
        // 세미콜론(;) 기준으로 쿼리 분할 (따옴표, 주석, PostgreSQL 달러 인용구 내부 세미콜론 무시)
        List<String> list = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;  // -- 스타일 라인 주석
        boolean inBlockComment = false; // /* */ 스타일 블록 주석
        String activeDollarTag = null;  // 활성화된 PostgreSQL 달러 인용구 태그 (예: "$$", "$body$")

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : 0;

            // 주석 및 따옴표 바깥일 때 PostgreSQL 달러 인용구 시작/종료 처리
            if (!inSingleQuote && !inDoubleQuote && !inLineComment && !inBlockComment && c == '$') {
                if (activeDollarTag != null) {
                    // 이미 달러 인용구 내부인 경우, 닫는 태그인지 확인
                    if (content.startsWith(activeDollarTag, i)) {
                        sb.append(activeDollarTag);
                        i += activeDollarTag.length() - 1;
                        activeDollarTag = null; // 인용구 종료
                        continue;
                    }
                } else {
                    // 달러 인용구 시작 탐색 (예: $$, $body$, $an_123_tag$)
                    int endIdx = content.indexOf('$', i + 1);
                    if (endIdx != -1 && endIdx - i < 50) { // 태그 길이는 보통 짧음
                        String tagCandidate = content.substring(i, endIdx + 1);
                        if (tagCandidate.matches("^\\$[a-zA-Z0-9_]*\\$$")) {
                            activeDollarTag = tagCandidate;
                            sb.append(activeDollarTag);
                            i += activeDollarTag.length() - 1;
                            continue;
                        }
                    }
                }
            }

            // 달러 인용구 내부이면 주석/따옴표/세미콜론 판정을 건너뛰고 버퍼에 그대로 담음
            if (activeDollarTag != null) {
                sb.append(c);
                continue;
            }

            // 라인 주석 진입 (-- comment)
            if (!inSingleQuote && !inDoubleQuote && !inBlockComment && c == '-' && next == '-') {
                inLineComment = true;
            }
            // 라인 주석 종료 (개행)
            if (inLineComment && (c == '\n' || c == '\r')) {
                inLineComment = false;
            }
            // 블록 주석 진입 (/* comment */)
            if (!inSingleQuote && !inDoubleQuote && !inLineComment && c == '/' && next == '*') {
                inBlockComment = true;
            }
            // 블록 주석 종료
            if (inBlockComment && c == '*' && next == '/') {
                sb.append(c); // '*' 추가
                sb.append(next); // '/' 추가
                i++; // '/' 스킵
                inBlockComment = false;
                continue;
            }

            // 주석 내부면 그냥 버퍼에 추가 (주석을 SQL에 포함시켜 추출에 활용)
            if (inLineComment || inBlockComment) {
                sb.append(c);
                continue;
            }

            // 따옴표 토글
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            // 세미콜론: 따옴표/주석/달러인용구 밖에서만 구분자로 취급
            if (c == ';' && !inSingleQuote && !inDoubleQuote) {
                list.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }

        // 마지막 쿼리 처리 (세미콜론 없이 끝난 경우)
        String remaining = sb.toString().trim();
        if (!remaining.isEmpty()) {
            list.add(remaining);
        }

        return list.toArray(new String[0]);
    }

    private static String extractTitle(String sql, int queryIndex) {
        String defaultTitle = "query_" + queryIndex;

        // SELECT 키워드 위치 탐색 (대소문자 무관)
        String upperSql = sql.toUpperCase();
        int selectIdx = upperSql.indexOf("SELECT");
        if (selectIdx == -1) {
            log.debug("쿼리 {} 에서 SELECT 키워드를 찾을 수 없어 기본 제목을 사용합니다: '{}'", queryIndex, defaultTitle);
            return defaultTitle;
        }

        // SELECT 이전 영역에서 마지막 '#' 주석 탐색
        String beforeSelect = sql.substring(0, selectIdx);
        int lastHashIdx = beforeSelect.lastIndexOf('#');
        if (lastHashIdx == -1) {
            return defaultTitle;
        }

        // '#' 다음부터 줄 끝까지 추출
        String commentLine = beforeSelect.substring(lastHashIdx + 1);
        int newLineIdx = commentLine.indexOf('\n');
        if (newLineIdx != -1) {
            commentLine = commentLine.substring(0, newLineIdx);
        }
        int carriageReturnIdx = commentLine.indexOf('\r');
        if (carriageReturnIdx != -1) {
            commentLine = commentLine.substring(0, carriageReturnIdx);
        }

        String rawTitle = commentLine.trim();
        if (rawTitle.isEmpty()) {
            return defaultTitle;
        }

        // 최대 20자 제한
        if (rawTitle.length() > 20) {
            rawTitle = rawTitle.substring(0, 20);
        }

        // 파일명 안전 문자만 허용 (특수문자 → '_')
        String safeTitle = SAFE_FILENAME_PATTERN.matcher(rawTitle).replaceAll("_");
        safeTitle = safeTitle.replaceAll("\\s+", "_").replaceAll("_+", "_");
        safeTitle = safeTitle.replaceAll("^_+|_+$", ""); // 앞뒤 '_' 제거

        if (safeTitle.isEmpty()) {
            log.warn("쿼리 {} 의 주석에서 유효한 제목을 추출할 수 없어 기본 제목을 사용합니다: '{}'", queryIndex, defaultTitle);
            return defaultTitle;
        }

        return safeTitle;
    }

    public static void createDefaultTemplate(Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# 1. 사용자_목록_조회\n");
            writer.write("SELECT '1' as user_id, 'admin' as username, 'admin@example.com' as email;\n\n");
            writer.write("# 2. 시스템_버전_조회\n");
            writer.write("SELECT '1.0.0' as version, 'OK' as status;\n");
        } catch (AccessDeniedException e) {
            throw new IOException(
                "[INIT ERROR] 파일 쓰기 권한이 없어 queries.sql을 생성할 수 없습니다: " + path + "\n" +
                "가이드: 현재 디렉토리의 쓰기 권한을 확인하거나, 권한이 있는 디렉토리로 이동 후 실행하세요.", e
            );
        }
    }
}
