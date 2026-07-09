package airbridge.query;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryParser 단위 테스트
 */
@DisplayName("QueryParser 테스트")
class QueryParserTest {

    // ─── 제목 추출 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("# 주석에서 제목 추출 - 기본 케이스")
    void testParseContentWithComments() {
        String content = """
                # 1. 2026 매출 현황 조회
                SELECT count(*), sum(price) FROM orders;
                
                # 2. 신규 사용자 목록 (가입순)
                SELECT user_id, email FROM users ORDER BY signup_date DESC;
                
                #3.단순_텍스트
                SELECT 1;
                
                # SELECT 바로 뒤 주석은 무시
                SELECT 2;
                """;

        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(4, queries.size());
        assertEquals("1_2026_매출_현황_조회", queries.get(0).getTitle());
        assertEquals("2_신규_사용자_목록_가입순", queries.get(1).getTitle());
        assertEquals("3_단순_텍스트", queries.get(2).getTitle());
        assertEquals("query_4", queries.get(3).getTitle()); // SELECT 뒤 주석 → 기본 이름
    }

    @Test
    @DisplayName("# 주석 제목 20자 초과 시 잘라냄")
    void testParseContentLongComment() {
        String content = """
                # 이 쿼리는 아주아주아주아주아주아주아주아주아주긴주석입니다
                SELECT * FROM dual;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        String title = queries.get(0).getTitle();
        assertTrue(title.length() <= 20, "제목 길이가 20자를 초과합니다: " + title.length());
        assertEquals("이_쿼리는_아주아주아주아주아주아주아주", title);
    }

    @Test
    @DisplayName("# 주석이 없으면 query_N 기본 제목 사용")
    void testNoHashComment_usesDefaultTitle() {
        String content = "SELECT 1 AS val;";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        assertEquals("query_1", queries.get(0).getTitle());
    }

    @Test
    @DisplayName("특수문자만 있는 주석은 기본 제목으로 대체")
    void testSpecialCharOnlyTitle_usesDefaultTitle() {
        String content = """
                # !!!???~~~
                SELECT 1;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        // 특수문자 → '_' 치환 후 앞뒤 _ 제거 → 빈 문자열 → 기본 이름
        assertEquals("query_1", queries.get(0).getTitle());
    }

    // ─── 세미콜론 파싱 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("세미콜론으로 복수 쿼리 분리")
    void testMultipleQueriesBySemicolon() {
        String content = "SELECT 1; SELECT 2; SELECT 3;";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(3, queries.size());
    }

    @Test
    @DisplayName("따옴표 안의 세미콜론은 구분자로 취급하지 않음")
    void testSemicolonInsideQuoteNotSplit() {
        String content = "SELECT 'hello;world' AS msg;";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getSql().contains("hello;world"));
    }

    @Test
    @DisplayName("쌍따옴표 안의 세미콜론도 구분자로 취급하지 않음")
    void testSemicolonInsideDoubleQuoteNotSplit() {
        String content = "SELECT \"col;name\" FROM t;";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
    }

    @Test
    @DisplayName("마지막 쿼리에 세미콜론이 없어도 파싱됨")
    void testLastQueryWithoutSemicolon() {
        String content = "SELECT 1;\nSELECT 2";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(2, queries.size());
    }

    @Test
    @DisplayName("-- 라인 주석 안의 세미콜론은 구분자로 취급하지 않음")
    void testSemicolonInLineCommentNotSplit() {
        String content = """
                SELECT 1 -- this is a comment; not a separator
                ;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
    }

    @Test
    @DisplayName("/* */ 블록 주석 안의 세미콜론은 구분자로 취급하지 않음")
    void testSemicolonInBlockCommentNotSplit() {
        String content = "SELECT /* ; */ 1;";
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
    }

    // ─── SQL 내용 검증 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("# 주석이 최종 SQL에서 제거됨 (H2 등 미지원 DB 대비)")
    void testHashCommentsRemovedFromSql() {
        String content = """
                # 쿼리 제목
                SELECT 1 AS val;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        String sql = queries.get(0).getSql();
        assertFalse(sql.contains("#"), "SQL에 # 주석이 남아있으면 안 됩니다: " + sql);
        assertTrue(sql.contains("SELECT 1 AS val"), "SELECT 문이 포함되어야 합니다: " + sql);
    }

    @Test
    @DisplayName("주석만 있는 블록은 쿼리로 등록되지 않음")
    void testCommentOnlyBlockIgnored() {
        String content = """
                # 이건 주석만
                ;
                # 실제 쿼리
                SELECT 1;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        // 주석만 있는 첫 번째 블록은 건너뜀 → 총 1개
        assertEquals(1, queries.size());
    }

    @Test
    @DisplayName("빈 입력은 빈 리스트 반환")
    void testEmptyContentReturnsEmptyList() {
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent("   \n\t  ");
        assertTrue(queries.isEmpty());
    }

    // ─── WITH 절 지원 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("WITH 절로 시작하는 쿼리도 파싱됨")
    void testWithClauseQuery() {
        String content = """
                # CTE 쿼리
                WITH cte AS (SELECT 1 AS val)
                SELECT * FROM cte;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getSql().toUpperCase().contains("WITH"));
    }

    // ─── 파라미터화 테스트 ───────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1;", "select 1;", "Select 1;", "  SELECT 1  ;"})
    @DisplayName("SELECT 대소문자 무관하게 파싱됨")
    void testSelectCaseInsensitive(String content) {
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size());
    }

    // ─── QueryInfo toString ──────────────────────────────────────────────────

    @Test
    @DisplayName("QueryInfo.toString()이 NPE 없이 동작")
    void testQueryInfoToString() {
        QueryParser.QueryInfo info = new QueryParser.QueryInfo("SELECT 1", "test");
        assertNotNull(info.toString());
        assertDoesNotThrow(info::toString);
    }

    @Test
    @DisplayName("PostgreSQL 달러 인용구 ($$ 및 $tag$) 내부에 세미콜론이 포함되어 있어도 분할되지 않고 하나로 유지됨")
    void testPostgreSqlDollarQuoteSemicolon() {
        String content = """
                # 달러_인용구_테스트
                SELECT $$ hello; world; $$ AS col1, $body$ a; b; c; $body$ AS col2;
                """;
        List<QueryParser.QueryInfo> queries = QueryParser.parseContent(content);
        assertEquals(1, queries.size(), "달러 인용구 안의 세미콜론들로 인해 여러 개로 쪼개지면 안 됩니다.");
        assertTrue(queries.get(0).getSql().contains("hello; world;"));
        assertTrue(queries.get(0).getSql().contains("a; b; c;"));
    }
}
