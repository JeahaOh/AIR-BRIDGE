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
- GUI/CLI 병행 지원은 현 상태를 유지한다. 핵심 전송 흐름은 GUI·CLI 둘 다 제공하고,
  보조 유틸(`identify`/`pack`/`unpack`/`reencode`)은 CLI 유지가 기본이다.

## 남은 작업

### 1. Windows 실기 검증

- `receiver`의 카메라 인식/캡처 동작을 Windows 실기에서 최종 확인한다.
  (디바이스 이름 열거·probe 타임아웃·폴더 picker 코드는 반영됨. ffmpeg 미설치 시
  디바이스 이름은 안 뜨고 brute-force로 동작 → 필요하면 번들 ffmpeg 사용으로 후속 개선.)

### 2. 구조 리팩터링 후속 검토

`transfer-core`/`carrier-qr` 정식 모듈 분리는 payload 계약이 안정화된 뒤
(§3.1 fountain code 방향 확정 후) 별도 작업으로 검토한다. 그 전까지는
`apps/*`, `libs/*`의 기존 모듈 경계를 유지한다.

검토 시 유지할 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.

### 3. 전송 포맷 · 처리율(throughput) 개선

"순차 인덱스 청크를 전부 수집해야 복원" 모델이 병목이라는 판단에 따른 개선안.
ROI(이득/비용) 순서로 1 → 2 로 진행한다.

- [ ] **1. Fountain code(RaptorQ, RFC 6330) 도입** (단방향 채널의 신뢰도·실효 처리율)
  - 현재 순차 인덱스 청크는 특정 프레임 드롭 시 그 청크가 다시 올 때까지 대기 →
    단방향 카메라 채널에서 비효율.
  - 파일을 동등 심볼 스트림으로 인코딩하고, 수신측이 임의의 K(1+ε)개만 모이면 복원.
    "빠진 청크 재전송" 협상 자체를 제거.
  - 영향 범위: 인코딩 측 청크 생성(`FileEncodingPlan`/`EncodeService`),
    수신 측 수집·복원(`FileChunks`의 TreeMap 인덱스 모델 → fountain 디코더로 대체),
    payload 헤더에 심볼 메타(블록/심볼 id) 추가.

- [ ] **2. 4색 컬러 심볼 (흰/녹/적/흑, 2 bit/셀)** (net ≈ 1.4~1.6×)
  - 휘도 255/150/76/0 으로 네 단계가 또렷 → 채도가 압축으로 무너져도 밝기만으로
    구분 가능(5색의 Blue↔Black 충돌 회피). 모노크롬에 가까운 견고함 유지.
  - 필수 동반 작업:
    - 프레임마다 고정 위치 **컬러 캘리브레이션 패치**(화이트밸런스/감마/조명 정규화).
    - RGB 유클리드 거리 대신 **휘도 우선 + 색상(hue) 보조 분류기**.
    - 크로마 서브샘플링(4:2:0) 대비 **셀 크기 하한** 확보.
  - 가장 엔지니어링 비용이 큰 항목 → 1(fountain) 적용 후에도 추가 처리율이 필요할 때 진행.
  - 영향 범위: `QrImageWriter`(컬러 렌더링) 또는 별도 carrier 모듈, `QrDecodeSupport`
    (컬러 분류·캘리브레이션), payload 비트 패킹.

### 4. 빌드·산출물·성능 개선

- [ ] **P4d. `chunkDataSize` 기본값 튜닝** — 바이트 모드 전환 후 QR 용량 한계까지 키워 QR 장수↓.
  버전/ECC 용량 검증 필요 → 별도 작업.
