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
- 앱 버전 표기는 `{major}.{minor}.{yymmdd}.{hh24mi}` 형식으로 관리한다.
- QR 전송 파이프라인은 `encode -> slide -> capture -> decode`로 본다.
- `identify -> pack -> unpack`는 `jar` 또는 `zip` 반입을 돕는 보조 흐름으로 본다.

## 남은 작업

### 1. 정적분석 후속 조치

- SpotBugs/Checkstyle/PMD 같은 정적분석 플러그인은 아직 없다. 도입 여부를 검토한다.
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

### 4. Windows 실기 검증

- `receiver`의 카메라 인식/캡처 동작을 Windows 실기에서 최종 확인한다.
  (디바이스 이름 열거·probe 타임아웃·폴더 picker 코드는 반영됨. ffmpeg 미설치 시
  디바이스 이름은 안 뜨고 brute-force로 동작 → 필요하면 번들 ffmpeg 사용으로 후속 개선.)

### 5. 구조 리팩터링 후속 검토

현재는 `apps/*`, `libs/*`의 기존 모듈 경계를 유지하고, 우선 서비스 계약을
정리합니다. `transfer-core`나 `carrier-qr` 같은 추가 모듈 분리는 계약이
충분히 안정화된 뒤 별도 작업으로 검토합니다.

검토할 때의 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.

#### 검토 결과 (2026-06-20)

- 의존성 방향 기준 3/4 충족: sender는 capture 미의존, receiver 메인은 sender/slide 미의존
  (sender는 test 전용), payload 변경 동시 갱신은 §6.1에서 실증.
- capture 책임 경계만 부분 충족이었음 — `apps/receiver/QrDecodeSupport`와
  `libs/capture/CaptureQrDecodeSupport`가 같은 QR 디코드 머신을 각자 구현(드리프트).
- **중간 조치 완료**: 디코드 머신을 `airbridge.common.qr.QrImageDecoder`로 통합하고
  양측이 `Strategy`로 호출하도록 변경(동작 보존, charset 단일화). 의존성에 `capture -> common`,
  `common -> zxing` 추가.
- **`transfer-core`/`carrier-qr` 정식 모듈 분리는 계속 보류**: payload 계약이 아직 유동적
  (§6.1로 변경됨, §6.2 보류). todo의 "계약 안정화 후" 게이트 미충족. §6.2 방향 확정 후 재검토.

### 6. 전송 포맷 · 처리율(throughput) 개선

QR 심볼 자체보다 "Base64 오버헤드 + 순차 청크 전부 수집 모델"이 병목이라는
판단에 따른 개선안. ROI(이득/비용) 순서로 1 → 2 → 3 으로 진행한다.

- [x] **1. Base64 제거 → QR 8-bit 바이트 모드 직접 사용** (이득 ≈ 1.33×, 정확도 손실 0) — 완료
  - `gzip → Base64 → QR 텍스트`에서 Base64(33% 부풀림)를 제거.
  - QR 바이트 모드로 gzip 바이트를 직접 싣고(`ISO-8859-1` 1:1 charset, ECI 없음),
    `QrPayloadSupport`의 구분자 텍스트 헤더를 바이너리 프레이밍(magic+길이접두 헤더+raw data)으로 교체.
  - 반영: `CodecSupport`(gzip 전용 `compress/decompress`·`compressToFile/decompressToFile`),
    `QrPayloadSupport`(바이너리 build/parse), `QrImageWriter`(byte 모드),
    `QrDecodeSupport`/`QrDecodedChunk`/`FileChunks`/`DecodeService`(byte[] 청크·파싱),
    `FileEncodingPlan`(gzip 임시파일·`readChunk` byte[]). tests·docs 동시 갱신, round-trip 검증 완료.

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
