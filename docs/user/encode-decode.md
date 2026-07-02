# Encode / Decode Usage

이 문서는 `encode`와 `decode`를 실제 사용 기준으로 정리합니다.

## 언제 쓰나

일반적인 전송 흐름은 아래와 같습니다.

1. 송신 측에서 `encode`로 파일을 QR PNG 세트로 만든다.
2. 생성된 QR PNG를 `slide`나 다른 방식으로 송출한다.
3. 수신 측에서 `capture` 또는 수집된 PNG 세트를 준비한다.
4. 수신 측에서 `decode`로 원본 파일을 복원한다.

## 준비물

- 송신 측: `sender` jar
- 수신 측: `receiver` jar
- 입력 파일이 들어 있는 디렉터리
- QR PNG를 저장할 디렉터리
- 복원 결과를 저장할 디렉터리

## 기본 경로

`--in`/`--out`을 생략하면 **jar가 있는 폴더 기준**의 기본 디렉터리를 씁니다. 기본 파이프라인:

```
encode( source -> encoded ) -> slide( encoded ) -> capture( -> captured ) -> decode( captured -> decoded )
```

- 즉 jar를 둔 폴더에서 `encode`만 실행하면 `source`를 읽어 `encoded`에 씁니다.
- 디렉터리 이름을 바꾸려면 jar 옆에 `airbridge-paths.properties`를 두고 키를 덮어씁니다.

  ```properties
  dir.source=source
  dir.encoded=encoded
  dir.captured=captured
  dir.decoded=decoded
  ```

- GUI도 각 입력/출력 칸을 같은 기본 경로로 채웁니다.

명령을 실행하면 시작 시 배너가 출력됩니다.

## 1. encode

입력 디렉터리의 대상 파일을 읽어 QR PNG로 변환합니다.

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out
```

GUI로 인코딩할 수도 있습니다.

```bash
java -jar build/libs/sender-<version>.jar gui
```

`Encode` 탭에서 입력 디렉터리와 출력 디렉터리를 선택한 뒤 `Encode`를
누릅니다. 같은 GUI의 `Slide` 탭에서 슬라이드 입력 디렉터리를 고를 수 있고,
`Encode` 탭의 `Slide` 버튼은 출력 디렉터리가 입력돼 있으면 그 디렉터리를
바로 슬라이드 입력으로 엽니다.

encode 실행 중에는 결과가 중간에 다른 설정과 섞이지 않도록 Encode 탭의
입력값이 잠깁니다. 잠기는 대상은 입력/출력/encode root, 오류 보정,
chunk size, QR size, label height, files per folder, targets, skip dirs,
exclude, 변환 옵션, folder structure입니다. 실행 중단이 필요하면
`Stop`을 누릅니다. Stop으로 중단되면 이번 encode 실행에서 생성한 QR PNG와
manifest 파일은 정리하고, 기존에 있던 파일이나 비어 있지 않은
디렉터리는 유지합니다.

Browse 선택창은 macOS에서는 Finder 스타일 창을 사용하고, Windows/리눅스에서는
폴더 선택 전용 Swing 디렉터리 선택창을 사용합니다(시스템 Look&Feel 적용). 입력칸
경로가 유효하면 그 위치에서 시작하고, 비어 있거나 유효하지 않으면 앱을 시작한
위치에서 시작합니다.

주요 입력:

- `--in`: 인코딩할 파일이 있는 디렉터리 (생략 시 jar 옆 `source`)
- `--out`: QR PNG를 저장할 디렉터리 (생략 시 jar 옆 `encoded`)
- `--repair-overhead`: fountain 복구 심볼 여유분 비율(기본 0.5). 소스 심볼 `k`개에 더해
  `ceil(k×비율)`개의 복구 QR을 만든다. 카메라가 일부 프레임을 놓쳐도 복원되도록 하는 보험값으로,
  **올리면 손실에 강해지지만 QR 장수(슬라이드 시간)가 늘고**, 내리면 빠르지만 손실에 약해진다.
  깨끗한 환경이면 낮게(예: 0.2), 불안정하면 높게(예: 1.0). capture가 출력하는 권장값(§slide/capture)을
  참고해 다음 전송에서 조정한다.

주요 산출물:

- QR PNG 파일들
- `_manifest.txt`

옵션 예시:

폴더 구조를 유지하지 않고 500개 단위 폴더로 저장:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --no-folder-structure \
  --files-per-folder 500
```

XLSX는 CSV로, DOCX/PPTX는 텍스트로 변환 후 인코딩:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --convert-xlsx-to-csv \
  --convert-office-to-text
```

대상 확장자만 제한:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --target-extensions .java,.xml,.properties,.sql
```

주의:

- `.xlsx`는 옵션이 없으면 원본 그대로 인코딩됩니다.
- `.xls`는 자동 CSV 변환을 지원하지 않습니다.
- 변환 옵션을 켜면 복원 결과 파일 확장자도 변환된 형식 기준으로 나옵니다.

## 2. decode

QR PNG 세트를 읽어 원본 파일을 복원합니다.

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/qr-images \
  --out /path/restore
```

GUI로 복원할 수도 있습니다.

```bash
java -jar build/libs/receiver-<version>.jar gui
```

`Decode` 탭에서 QR PNG 입력 디렉터리와 복원 출력 디렉터리를 선택한 뒤
`Decode`를 누릅니다. CLI `decode`와 같은 복원 로직을 사용합니다.
복원이 실행되는 동안에는 Decode 탭의 입력값을 바꿀 수 없습니다.
Browse 선택창은 macOS에서는 Finder 스타일 창을 사용하고, Windows/리눅스에서는
폴더 선택 전용 Swing 디렉터리 선택창을 사용합니다(시스템 Look&Feel 적용). 입력칸
경로가 유효하면 그 위치에서 시작하고, 비어 있거나 유효하지 않으면 앱을 시작한
위치에서 시작합니다.

주요 입력:

- `--in`: QR PNG가 들어 있는 디렉터리 (생략 시 jar 옆 `captured`)
- `--out`: 복원 결과를 저장할 디렉터리 (생략 시 jar 옆 `decoded`)
- `--decode-workers`: QR 읽기 작업 스레드 수

주요 산출물:

- 복원된 원본 파일들
- `_restore_result.txt`

예:

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/qr-images \
  --out /path/restore \
  --decode-workers 4
```

복원 성공한 QR PNG는 입력 폴더 옆의 `*-success` 디렉터리로 이동될 수 있습니다. 복원이
이미 끝난 파일의 남은 QR(복구용 여분)도 함께 이동됩니다.

예:

- `batch/0001.png` 성공 후 `batch-success/0001.png`

같은 입력 폴더로 decode를 다시 실행하면 `*-success` 디렉터리는 건너뛰므로, 이미 복원된
QR을 다시 처리하지 않습니다.

## 결과 파일 읽는 법

`_manifest.txt`:

- encode 시 생성
- 어떤 파일이 몇 장의 QR로 만들어졌는지 확인할 때 사용

`_restore_result.txt`:

- decode 시 생성
- 복원 성공/실패 여부를 확인할 때 사용

예시:

```text
O src/main/java/App.java - OK
X src/main/java/App.java - INCOMPLETE (심볼 8/10 소스, 복원 불가)
X src/main/java/App.java - HASH_MISMATCH
X src/main/java/App.java - DECODE_ERROR
```

의미:

- `OK`: 정상 복원
- `INCOMPLETE`: 복원에 필요한 QR(심볼)을 충분히 못 모음. 특정 한 장이 아니라 **개수**가
  부족한 것이므로, 같은 화면을 한 번 더 재생/캡처하거나 다음 전송에서 `--repair-overhead`를
  올려 여유 QR을 늘리면 해결됩니다. (괄호의 `8/10`은 소스 심볼 10개 중 8개만 모였다는 뜻)
- `HASH_MISMATCH`: 복원은 됐지만 내용 불일치
- `DECODE_ERROR`: QR payload 복원 실패

## 빠른 전체 예시

송신 측:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /work/source \
  --out /work/qr
```

수신 측:

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /work/qr \
  --out /work/restored
```

## 스모크 체크

- `java -jar build/libs/sender-<version>.jar encode --help`
- `java -jar build/libs/receiver-<version>.jar decode --help`

## 관련 문서

- `deploy-sender.md`
- `deploy-receiver.md`
- `slide-capture.md`
- `warning.ko.md`
