# sender query

`sender query`는 DB 조회 결과를 CSV로 추출해 `sender encode`의 입력 소스 중 일부를 만드는
sender 전용 CLI 기능입니다. GUI와 receiver에는 연결하지 않습니다.

## 범위

- `query init`: `config.csv`, `queries.sql` 템플릿 생성
- `query --list`: DB 접속 없이 SQL 파일 파싱 결과 출력
- `query`: 설정과 SQL을 읽어 SELECT/WITH 쿼리 실행, CSV/report 출력

`query`는 QR payload나 fountain encode/decode 형식에 관여하지 않습니다.

## 모듈

- `libs/query`
  - `QueryCommand`: picocli 명령 어댑터
  - `QueryConfig`: `config.csv` 로드, 기본값, 범위 보정
  - `QueryParser`: SQL 분할, `# 제목` 추출, 파일명 안전화
  - `DatabaseConnector`: HikariCP DataSource 라이프사이클
  - `QueryExecutor`: 병렬 쿼리 실행, CSV 스트리밍, 보고서 작성
- `apps/sender`
  - `Sender`가 `QueryCommand`를 subcommand로 등록

의존성은 sender 방향으로만 흐릅니다.

```text
sender -> query
query  -> picocli, HikariCP, commons-csv, JDBC drivers
```

## 실행 흐름

```text
config.csv + queries.sql
  -> QueryCommand
  -> QueryConfig / QueryParser
  -> DatabaseConnector
  -> QueryExecutor
  -> CSV files + execution reports
  -> sender encode --in <query output>
```

## 안전장치

- SQL은 `SELECT` 또는 `WITH`로 시작하는 문장만 실행합니다.
- 세미콜론 분할 뒤 하위 문장마다 허용 패턴을 다시 확인해 다중문 DML/DDL을 차단합니다.
- `db.read-only=true`(기본값)를 HikariCP 커넥션 풀과 실행 커넥션의
  `setReadOnly(true)`에 적용합니다.
- 대용량 결과를 메모리에 모으지 않고 `ResultSet`에서 CSV로 스트리밍합니다.
- `--out`은 `config.csv`의 `output.dir`보다 우선합니다.
- JDBC URL 로그에서는 `password`/`pwd` 파라미터를 마스킹합니다.
- sender의 `logback.xml`은 `DEBUG/INFO`용 stdout과 `WARN/ERROR`용 stderr
  ConsoleAppender를 분리하고 두 인코더를 UTF-8로 고정합니다. query 실행 상태와 오류는
  Logback을 사용합니다. 다만 banner와 일부 CLI 결과는 `System.out`/`System.err` 직접 출력이므로 Windows 터미널 코드 페이지도
  UTF-8(`chcp 65001`)로 맞춰야 전체 한글 출력이 일관됩니다.
- 내장 JDBC 드라이버는 MySQL, PostgreSQL, Oracle, SQL Server, MariaDB, IBM DB2, H2입니다.

이 안전장치는 보조 방어입니다. 실제 운영에서는 읽기 전용 DB 계정을 사용해야 합니다.

## 테스트

변경 시 최소 확인 대상:

- `libs/query/src/test/java/airbridge/query/QueryConfigTest.java`
- `libs/query/src/test/java/airbridge/query/QueryParserTest.java`
- `libs/query/src/test/java/airbridge/query/QueryExecutorIntegrationTest.java`
- `libs/query/src/test/java/airbridge/query/QueryCommandTest.java`
- `apps/sender/src/test/java/airbridge/sender/SenderCliTest.java`
