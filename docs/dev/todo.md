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

## 남은 작업 (진행 순서)

핵심 작업은 아래 순서로 진행한다. ①이 payload 계약을 확정하므로 가장 먼저고,
④(모듈 분리)는 계약이 굳은 뒤 마지막이다. 순서에 묶이지 않는 독립 작업은 맨 끝에 모았다.

```
① fountain code  →  ② slide↔capture 페이싱  →  ③ 4색 컬러 심볼  →  ④ 구조 리팩터링(모듈 분리)
```

### 1. Fountain code 도입 (완료, 2026-06-20)

단방향 채널의 신뢰도·실효 처리율 개선이자, 이후 작업이 기다리던 **payload 계약 확정** 단계.

- [x] **자체 구현 systematic LT(Luby Transform) fountain 부호로 순차 인덱스 청크 모델을 대체** — 완료
  - RaptorQ 라이브러리(OpenRQ)는 Maven Central 미배포라 의존성 0인 자체 LT 구현 채택.
    `libs/common/.../fountain`(`LtFountain`/`LtDecoder`). 이웃 생성은 플랫폼 무관 결정적
    (Ideal Soliton, `Math.log`/`sqrt` 회피)이라 송·수신이 같은 이웃 집합을 계산한다.
  - 시스템틱(esi 0..k-1 = 소스 그대로) + 복구 심볼(esi>=k = 소스 XOR). 수신측은 임의 순서로
    distinct 심볼을 ~k개 모으면 복원 → "빠진 청크 재전송" 협상 제거. 기본 복구 여유분 0.5
    (`SenderDefaults.DEFAULT_REPAIR_OVERHEAD`).
  - 반영: 프레임 교체(`QrPayloadSupport` → relPath+hash+k+gzipLen+esi+symbol),
    `FileEncodingPlan.readSymbol`(패딩), `EncodeService`(심볼 스트림·`reencode` 전체 재생성),
    `FileChunks`(TreeMap → `LtDecoder` 누산기), `DecodeService`/`QrDecodeSupport`.
    tests(코덱 손실 시뮬 + 손실 라운드트립)·docs 동시 갱신, round-trip·손실 복원 검증 완료.
  - [x] 복구 여유분 `--repair-overhead` CLI 옵션 + GUI 스피너 노출 — 완료(기본 0.5, EncodeService까지 배선).
  - 후속(미착수): Robust Soliton 등 효율 튜닝, 페이싱 캘리브레이션 모드.

### 2. slide ↔ capture 페이싱 (완료, 2026-06-20)

단방향 채널이라 back-channel이 없어 **송신측(slide) 자동 속도 제어는 원리적으로 불가능**.
§1 fountain으로 "특정 프레임 재수신 대기"가 이미 해소됐고, 남은 페이싱은 측정·권장+문서로 처리.

- [x] **capture가 권장 slide 속도를 측정해 출력** — 완료
  - 이번 실행의 고유 심볼 수/경과시간으로 "고유 N QR/s"를 구하고 다음 패스 권장
    `page-display-ms`(= 고유 1개당 평균 간격 × 안전계수 1.3)를 상태 로그·종료 로그에 출력
    (`CaptureService.recommendedDwellMs`). 송신측 자동 제어 채널은 없으므로 가이드값.
- [x] **슬라이드 루프 동작 확인·문서화** — 완료
  - 이미 루프함(`Loop=0` 무한, `SlidePlaybackController.advanceToNext`가 끝에서 첫 장 복귀).
    fountain 복구+루프가 속도 미스매치를 흡수 → 정밀 매칭 불필요. `docs/dev/slide-capture.md`
    "slide ↔ capture 페이싱" 절에 워크플로(권장값 적용 + 루프 + 수신측에서 완료 확인 후 정지) 명시.
- [x] **완료 자동 감지 + 정지 안내 (sketch 방법 B)** — 완료(2026-07-02)
  - `capture`가 고유 payload마다 fountain 프레임을 파싱해(`CaptureCompletionTracker` +
    `LtPeelTracker`, 심볼 바이트 없이 ESI 구조만으로 peel 판정) 파일별 복원 가능 시점을
    감지하고 `[CAPTURE][DONE]` 로그·"slide 정지해도 됩니다" 배너·GUI 상태(`복원 가능 N/M`)로
    안내. 권장값 로그에 방향 힌트 추가. 자세한 배경은 `docs/dev/sketch.md`(방법 B).
  - 후속(미착수): 전송 전 속도 램프 캘리브레이션 모드(더 정확한 권장값) — ROI 보고 별도 결정.
    방법 A(광학 양방향 back-channel)는 에어갭 정책 결정 게이트에 답한 뒤에만 검토.

### 3. 4색 컬러 심볼 (흰/녹/적/흑, 2 bit/셀)

추가 처리율이 필요할 때 진행하는 net ≈ 1.4~1.6× 개선. 가장 엔지니어링 비용이 큰 항목.

- [ ] 휘도 255/150/76/0 으로 네 단계가 또렷 → 채도가 압축으로 무너져도 밝기만으로
  구분 가능(5색의 Blue↔Black 충돌 회피). 모노크롬에 가까운 견고함 유지.
  - 필수 동반 작업:
    - 프레임마다 고정 위치 **컬러 캘리브레이션 패치**(화이트밸런스/감마/조명 정규화).
    - RGB 유클리드 거리 대신 **휘도 우선 + 색상(hue) 보조 분류기**.
    - 크로마 서브샘플링(4:2:0) 대비 **셀 크기 하한** 확보.
  - §1(fountain) 적용 후에도 추가 처리율이 필요할 때 진행.
  - 영향 범위: `QrImageWriter`(컬러 렌더링) 또는 별도 carrier 모듈, `QrDecodeSupport`
    (컬러 분류·캘리브레이션), payload 비트 패킹.

### 4. 구조 리팩터링 후속 검토 (모듈 분리)  ← 마지막

`transfer-core`/`carrier-qr` 정식 모듈 분리는 payload 계약이 안정화된 뒤
(§1 fountain code 방향 확정 후) 별도 작업으로 검토한다. 그 전까지는
`apps/*`, `libs/*`의 기존 모듈 경계를 유지한다.

검토 시 유지할 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.

## 독립 작업 (순서 무관)

위 순서와 의존성이 없어 아무 때나 진행할 수 있다.

- [ ] **Windows 실기 검증** — `receiver`의 카메라 인식/캡처 동작을 Windows 실기에서 최종 확인.
  (디바이스 이름 열거·probe 타임아웃·폴더 picker 코드는 반영됨. ffmpeg 미설치 시
  디바이스 이름은 안 뜨고 brute-force로 동작 → 필요하면 번들 ffmpeg 사용으로 후속 개선.)
- [ ] **P4d. `chunkDataSize` 기본값 튜닝** — 바이트 모드 전환 후 QR 용량 한계까지 키워 QR 장수↓.
  버전/ECC 용량 검증 필요 → 별도 작업.

### 2026-07-03 정확성·성능 감사에서 보류한 후속 항목

멀티에이전트 감사(finder 14 + 적대적 검증)에서 나온 항목 중 이번에 반영하지 않은 것.
반영 전 실기/수동 검증이나 설계 판단이 더 필요하다.

- [ ] **slide 타이머·페인트 검토(실기 필요)** — 감사 지적: `scheduleBlackPhase()`가 이전
  black 타이머를 정지하지 않아 스피너 변경 시 고아 타이머가 남을 수 있음; dwell 타이머가
  이미지 표시 전에 시작돼 캐시 미스 시 실효 dwell이 깎임; 종료 blackout에서 빠져나와도
  5분 closeTimer가 살아 있음; `SlideCanvas`가 매 paint마다 BILINEAR 재스케일. Swing 실기
  확인 없이 바꾸기엔 캡처 신뢰도에 영향 가능 → 실기 검증과 함께 진행.
- [ ] **capture grab 스레드 최적화(실기 필요)** — 매 프레임 풀해상도 RGB 복사(~8MB) 후
  게이팅. 지문 계산을 먼저 하고 통과 프레임만 복사하는 구조로 바꾸면 CPU 대폭 절감이나,
  파이프라인 스레딩 구조 변경이라 카메라 실기 검증 필요. resume 스캔 병렬화도 동일 묶음.
- [ ] **pack rename 충돌 시 엔트리 드롭** — `LICENSE`(확장자 없음)→`LICENSE.txt` rename이
  원래 있던 `LICENSE.txt`와 충돌하면 뒤에 오는 쪽이 조용히 사라진다. 충돌 회피 이름 규칙과
  `target.txt` 기록을 함께 바꿔야 해서 보류.
- [ ] **QrImageDecoder 회전 변형 제거 검토** — zxing QR 디코더는 회전 불변이라 90/180/270
  재렌더 시도가 실패 경로 비용만 4배로 늘린다는 지적. 경계 프레임에서의 영향을 벤치마크로
  확인한 뒤 제거.
