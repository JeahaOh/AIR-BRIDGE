# Sender Deployment

이 문서는 `sender`를 실제로 어떻게 실행하면 되는지 사용자 기준으로 정리합니다.

## 무엇을 하나

`sender`는 아래 작업을 담당한다.

- `encode`: 입력 파일을 QR 이미지 세트로 변환
- `gui`: sender GUI 실행. 현재는 encode와 slide 화면을 제공
- `query`: DB SELECT/WITH 조회 결과를 CSV로 추출해 encode 입력 소스 중 일부 생성
- `slide`: QR 이미지 또는 일반 이미지 세트를 화면에 재생
- `unpack`: 패킹된 jar/zip의 `.txt` suffix를 제거

대부분은 `encode`와 `slide`만 알면 됩니다. DB 조회 결과를 전송해야 할 때만
`query`를 먼저 사용합니다.

## 산출물

```bash
build/libs/sender-<version>.jar
```

같은 폴더에 sender 기본 명령용 스크립트도 생성됩니다.

```bash
build/libs/encode.sh
build/libs/encode.bat
build/libs/slide.sh
build/libs/slide.bat
```

## 기본 실행

기본 실행 방식:

```bash
java -jar build/libs/sender-<version>.jar
```

명령 없이 실행하면 sender GUI가 열립니다. jar를 더블 클릭해 실행하는 경우도
같은 동작을 목표로 합니다. `encode`, `query`, `slide`, `unpack`, `--help`처럼 명령이나
CLI 옵션을 지정하면 CLI로 동작합니다.

도움말:

```bash
java -jar build/libs/sender-<version>.jar --help
```

## 운영 메모

- `sender`는 단일 fat jar 기준으로 배포하는 편이 가장 단순합니다.
- 기본 빌드는 query용 JDBC 드라이버를 모두 포함합니다. 특정 DB 드라이버만 포함하려면
  `queryJdbcDrivers`를 지정합니다. 예: DB2 전용 sender는
  `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew -PqueryJdbcDrivers=db2 :sender:jar`
  로 빌드하며, 산출물 이름은 `sender-<version>-db2.jar`입니다.
- 대부분의 경우 별도 JVM 옵션 없이 `java -jar ...`로 바로 실행하면 됩니다.
- `--in`/`--out`을 생략하면 jar가 있는 폴더 기준 기본 디렉터리를 씁니다
  (encode: `source` → `encoded`, slide 입력: `encoded`). 디렉터리 이름은 jar 옆
  `airbridge-paths.properties`로 바꿀 수 있습니다. 자세한 내용은 `encode-decode.md`의
  "기본 경로" 참고.
- 이 문서는 공개 명령만 다룹니다. 유지보수용 숨김 명령은 별도 사용자 문서로 다루지 않습니다.

## 자주 쓰는 작업

파일을 QR 이미지로 만들기:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out
```

GUI에서 파일을 QR 이미지로 만들기:

```bash
java -jar build/libs/sender-<version>.jar gui
```

`gui` 명령을 명시해도 되고, 명령 없이 jar만 실행해도 같은 GUI가 열립니다.

GUI의 `Slide` 탭에서 슬라이드 입력 디렉터리를 고를 수 있습니다. `Encode`
탭의 `Slide` 버튼은 출력 디렉터리가 입력돼 있으면 그 디렉터리를 슬라이드
입력으로 열어 줍니다.

encode 실행 중에는 Encode 탭의 입력값과 Browse 버튼이 잠기며, 중단은
`Stop`으로 요청합니다. 중단된 encode는 이번 실행에서 만든 QR PNG와 manifest
파일을 정리합니다.

DB 조회 결과를 CSV 소스로 만들기:

```bash
java -jar build/libs/sender-<version>.jar query
```

처음 사용할 때는 템플릿을 생성합니다.

```bash
java -jar build/libs/sender-<version>.jar query init
```

생성된 CSV 결과 폴더는 이후 `encode --in` 입력으로 사용할 수 있습니다.

QR 이미지를 화면에 재생하기:

```bash
java -jar build/libs/sender-<version>.jar slide
```

빌드 산출물의 기본 스크립트를 써도 됩니다.

```bash
./build/libs/encode.sh
./build/libs/slide.sh
```

초기 입력 디렉터리를 지정해 열 수도 있습니다.

```bash
java -jar build/libs/sender-<version>.jar slide --in /path/qr
```

패킹된 zip을 다시 복원하기:

```bash
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

## 먼저 보면 좋은 문서

- [`encode-decode.md`](encode-decode.md)
- [`query.md`](query.md)
- [`slide-capture.md`](slide-capture.md)
- [`packager.md`](packager.md)

## 간단 확인

- `java -jar build/libs/sender-<version>.jar --help`
- `java -jar build/libs/sender-<version>.jar`
- `java -jar build/libs/sender-<version>.jar gui`
- `java -jar build/libs/sender-<version>.jar query --help`
- `java -jar build/libs/sender-<version>.jar encode --help`
- `java -jar build/libs/sender-<version>.jar slide --help`
- `java -jar build/libs/sender-<version>.jar unpack --help`

주의:

- `slide` 실제 GUI 오픈 여부는 운영 환경에서 별도 수동 확인이 필요합니다.
