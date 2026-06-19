# air-bridge TODO

기준 시점: 2026-05-26

## 현재 기준

- 최종 배포 산출물은 `sender`, `receiver` 두 앱만 유지한다.
- 루트 구조는 `apps/*`, `libs/*` 기준으로 유지한다.
- 공개 명령은 아래 기준으로 유지한다.
  - `sender`: `encode`, `gui`, `slide`, `unpack`
  - `receiver`: `decode`, `capture`, `gui`, `identify`, `pack`
- `printer`는 별도 명령이 아니라 `--help`, `--version`에서 쓰는 공통 배너 출력으로 본다.
- 공통 배너와 버전 출력 유틸은 `common`에 둔다.
- 자동 테스트는 이미 들어가 있으며 2026-05-26 기준 `./gradlew check`로 통과 확인했다.
- 앱 버전 표기는 `{major}.{minor}.{yymmdd}.{hh24mi}` 형식으로 관리한다.
- QR 전송 파이프라인은 `encode -> slide -> capture -> decode`로 본다.
- `identify -> pack -> unpack`는 `jar` 또는 `zip` 반입을 돕는 보조 흐름으로 본다.
- CLI/GUI 병행 지원은 현재 구현 문서 기준으로 유지하고, `gui-cli-plan.md`는
  계획 기록으로 남길지 정리할지 별도 판단한다.

## 남은 작업

### 1. 정적분석 후속 조치

2026-05-26 기준 `./gradlew check`는 통과했지만, 별도 SpotBugs/Checkstyle/PMD
같은 정적분석 플러그인은 아직 없습니다. 아래 항목은 코드 리딩 기반으로 확인한
후속 조치입니다.

#### 예상 버그

- [완료] `EncodeService`: `--no-folder-structure` 사용 시 QR 파일명이 basename 기반이라
  `a/sample.txt`, `b/sample.txt`처럼 같은 이름의 파일이 같은 출력 폴더에서
  덮어써질 수 있다. → 평탄화 출력 시 상대경로 기반 `flatSafePrefix`를 쓰도록 수정.
  `reencode`도 항상 평탄화하므로 동일 적용. (회귀 테스트 추가)
- [완료] `EncodeService`: `--encode-root`가 실제 소스 파일의 상위 경로인지 검증하지
  않는다. → `EncodeService.isSourceUnderRoot`로 검증. `EncodeService.encode` 시작 시
  가드(IllegalArgumentException, GUI 포함 모든 호출자 보호), CLI `encode`에는
  친절한 `[ERROR]` 메시지 추가. (회귀 테스트 추가)
- [완료] `SlideApp`: `imageCache`와 `loadingImages`가 EDT와 이미지 로더 스레드에서
  동시에 접근된다. → EDT 쪽 `clear()`/`size()`도 `imageCache` 모니터로 보호
  (로더와 동일 락). `cacheSize()` 헬퍼 추가.
- [완료] `SourceCollector`: exclude path 비교가 문자열 `startsWith`라 `/src/a` 제외 시
  `/src/abc`도 제외될 수 있다. → `Path.equals`/`Path.startsWith` 기반 경계 비교로
  변경(`isExcluded`). 제외 디렉터리는 `SKIP_SUBTREE`로 가지치기. (회귀 테스트 추가)

#### 불필요 파일과 정리 대상

- [완료] git에 tracked 된 `apps/*/bin/main/*`, `libs/*/bin/main/*` 리소스 파일은 빌드
  산출물 성격이다. → `git rm --cached`로 추적 해제.
- [완료] `.gitignore`에 `**/bin/`을 추가해 IDE/Gradle이 만든 `bin` 출력물이 다시
  들어오지 않게 한다.
- [완료] `command.gui.description` 등 `command.<name>.description` 리소스 키는 현재
  `@Command(description = "...")` 하드코딩 때문에 help 출력에 쓰이지 않았다.
  `@Command`에는 `descriptionKey` 속성이 없으므로, 루트 설명과 동일하게
  `newCommandLine()`에서 번들 키를 서브커맨드 spec에 수동 주입하도록 했다
  (`applySubcommandDescriptions`). 이제 명령 설명도 `--lang`에 따라 현지화된다.
  (어노테이션 `description=`은 영어 fallback로 남겨둠. 회귀 테스트 추가)
- `docs/dev/gui-cli-plan.md`는 구현 계획 문서다. 현재 구현 상태와 맞춰 유지할지,
  완료된 계획 기록으로 제거할지 정리한다.

### 2. GUI / CLI 병행 지원 후속

핵심 전송 흐름의 GUI/CLI 병행 지원은 현재 구현에 들어가 있다. 남은 항목은
보조 유틸리티를 GUI에 넣을지 판단하는 일이다.

- `sender reencode`를 사용자 GUI에 노출할지 재검토한다.
- `receiver identify`, `receiver pack`, `sender unpack`는 CLI 유지가 기본값이며,
  GUI 편입은 실제 사용 빈도와 운영 복잡도를 보고 별도 결정한다.
- `gui-cli-plan.md`에 남은 완료 전 계획 표현을 현재 구현 기준으로 정리하거나
  계획 기록 문서로 분리한다.

### 3. 사용자 문서 정리

README와 `docs/user/*`에는 GUI 실행 흐름이 반영되어 있다. 남은 작업은 정적분석
후속 조치나 GUI/CLI 후속 판단으로 실제 동작이 바뀔 때 맞춰 갱신한다.

### 4. 메모리 및 성능

- [완료] `encode`/`reencode`가 파일 전체를 `byte[]`와 Base64 문자열로 한 번에 올리던 구조 제거.
  `CodecSupport.compressAndEncodeToFile`로 소스를 GZIP+Base64 스트리밍해 임시파일에 1회 기록하고
  (같은 패스에서 SHA-256 계산), 청크는 임시파일에서 윈도우로 읽는다. `FileEncodingPlan`은
  `AutoCloseable`로 임시파일을 정리. peak heap이 파일 크기와 무관(≈O(buffer))해졌다.
  QR payload는 바이트 동일 → receiver/decode/round-trip 무변경. (대용량 round-trip 수동 검증 완료)
- [완료] `decode` 파일별 복원도 스트리밍화. `CodecSupport.decodeDecompressToFile` +
  `FileChunks.orderedEncodedStream`으로 청크를 순서대로 흘려 Base64+GZIP 해제를 임시파일에
  스트리밍 기록하고(같은 패스에서 SHA-256), 해시 검증 후 최종 경로로 move. 거대 join String과
  full 복원 바이트(2N) 동시 적재 제거. 잘못된 payload는 임시파일만 남기고 최종 경로엔 안 씀.
  (대용량 round-trip 수동 검증 완료)
- [완료] decode "완료 즉시 복원+evict". 파일이 `FileChunks.isComplete()`가 되는 즉시 복원하고
  `fileChunkMap`에서 제거 → 메모리에는 진행 중 파일의 청크만 남고 전송 전체가 한꺼번에 쌓이지
  않는다. 이미 종료된 파일의 지연/중복 청크는 `finalizedPaths`로 무시(1회만 복원). 루프 후
  남은 항목은 INCOMPLETE만. 보고는 `contains` 기반이라 순서 변경 무해, 카운트 의미 보존.
  (다파일 병렬 decode + 중복 청크 단위테스트 + 대용량 round-trip 검증 완료)
- 큰 파일이나 많은 파일에서 heap 사용량이 급증하지 않도록 스트리밍 또는 단계별 처리 방식을 검토한다.
  (encode/reencode/decode 모두 반영 완료.)
- [완료] `print-html`(모든 PNG를 base64 inline으로 한 파일에 모으던 메모리 폭탄)은
  분할/외부참조 개선 대신 기능 자체를 제거했다. CLI `--print-html` 옵션, GUI 체크박스,
  `EncodeWorkflow.Request.printHtml`, `EncodeService.generatePrintHtml`, 리소스 키,
  관련 문서를 모두 삭제. 인쇄가 필요하면 개별 QR PNG를 직접 인쇄한다.
- [완료] 대량 QR 세트의 처리 시간/메모리 측정 벤치마크 추가(JMH 없이 stdlib만).
  `apps/receiver/src/test/java/airbridge/bench/RoundTripBenchmark.java` + `./gradlew :receiver:benchmark`.
  단계별 wall-clock과 peak heap(백그라운드 샘플러 동시 사용량)을 출력. `-Pbench.maxHeapMb`로 힙을
  조여 스트리밍 효과 검증 가능(예: 200MB 소스가 128MB 힙에서 OOM 없이 round-trip, encode peak ~52MB).

### 5. 구조 리팩터링 후속 검토

현재는 `apps/*`, `libs/*`의 기존 모듈 경계를 유지하고, 우선 서비스 계약을
정리합니다. `transfer-core`나 `carrier-qr` 같은 추가 모듈 분리는 계약이
충분히 안정화된 뒤 별도 작업으로 검토합니다.

검토할 때의 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.

### 6. 전송 포맷 · 처리율(throughput) 개선

QR 심볼 자체보다 "Base64 오버헤드 + 순차 청크 전부 수집 모델"이 병목이라는
판단에 따른 개선안. ROI(이득/비용) 순서로 1 → 2 → 3 으로 진행한다.

- [ ] **1. Base64 제거 → QR 8-bit 바이트 모드 직접 사용** (이득 ≈ 1.33×, 정확도 손실 0)
  - 현재 `gzip → Base64 → QR 텍스트` 에서 Base64는 데이터를 33% 부풀리는 순수 낭비.
  - QR 바이트 모드로 gzip 바이트를 직접 싣고, `QrPayloadSupport`의 구분자 텍스트
    헤더를 바이너리 프레이밍으로 교체.
  - 영향 범위: `CodecSupport`(Base64 단계 제거), `QrPayloadSupport`(헤더 포맷),
    `QrImageWriter`(byte 모드), `QrDecodeSupport`/`DecodeService`(파싱).
    payload 포맷 변경이므로 sender·receiver·tests·docs 동시 갱신(§5 기준).
  - 가장 싸고 무위험 → 먼저.

- [ ] **2. Fountain code(RaptorQ, RFC 6330) 도입** (단방향 채널의 신뢰도·실효 처리율)
  - 현재 순차 인덱스 청크는 특정 프레임 드롭 시 그 청크가 다시 올 때까지 대기 →
    단방향 카메라 채널에서 비효율.
  - 파일을 동등 심볼 스트림으로 인코딩하고, 수신측이 임의의 K(1+ε)개만 모이면 복원.
    "빠진 청크 재전송" 협상 자체를 제거.
  - 영향 범위: 인코딩 측 청크 생성(`FileEncodingPlan`/`EncodeService`),
    수신 측 수집·복원(`FileChunks`의 TreeMap 인덱스 모델 → fountain 디코더로 대체),
    payload 헤더에 심볼 메타(블록/심볼 id) 추가.

- [ ] **3. 4색 컬러 심볼 (흰/녹/적/흑, 2 bit/셀)** (net ≈ 1.4~1.6×)
  - 휘도 255/150/76/0 으로 네 단계가 또렷 → 채도가 압축으로 무너져도 밝기만으로
    구분 가능(5색의 Blue↔Black 충돌 회피). 모노크롬에 가까운 견고함 유지.
  - 필수 동반 작업:
    - 프레임마다 고정 위치 **컬러 캘리브레이션 패치**(화이트밸런스/감마/조명 정규화).
    - RGB 유클리드 거리 대신 **휘도 우선 + 색상(hue) 보조 분류기**.
    - 크로마 서브샘플링(4:2:0) 대비 **셀 크기 하한** 확보.
  - 가장 엔지니어링 비용이 큰 항목 → 1·2 적용 후에도 추가 처리율이 필요할 때 진행.
  - 영향 범위: `QrImageWriter`(컬러 렌더링) 또는 별도 carrier 모듈, `QrDecodeSupport`
    (컬러 분류·캘리브레이션), payload 비트 패킹.

---

1. [완료] 모든 프로세스가 시작 전에 banner를 print 했으면 좋겠음
   → encode/decode/capture/slide/gui 각 `call()` 시작에서 `BannerSupport.print(...)`.
2. [완료] 특히 capture시 준비가 끝난 순간을 인식하기에 banner가 최고인 듯 함
   → `CaptureListener.onReady()` 추가, `grabber.start()` 직후 호출. CLI는 onReady에서
   실제 배너(BannerSupport.render)를 다시 출력해 "지금 slide 재생" 신호. GUI는 상태/로그 표시.
3. windows 문제
   3 - 1. windows에서 receiver가 정상 동작 하지 않음
   3 - 2. windows sender에서 폴더 선택 하는 화면 개선 가능?
   3 - 3.
4. encode, decode, capture, slide에 in, out 경로를 optional로 받고 기본 값 경로 고정할 수 있나? jar 위치 기준으로 `./captured`, `./decoded`, `./encoded` 이런 식으로

- gui 에서도 해당 경로를 기본으로 잡도록

5. receiver에서 capture 도구 선택하는 방법이 좀 더 사용자 친화적이였으면 좋겠음
