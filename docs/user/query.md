# Query Usage

`sender query`는 DB 조회 결과를 CSV 파일로 추출해 `sender encode`의 입력 소스 중 일부를 만드는 CLI 전용 기능입니다.

이 명령은 QR 전송 파이프라인 내부 단계가 아닙니다. 일반 흐름은 아래처럼 분리됩니다.

```text
sender query  -> CSV 결과 폴더 생성
sender encode -> CSV 결과 폴더를 QR PNG로 인코딩
```

## 기본 실행

현재 디렉터리의 `config.csv`와 `queries.sql`을 읽어 쿼리를 실행합니다.

```bash
java -jar sender-<version>.jar query
```

`config.csv`의 `output.dir`이 비어 있으면 `run_yyyyMMdd_HHmmss` 폴더가 자동으로 만들어지고,
그 안에 CSV 결과와 보고서가 저장됩니다.

## 템플릿 생성

```bash
java -jar sender-<version>.jar query init
```

기존 파일을 덮어쓰려면:

```bash
java -jar sender-<version>.jar query init --force
```

## 경로 지정

```bash
java -jar sender-<version>.jar query \
  --config /path/config.csv \
  --sql /path/queries.sql \
  --out /path/source/query-run
```

`--out`은 `config.csv`의 `output.dir`보다 우선합니다.

## 쿼리 목록 확인

DB에 접속하지 않고 SQL 파일에서 파싱되는 쿼리 목록만 확인합니다.

```bash
java -jar sender-<version>.jar query --list --sql /path/queries.sql
```

## 설정 파일

`config.csv`는 `key,value` 형식입니다.

```csv
key,value
db.url,jdbc:mysql://localhost:3306/your_database?useSSL=false&serverTimezone=UTC&useCursorFetch=true
db.username,readonly_user
db.password,
thread.count,4
fetch.size,1000
query.timeout.seconds,0
db.connection.timeout.seconds,30
query.retry.count,0
query.retry.delay.seconds,5
progress.interval,50000
csv.delimiter,","
csv.bom,false
output.dir,
```

`db.password`를 비워두면 `DB_PASSWORD` 환경변수 또는 `-Ddb.password=...` JVM 옵션을
사용합니다. 비밀번호는 `config.csv`에 평문으로 두기보다 이 방식을 권장합니다.

## SQL 파일

각 쿼리는 세미콜론으로 구분합니다. `SELECT` 또는 `WITH`로 시작하는 조회 쿼리만 실행합니다.

```sql
# 월별_매출_현황
SELECT year_month, sum(amount) AS total
FROM sales
GROUP BY year_month
ORDER BY year_month;

# 신규_회원_목록
SELECT user_id, email, created_at
FROM users
WHERE created_at >= '2024-01-01';
```

`SELECT` 직전의 `# 제목`은 결과 CSV 파일명에 사용됩니다.

## 출력 파일

```text
run_yyyyMMdd_HHmmss/
  01_월별_매출_현황.csv
  02_신규_회원_목록.csv
  _00_summary.csv
  execution-report.txt
  execution-report.json
```

생성된 폴더를 바로 encode 입력 중 하나로 사용할 수 있습니다.

```bash
java -jar sender-<version>.jar encode \
  --in /path/source/query-run \
  --out /path/encoded
```

## 주의

- 읽기 전용 DB 계정을 사용하십시오. `sender query`는 `SELECT/WITH` 필터와 read-only 커넥션 힌트를 적용하지만, DB 권한 설정을 대체하지 않습니다.
- MySQL/MariaDB에서 대용량 결과를 스트리밍하려면 JDBC URL에 `useCursorFetch=true`를 넣는 것을 권장합니다.
- 내장 JDBC 드라이버: MySQL, PostgreSQL, Oracle, SQL Server, MariaDB, IBM DB2, H2.
- 이 기능은 DB에 접속하므로, air-gap 대상 환경에서 쓰려면 해당 환경 안에서 접근 가능한 DB와 JDBC 설정이 필요합니다. 원격 업데이트, telemetry, cloud sync는 수행하지 않습니다.
