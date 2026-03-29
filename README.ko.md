# air-bridge

`air-bridge`는 air-gap 환경에서 데이터를 전송하기 위해 사용하는 도구입니다.

이 프로젝트는 `sender`와 `receiver` 두 애플리케이션이 한 쌍으로 동작합니다. 주요 전송 흐름은 `encode`, `slide`, `capture`, `decode`로 이어지며, 송신 측에서 파일을 이미지 시퀀스로 변환하고 수신 측에서 이를 다시 복원합니다.

보조 명령인 `identify`, `pack`, `unpack`는 `sender`를 대상 환경에 반입하기 전에 파일 구성 확인과 패키징 작업을 돕기 위한 기능입니다.

## 경고

이 도구의 실제 사용은 적용 환경의 정책, 법률, 보안 규정에 따라 문제가 될 수 있습니다. 사용자는 관련 법률과 내부 정책을 직접 확인하고 스스로 판단해야 하며, 그에 따른 책임은 사용자에게 있습니다.

더 강한 경고와 책임 범위는 [`docs/warning.ko.md`](docs/warning.ko.md) 를 참고하십시오.

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

버전 형식은 `0.9.yymmdd.hh24mi` 입니다.

실행 기준은 `sender` 와 `receiver` 모두 jar 입니다. `receiver` 는 대량 `decode` 나 `capture` 작업에서 JVM 메모리 옵션을 추가하는 것을 권장합니다.

## 기본 명령

- `sender`: `encode`, `slide`, `unpack`
- `receiver`: `decode`, `capture`, `identify`, `pack`

## Quick Start

최소 실행 확인은 아래 명령으로 충분합니다.

```bash
./gradlew clean build
java -jar build/libs/sender-<version>.jar --help
java -jar build/libs/receiver-<version>.jar --help
```

## 배포 문서

- `warning`: `docs/warning.ko.md`
- `sender`: `docs/deploy-sender.md`
- `receiver`: `docs/deploy-receiver.md`

## 상세 사용

### Commands

### 1. encode

대상 파일들을 읽어 QR PNG로 변환합니다.

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT
```

주요 산출물:

- QR PNG 파일들
- `_manifest.txt`

### 2. decode

QR PNG를 읽어 원본 파일로 복원합니다.

```bash
java -jar build/libs/receiver-<version>.jar decode --in /path/qr-images --out /path/restore
```

주요 산출물:

- 복원된 원본 파일들
- `_restore_result.txt`

`_restore_result.txt` 예시:

```text
O src/main/java/App.java - OK
X src/main/java/App.java - INCOMPLETE (누락: [2, 5])
X src/main/java/App.java - HASH_MISMATCH
X src/main/java/App.java - DECODE_ERROR
```

### 3. capture

UVC 카메라 입력에서 QR 프레임을 수집해 `captured-images/` 와 `capture-manifest.json` 을 만듭니다.

```bash
java -jar build/libs/receiver-<version>.jar capture --out /path/capture-out
```

### 4. identify / pack / unpack

패키지 반입용 보조 명령입니다.

```bash
java -jar build/libs/receiver-<version>.jar identify --in /path/to/sender.jar
java -jar build/libs/receiver-<version>.jar pack --in /path/to/sender.jar
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

주요 산출물:

- `target-ext.txt`
- 패킹된 `.zip`

### 5. slide

QR 이미지나 일반 이미지 세트를 화면에 재생하는 Swing 도구입니다. `sender` 안에 번들되어 있습니다.

```bash
java -jar build/libs/sender-<version>.jar slide
java -jar build/libs/sender-<version>.jar slide --help
```

### 6. reencode

`reencode`는 유지보수용 숨김 명령입니다. 공개 도움말에는 나오지 않지만, `_restore_result.txt` 기준으로 실패 파일 또는 누락 청크만 다시 생성할 때 사용할 수 있습니다.

```bash
java -jar build/libs/sender-<version>.jar reencode --in /path/in --out /path/reencoded --project-name PROJECT --restore-dir /path/restore
```

## Main Options

- `sender` 공개 명령: `encode`, `slide`, `unpack`
- `receiver` 공개 명령: `decode`, `capture`, `identify`, `pack`
- `--in`: 입력 디렉토리
- `--out`: 출력 디렉토리
- `--project-name`: 프로젝트명
- `--encode-root`: QR 내부 상대경로 기준점
- `--restore-dir`: `reencode` 시 `_restore_result.txt` 기본 위치
- `--reencode-result-path`: `reencode` 시 결과 파일 직접 지정
- `--chunk-data-size`: QR 하나당 데이터 청크 크기
- `--qr-image-size`: QR 이미지 크기
- `--qr-error-level`: `L`, `M`, `Q`, `H`
- `--label-height`: QR 하단 라벨 높이
- `--convert-xlsx-to-csv`: `.xlsx`를 CSV로 변환 후 인코딩
- `--convert-office-to-text`: `.docx`, `.pptx`를 텍스트로 변환 후 인코딩
- `--[no-]folder-structure`: 출력 폴더 구조 유지 여부
- `--files-per-folder`: 순번 폴더 모드에서 폴더당 파일 수
- `--print-html`: 인쇄용 HTML 생성
- `--target-extensions`: 인코딩 대상 확장자 목록
- `--skip-dirs`: 탐색 제외 디렉토리명 목록
- `--exclude-paths`: 제외할 절대/상대 경로 목록

## Examples

폴더 구조 유지 없이 500개 단위 폴더로 QR 저장:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --no-folder-structure --files-per-folder 500
```

XLSX는 CSV로, DOCX/PPTX는 텍스트로 변환해서 인코딩:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --convert-xlsx-to-csv --convert-office-to-text
```

대상 확장자를 직접 지정:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --target-extensions .java,.xml,.properties,.sql
```

## Notes

- `decode`와 `reencode`는 `encode`가 만든 QR payload 형식을 전제로 동작합니다.
- `reencode`는 `_restore_result.txt`의 `INCOMPLETE`, `DECODE_ERROR`, `HASH_MISMATCH` 항목을 읽어 재생성 대상을 결정합니다.
- 작은 샘플 입력은 [`fixtures/samples`](fixtures/samples)에 있습니다.
- 비교적 큰 수동 검증 자산은 [`fixtures`](fixtures)에 있습니다.
- 그래픽 환경 제약이 있는 서버에서는 필요 시 `java -Djava.awt.headless=true`로 실행하는 편이 안전합니다.
