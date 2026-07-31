# air-bridge

`air-bridge`는 air-gap 환경에서 데이터를 전송하기 위해 사용하는 도구입니다.

이 프로젝트는 `sender`와 `receiver` 두 애플리케이션이 한 쌍으로 동작합니다. 주요 전송 흐름은 `encode`, `slide`, `capture`, `decode`로 이어지며, 송신 측에서 파일을 이미지 시퀀스로 변환하고 수신 측에서 이를 다시 복원합니다.

`sender query`는 DB 조회 결과를 CSV로 추출해 `encode` 입력 소스 중 일부를 만드는 선택 기능입니다. 보조 명령인 `identify`, `pack`, `unpack`는 `sender`를 대상 환경에 반입하기 전에 파일 구성 확인과 패키징 작업을 돕기 위한 기능입니다.

## 경고

이 도구의 실제 사용은 적용 환경의 정책, 법률, 보안 규정에 따라 문제가 될 수 있습니다. 사용자는 관련 법률과 내부 정책을 직접 확인하고 스스로 판단해야 하며, 그에 따른 책임은 사용자에게 있습니다.

더 강한 경고와 책임 범위는 [`docs/user/warning.ko.md`](docs/user/warning.ko.md) 를 참고하십시오.

## 사용 흐름 Overview

일반적인 사용 흐름은 아래와 같습니다.

1. 대상 컴퓨터에서 전송할 파일에 대해 `encode`를 수행합니다.
2. 생성된 이미지 파일을 송출하기 위해 `slide`를 실행합니다.
3. 데이터를 수신할 환경에서 `capture`를 수행합니다.
4. 수집된 결과를 복원하기 위해 `decode`를 수행합니다.

`slide`와 `capture`는 USB capture board를 사용하는 구성을 기본 전제로 합니다. capture board가 없다면 이미지를 수동으로 촬영하거나 별도 방식으로 옮겨 처리할 수 있습니다.

## Requirements

- Java 21 이상
- Gradle Wrapper 사용 가능 환경

## Build

```bash
./gradlew clean build
```

샌드박스나 제한된 환경에서는 아래처럼 프로젝트 내부 Gradle 홈을 써도 됩니다.

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew build
```

## Artifacts

기본 산출물은 아래 두 파일입니다.

```bash
build/libs/sender-<version>.jar
build/libs/receiver-<version>.jar
```

DB2 전용 sender처럼 query JDBC 드라이버를 제한하려면 아래처럼 빌드합니다.

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew -PqueryJdbcDrivers=db2 :sender:jar
```

이 경우 산출물은 `apps/sender/build/libs/sender-<version>-db2.jar` 입니다.

같은 폴더에 기본 명령 실행용 스크립트도 생성됩니다.

```bash
build/libs/encode.sh
build/libs/encode.bat
build/libs/slide.sh
build/libs/slide.bat
build/libs/capture.sh
build/libs/capture.bat
build/libs/decode.sh
build/libs/decode.bat
```

버전 형식은 `{major}.{minor}.{yymmdd}.{hh24mi}` 입니다.

실행 기준은 `sender` 와 `receiver` 모두 jar 입니다.

`java -jar ...`처럼 명령 없이 실행하면 GUI가 열립니다. `encode`, `decode`,
`--help`처럼 명령이나 CLI 옵션을 지정하면 기존 CLI로 동작합니다.

## 기본 명령

- `sender`: `encode`, `gui`, `query`, `slide`, `unpack`
- `receiver`: `decode`, `capture`, `gui`, `identify`, `pack`

## 기본 경로

`--in`/`--out`을 생략하면 jar가 있는 폴더 기준 기본 디렉터리를 사용합니다.

```
encode( source -> encoded ) -> slide( encoded ) -> capture( -> captured ) -> decode( captured -> decoded )
```

디렉터리 이름은 jar 옆 `airbridge-paths.properties`(키: `dir.source`/`dir.encoded`/`dir.captured`/`dir.decoded`)로 바꿀 수 있습니다. GUI도 같은 기본 경로를 채웁니다.

## Quick Start

최소 실행 확인은 아래 명령으로 충분합니다.

```bash
./gradlew clean build
java -jar build/libs/sender-<version>.jar --help
java -jar build/libs/receiver-<version>.jar --help
```

## AI / 자동화 도구 참고

AI나 스크립트가 전송 준비를 자동화할 때는 명령 책임을 분리합니다.

1. DB `SELECT`/`WITH` 결과가 CSV 소스로 필요할 때만 `sender query`를 사용합니다.
2. `query` 출력 폴더는 `sender encode` 입력 후보 중 하나이지, 필수 QR 파이프라인 단계가 아닙니다.
3. 전송할 파일/폴더를 정한 뒤 `sender encode`를 명시적으로 실행합니다.
4. 실제 QR 전송 경로는 `sender slide`, `receiver capture`, `receiver decode`입니다.
5. `query`, `pack`, `unpack`이 QR payload 형식을 바꾼다고 가정하지 않습니다.

## 배포 문서

- `warning`: `docs/user/warning.ko.md`
- `sender`: `docs/user/deploy-sender.md`
- `receiver`: `docs/user/deploy-receiver.md`
- `encode / decode`: `docs/user/encode-decode.md`
- `query`: `docs/user/query.md`
- `slide / capture`: `docs/user/slide-capture.md`
- `packager`: `docs/user/packager.md`
- `tuning`: `docs/user/tuning.md`
