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

## 1. encode

입력 디렉터리의 대상 파일을 읽어 QR PNG로 변환합니다.

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT
```

주요 입력:

- `--in`: 인코딩할 파일이 있는 디렉터리
- `--out`: QR PNG를 저장할 디렉터리
- `--project-name`: QR payload 안에 들어가는 프로젝트 이름

주요 산출물:

- QR PNG 파일들
- `_manifest.txt`

옵션 예시:

폴더 구조를 유지하지 않고 500개 단위 폴더로 저장:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT \
  --no-folder-structure \
  --files-per-folder 500
```

XLSX는 CSV로, DOCX/PPTX는 텍스트로 변환 후 인코딩:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT \
  --convert-xlsx-to-csv \
  --convert-office-to-text
```

대상 확장자만 제한:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT \
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

주요 입력:

- `--in`: QR PNG가 들어 있는 디렉터리
- `--out`: 복원 결과를 저장할 디렉터리
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

복원 성공한 QR PNG는 입력 폴더 옆의 `*-success` 디렉터리로 이동될 수 있습니다.

예:

- `batch/0001.png` 성공 후 `batch-success/0001.png`

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
X src/main/java/App.java - INCOMPLETE (누락: [2, 5])
X src/main/java/App.java - HASH_MISMATCH
X src/main/java/App.java - DECODE_ERROR
```

의미:

- `OK`: 정상 복원
- `INCOMPLETE`: 일부 QR 청크 누락
- `HASH_MISMATCH`: 복원은 됐지만 내용 불일치
- `DECODE_ERROR`: QR payload 복원 실패

## 빠른 전체 예시

송신 측:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /work/source \
  --out /work/qr \
  --project-name DEMO
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
