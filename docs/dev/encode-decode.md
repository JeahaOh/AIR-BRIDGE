# encode / decode

`sender encode`와 `receiver decode`의 내부 동작을 개발 기준으로 정리한 문서입니다.

## 범위

- `encode`: 입력 파일을 QR PNG 시퀀스로 변환
- `decode`: QR PNG 시퀀스를 읽어 원본 파일로 복원
- 공통 payload 형식, 청크 규칙, 실패 판정, 산출물

## 관련 구현

- `apps/sender/src/main/java/airbridge/sender/EncodeService.java`
- `apps/sender/src/main/java/airbridge/sender/EncodeWorkflow.java`
- `apps/sender/src/main/java/airbridge/sender/gui/SenderGui.java`
- `apps/sender/src/main/java/airbridge/sender/FileEncodingPlan.java`
- `apps/receiver/src/main/java/airbridge/receiver/DecodeService.java`
- `apps/receiver/src/main/java/airbridge/receiver/DecodeWorkflow.java`
- `apps/receiver/src/main/java/airbridge/receiver/gui/ReceiverGui.java`
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
- `encodedSize`: GZIP 결과의 길이(바이트 수)
- `totalChunks`: `encodedSize / chunkDataSize` 기준 청크 수

GZIP 결과(`encoded`)는 메모리에 통째로 들고 있지 않고 임시파일에 스트리밍으로
1회 기록한다. 따라서 큰 파일을 encode해도 heap 사용량이 파일 크기에 비례해 늘지 않는다.
청크는 이 임시파일에서 윈도우 단위로 읽고, 파일 처리가 끝나면 임시파일을 삭제한다
(`FileEncodingPlan`은 `AutoCloseable`).

중요한 점은 해시와 decode 대상 경로가 모두 전처리 후 결과 기준이라는 점입니다. 즉 `.xlsx -> .csv` 옵션을 켜면 encode/decode 관점의 원본은 `.csv`입니다.

## payload 형식

QR payload는 `QrPayloadSupport.buildPayload(...)`에서 만든다. Base64 없이 GZIP 바이트를 QR
8-bit 바이트 모드로 직접 싣기 때문에, payload는 텍스트 구분자가 아니라 **바이너리 프레이밍**이다.
정수는 모두 big-endian.

```
magic    : 2 bytes  'A','B'
project  : u8 길이  + UTF-8 바이트
relPath  : u16 길이 + UTF-8 바이트
chunkIdx : u32
total    : u32
hash     : 8 bytes  (파일 SHA-256의 앞 8바이트 = 16 hex chars)
data     : 나머지 바이트 (현재 청크의 GZIP 윈도우)
```

- 해시는 전체 SHA-256 중 앞 16자리(8바이트)만 payload에 실린다.
- 바이트를 QR에 무손실로 싣기 위해 인코더/디코더 모두 `ISO-8859-1` charset을 쓴다(바이트↔문자
  1:1, QR 바이트 모드 기본 ECI라 ECI 세그먼트 없음). `QrImageWriter`는 `new String(bytes,
  ISO_8859_1)`로 인코딩하고, `QrDecodeSupport`는 `getText().getBytes(ISO_8859_1)`로 바이트를
  복원한 뒤 `QrPayloadSupport.parsePayload(byte[])`로 프레임을 해석한다.

## encode 청크 생성

파일 하나당 처리 순서는 아래와 같다.

1. 전처리 후 바이트를 `CodecSupport.compressToFile(...)`로 GZIP 압축해서
   임시파일에 스트리밍 기록한다(같은 패스에서 SHA-256도 계산). 메모리는 O(buffer)로 제한된다.
2. 임시파일을 `chunkDataSize`(바이트) 단위 윈도우로 읽는다(`FileEncodingPlan.readChunk`, byte[] 반환).
3. 각 청크마다 payload 바이트 프레임을 만든다.
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

폴더 배치 규칙:

- `folderStructure=true`: 소스 상대경로 디렉터리를 유지
- `folderStructure=false`: `filesPerFolder` 단위로 `0000000`, `0000500` 같은 폴더를 만들어 분산 저장

`_manifest.txt`에는 아래 수준의 정보가 들어갑니다.

- 프로젝트명
- 소스 루트
- 실행 시각
- 파일별 상대경로, 원본 바이트 크기, QR 장수, 해시 앞 16자리
- 전체 파일 수 / QR 수 / 총 원본 바이트

## encode GUI 실행 상태

`sender gui`의 Encode 탭은 `EncodeWorkflow`를 통해 CLI와 같은
`EncodeService`를 호출한다. Swing EDT를 막지 않도록 실제 encode는
`SwingWorker`에서 실행한다.

실행 중에는 아래 입력을 비활성화한다.

- input, output, encode root
- project, error correction level
- chunk size, QR size, label height, files per folder
- targets, skip dirs, exclude
- XLSX/Office 변환 옵션
- folder structure
- 각 Browse 버튼과 Encode 버튼

`Stop` 버튼은 `AtomicBoolean` cancellation supplier와 `SwingWorker.cancel(true)`를
함께 사용한다. `EncodeService`는 파일별 처리와 청크 생성 사이에서 cancellation을
확인하고, 취소되면 이번 실행에서 만든 파일을 삭제한다. 삭제 대상은 생성된 QR PNG와
`_manifest.txt`이며, 비어 있는 생성 디렉터리만 제거한다. 비어 있지
않은 디렉터리는 기존 사용자 파일 보호를 위해 유지한다.

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

파일은 모든 청크가 모이는(`FileChunks.isComplete()`) 즉시 복원되고 `fileChunkMap`에서 제거된다.
따라서 메모리에는 "아직 진행 중인 파일"의 청크만 남고, 전송 전체의 청크가 한꺼번에 쌓이지 않는다.
이미 복원(또는 종료 판정)된 파일에 대한 지연/중복 청크는 `finalizedPaths`로 무시한다.
QR 루프가 끝난 뒤 `fileChunkMap`에 남은 항목은 완성되지 못한(INCOMPLETE) 파일뿐이다.

복원 한 파일의 순서(`restoreCompletedFile`):

1. 출력 경로를 `RelativePathSupport.resolveUnderRoot(...)`로 검증
2. 청크를 순서대로 흘려(`FileChunks.orderedEncodedStream`, GZIP 바이트) GZIP 해제한 결과를
   출력 디렉터리의 임시파일(`.airbridge-restore-*.part`)에 스트리밍 기록
   (`CodecSupport.decompressToFile`). 같은 패스에서 SHA-256을 계산하므로 복원 바이트를
   메모리에 통째로 들고 있지 않는다.
3. 계산된 SHA-256 앞 16자리를 payload의 `hash16`과 비교
4. 일치하면 임시파일을 최종 경로로 move(불일치/오류 시 임시파일 삭제 → 잘못된 파일이 최종 경로에
   남지 않는다)
5. 성공한 QR PNG는 원래 폴더의 sibling인 `*-success` 디렉터리로 이동

즉 한 파일 복원은 메모리 O(buffer)이고, 전체 decode 메모리도 진행 중 파일 수에 비례하도록 묶인다.

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
- `DECODE_ERROR`: GZIP 복원 실패
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
  (중첩 경로, `--encode-root` 상위 경로 상대화 포함)
- `DecodeServiceTest`: 성공 복원, success 폴더 이동, incomplete, hash mismatch, QR read error,
  path traversal 차단, 완료 후 중복 청크 무시(1회 복원)
- `CodecSupportTest`: 스트리밍 GZIP 압축/해제가 in-memory 경로와 바이트 동일 + round-trip
- `QrPayloadSupportTest`: 바이너리 프레임 build/parse round trip(매직·UTF-8 경로·잘린 프레임 거부)

## 벤치마크 (메모리/시간 측정)

JMH 없이 stdlib만 쓰는 경량 하니스가 있다. 합성 소스 트리를 만들어 encode -> decode를
돌리고 단계별 wall-clock과 peak heap(백그라운드 샘플러로 측정한 동시 사용량)을 출력한다.

- 코드: `apps/receiver/src/test/java/airbridge/bench/RoundTripBenchmark.java`
- 실행: `./gradlew :receiver:benchmark -Pbench.*=...`
- 파라미터: `bench.fileCount`, `bench.fileSizeKb`, `bench.chunkSize`,
  `bench.compressible`, `bench.decodeWorkers`, `bench.seed`, `bench.maxHeapMb`

예) 200MB 소스를 128MB 힙에서 돌려도 OOM 없이 round-trip 되는지 확인:

```
./gradlew :receiver:benchmark -Pbench.fileCount=1 -Pbench.fileSizeKb=204800 \
    -Pbench.compressible=true -Pbench.maxHeapMb=128
```

스트리밍 적용 후에는 peak heap이 소스 크기와 분리된다(예: 위 케이스 encode peak ~52MB).
타이트한 힙에서는 GC가 한계 근처까지 garbage를 모았다가 회수하므로 decode peak는 maxHeap에
가깝게 보일 수 있다(작업 집합 크기가 아니라 GC 동작의 반영).

## 기본 경로(in/out)와 설정

`--in`/`--out`을 생략하면 jar 위치 기준 기본 디렉터리를 쓴다(`AppPaths`, common 모듈).
기본 파이프라인:

```
encode( source -> encoded ) -> slide( encoded ) -> capture( -> captured ) -> decode( captured -> decoded )
```

- base 디렉터리: 실행 jar가 있는 폴더(비-jar 실행이면 `user.dir`).
- 디렉터리 이름은 `airbridge-paths.properties`로 관리. 번들 기본값은 `libs/common/src/main/resources/`,
  런타임 오버라이드는 **jar 옆에 같은 이름의 파일**을 두면 키별로 덮어쓴다
  (`dir.source`/`dir.encoded`/`dir.captured`/`dir.decoded`).
- CLI는 미지정 시 기본값, GUI는 입력/출력 필드 초기값으로 동일 경로를 채운다.

## 개발 시 주의점

- payload 포맷을 바꾸면 `encode`, `decode`, 테스트, 기존 산출물 호환성이 동시에 깨진다.
- hash 비교는 전체 SHA-256이 아니라 앞 16자리만 사용한다.
- chunking 기준은 GZIP 바이트 길이다(Base64 단계 없음). QR은 8-bit 바이트 모드를 쓴다.
- QR 파일명은 decode의 복원 근거가 아니다. 실제 복원 기준은 payload다.
- 성공 시 QR 원본 PNG를 `*-success`로 이동하므로, decode 입력 디렉터리를 후처리 파이프라인과 공유할 때 주의가 필요하다.
- GUI 실행 중 입력 잠금과 취소 동작은 core service가 아니라 GUI adapter와 workflow 계약에서 관리한다.
