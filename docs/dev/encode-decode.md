# encode / decode

`sender encode`와 `receiver decode`의 내부 동작을 개발 기준으로 정리한 문서입니다.

## 범위

- `encode`: 입력 파일을 QR PNG 시퀀스로 변환
- `decode`: QR PNG 시퀀스를 읽어 원본 파일로 복원
- 공통 payload 형식, 청크 규칙, 실패 판정, 산출물

## 관련 구현

- `apps/sender/src/main/java/airbridge/sender/EncodeService.java`
- `apps/sender/src/main/java/airbridge/sender/FileEncodingPlan.java`
- `apps/receiver/src/main/java/airbridge/receiver/DecodeService.java`
- `apps/receiver/src/main/java/airbridge/receiver/FileChunks.java`
- `apps/receiver/src/main/java/airbridge/receiver/QrDecodeSupport.java`
- `libs/common/src/main/java/airbridge/common/QrPayloadSupport.java`
- `libs/common/src/main/java/airbridge/common/CodecSupport.java`

## encode 입력 처리

`EncodeService.encode(...)`는 먼저 `SourceCollector.collectSourceFiles(...)`로 대상 파일 목록을 만든다.

파일별 전처리 규칙은 `FileEncodingPlan.fromSourceFile(...)`에 모여 있다.

- 기본값: 원본 바이트 그대로 읽음
- `--convert-xlsx-to-csv`: `.xlsx`를 CSV 바이트로 변환하고 상대경로 확장자를 `.csv`로 바꿈
- `--convert-office-to-text`: `.docx`, `.pptx`를 텍스트 바이트로 변환하고 상대경로 확장자를 `.txt`로 바꿈
- `.xls`는 자동 CSV 변환 미지원이라 경고만 남기고 원본 그대로 처리

전처리 후 각 파일은 다음 값을 가진다.

- `relPath`: QR payload 안에 들어갈 상대경로
- `fileName`: QR 라벨에 표시할 파일명
- `convertedType`: 변환 여부 표시용 문자열
- `fileHash`: 전처리 후 바이트 기준 SHA-256
- `encoded`: GZIP + Base64 문자열
- `totalChunks`: `encoded.length / chunkDataSize` 기준 청크 수

중요한 점은 해시와 decode 대상 경로가 모두 전처리 후 결과 기준이라는 점입니다. 즉 `.xlsx -> .csv` 옵션을 켜면 encode/decode 관점의 원본은 `.csv`입니다.

## payload 형식

QR payload는 `QrPayloadSupport.buildPayload(...)`에서 만든다.

구조:

1. `"HDR"`
2. header separator `\u001E`
3. 필드 5개를 field separator `\u001F`로 연결한 헤더
4. header separator `\u001E`
5. 현재 청크의 Base64 substring

헤더 필드 순서:

1. `project`
2. `relPath`
3. `chunkIdx`
4. `totalChunks`
5. `fileHash.substring(0, 16)`

해시는 전체 SHA-256 중 앞 16자리만 payload에 실립니다.

## encode 청크 생성

파일 하나당 처리 순서는 아래와 같다.

1. 전처리 후 바이트를 `CodecSupport.compressAndEncode(...)`로 GZIP + Base64 한다.
2. `chunkDataSize` 단위로 문자열을 자른다.
3. 각 청크마다 payload 문자열을 만든다.
4. `QrImageWriter.generateQrImage(...)`로 QR PNG를 만든다.
5. 파일별 진행 로그를 남긴다.

파일명 규칙:

- 라벨 1행: `<fileName> [001/123]`
- 라벨 2행: `relPath`
- PNG 파일명: `<safePrefix>_001of123.png`

`safePrefix`는 파일명에서 basename과 extension을 `_`로 이어 붙인 값입니다.

예:

- `sample.txt` -> `sample_txt_001of010.png`

## encode 출력 구조

기본 산출물:

- QR PNG 파일들
- `_manifest.txt`
- 옵션 사용 시 `_print.html`

폴더 배치 규칙:

- `folderStructure=true`: 소스 상대경로 디렉터리를 유지
- `folderStructure=false`: `filesPerFolder` 단위로 `0000000`, `0000500` 같은 폴더를 만들어 분산 저장

`_manifest.txt`에는 아래 수준의 정보가 들어갑니다.

- 프로젝트명
- 소스 루트
- 실행 시각
- 파일별 상대경로, 원본 바이트 크기, QR 장수, 해시 앞 16자리
- 전체 파일 수 / QR 수 / 총 원본 바이트

`_print.html`은 각 PNG를 base64 data URI로 인라인한 단순 인쇄용 HTML이다.

## decode 입력 수집

`QrDecodeSupport.collectQrImageFiles(...)`는 입력 디렉터리 이하의 `.png`만 재귀 수집하고 정렬합니다.

`DecodeService.decode(...)`는 각 PNG에 대해 고정 크기 스레드풀로 decode task를 실행합니다.

- worker 수는 `decodeWorkers`
- 각 QR은 최대 3회 재시도
- `OutOfMemoryError`나 heap space 계열 실패는 재시도 대상

## QR 읽기 전략

`QrDecodeSupport.decodeQrPayloadWithRetries(...)`는 한 이미지에 대해 여러 변형을 순차 시도합니다.

- 기본 방향 + 90/180/270도 회전
- Hybrid / GlobalHistogram binarizer
- `TRY_HARDER` 힌트 유무
- 1.5x / 2x / 3x 스케일업
- 중앙 crop
- grid crop

이 단계는 payload 문자열을 얻는 데만 집중하고, 이후 payload 파싱과 파일 조립은 `DecodeService`가 처리한다.

## decode 파일 조립

QR 하나를 읽으면 `QrDecodedChunk`가 만들어지고, `relPath` 기준으로 `FileChunks`에 묶는다.

`FileChunks`가 동일 파일로 인정하는 조건:

- `project` 동일
- `totalChunks` 동일
- `hash16` 동일

청크 번호 범위를 벗어나면 즉시 오류다. 같은 청크 번호가 여러 번 들어오면 마지막 값으로 덮어쓴다.

복원 단계는 파일별로 아래 순서다.

1. 누락 청크 검사
2. 모든 chunkData 연결
3. Base64 decode + GZIP 해제
4. 복원 바이트의 SHA-256 앞 16자리 계산
5. payload의 `hash16`과 비교
6. 출력 경로를 `RelativePathSupport.resolveUnderRoot(...)`로 검증 후 파일 저장
7. 성공한 QR PNG는 원래 폴더의 sibling인 `*-success` 디렉터리로 이동

예:

- `qr/batch/a.png` 성공 후 이동 대상: `qr/batch-success/a.png`

## decode 결과 분류

`_restore_result.txt`에는 파일 또는 QR 단위 결과가 기록된다.

주요 유형:

- `O rel/path - OK`
- `X rel/path - INCOMPLETE (누락: [...])`
- `X rel/path - DECODE_ERROR`
- `X rel/path - HASH_MISMATCH`
- `X rel/path - INVALID_PATH`
- `! batch/file.png - QR_READ_ERROR`
- `! batch/file.png - INVALID_REL_PATH`

의미:

- `QR_READ_ERROR`: 이미지에서 QR payload를 읽지 못했거나 payload 처리 중 예외
- `INCOMPLETE`: 어떤 청크가 아예 없음
- `DECODE_ERROR`: Base64 또는 GZIP 복원 실패
- `HASH_MISMATCH`: 복원 바이트는 나왔지만 hash16 불일치
- `INVALID_REL_PATH` / `INVALID_PATH`: 경로 traversal 등 안전하지 않은 상대경로

## 경로 안전성

encode와 decode 모두 상대경로 안전성은 `RelativePathSupport`에 의존한다.

- encode reencode는 source root 아래 경로만 허용
- decode는 output root 밖으로 벗어나는 경로를 거부

즉 payload 안에 `../escape.bin` 같은 값이 들어와도 복원 파일은 생성되지 않습니다.

## reencode와의 연결

`reencode`는 `_restore_result.txt`를 읽어 실패 파일이나 누락 청크만 다시 만든다.

연결 포인트:

- `INCOMPLETE`, `DECODE_ERROR`, `HASH_MISMATCH` 항목을 입력으로 사용
- 변환 옵션이 켜져 있으면 `.csv` / `.txt` 상대경로를 원래 `.xlsx` / `.docx` / `.pptx` 소스로 역추적

즉 encode 쪽 상대경로 변환 규칙을 decode/reencode가 같이 이해하고 있어야 round-trip이 맞습니다.

## 테스트로 보장하는 내용

현재 테스트 기준 핵심 보장:

- `ReceiverRoundTripTest`: `sender encode` -> `receiver decode` end-to-end round trip
- `DecodeServiceTest`: 성공 복원, success 폴더 이동, incomplete, hash mismatch, QR read error, path traversal 차단

## 개발 시 주의점

- payload 포맷을 바꾸면 `encode`, `decode`, 테스트, 기존 산출물 호환성이 동시에 깨진다.
- hash 비교는 전체 SHA-256이 아니라 앞 16자리만 사용한다.
- chunking 기준은 바이트가 아니라 Base64 문자열 길이다.
- QR 파일명은 decode의 복원 근거가 아니다. 실제 복원 기준은 payload다.
- 성공 시 QR 원본 PNG를 `*-success`로 이동하므로, decode 입력 디렉터리를 후처리 파이프라인과 공유할 때 주의가 필요하다.
