# sender query DB 설정 예제

두 설정 파일은 DB 비밀번호를 저장하지 않습니다. 실행 전에 `DB_PASSWORD` 환경변수에
비밀번호를 넣고, 사용할 파일을 `--config`로 지정합니다.

두 예제의 `db.read-only=true`는 커넥션 풀과 쿼리 실행 커넥션에 JDBC 읽기 전용 모드를
요청합니다. 설정을 생략해도 기본값은 `true`입니다.

## DB2

`config-db2.csv`에서 다음 값을 실제 환경에 맞게 바꿉니다.

- `db2-host.example.internal`: DB2 호스트명 또는 IP
- `50000`: DB2 포트
- `YOUR_DATABASE`: 데이터베이스명
- `YOUR_SCHEMA`: 기본 스키마명
- `readonly_user`: 조회 전용 계정

```bash
DB_PASSWORD='실제_DB2_비밀번호' java -jar sender-<version>-db2-postgresql.jar query \
  --config examples/query/config-db2.csv \
  --sql queries-db2.sql
```

## PostgreSQL

`config-postgresql.csv`에서 다음 값을 실제 환경에 맞게 바꿉니다.

- `postgres-host.example.internal`: PostgreSQL 호스트명 또는 IP
- `5432`: PostgreSQL 포트
- `your_database`: 데이터베이스명
- `your_schema`: 기본 스키마명
- `readonly_user`: 조회 전용 계정

```bash
DB_PASSWORD='실제_PostgreSQL_비밀번호' java -jar sender-<version>-db2-postgresql.jar query \
  --config examples/query/config-postgresql.csv \
  --sql queries-postgresql.sql
```

Windows PowerShell에서는 실행 전에 다음처럼 비밀번호를 설정합니다.

```powershell
$env:DB_PASSWORD = '실제_DB_비밀번호'
```

`thread.count=2`는 DB 부하를 낮춘 시작값입니다. DBA와 협의하고 독립적으로 동시에
실행해도 되는 쿼리일 때만 값을 늘리십시오. `csv.bom=true`는 Excel에서 한글 CSV가
깨지는 문제를 줄입니다.

`sender query`의 읽기 전용 설정은 DB 권한을 대신하지 않습니다. 운영 환경에서는
테이블 조회 권한만 가진 전용 계정을 사용하십시오.
