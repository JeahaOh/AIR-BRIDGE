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
  - 후속(미착수): 복구 여유분(`--repair-overhead`) CLI/GUI 노출, Robust Soliton 등 효율 튜닝.

### 2. slide ↔ capture 페이싱·동기화

slide는 `page-display-ms`(기본 100ms, 스피너 50~10000ms) 고정 간격으로만 QR을
넘기는 **open-loop**다. 카메라 채널은 단방향이라 capture가 따라잡는 속도와
무관하게 흘려보내고, 순차 인덱스 청크라 한 프레임을 놓치면 슬라이드가 한 바퀴
돌 때까지 그 청크를 못 받는다 → 단방향 채널의 처리율·안정성에 직접 영향.

§1 fountain code가 들어오면 "특정 프레임 재수신 대기"가 사라져 이 문제의 상당
부분이 해소된다 → §1 적용 뒤 남는 부분만 다룬다. back-channel이 없으므로(air-gap)
진짜 닫힌 루프 동기화는 불가능하다. 현실적인 레버:

- [ ] **slide 재생 속도를 capture의 지속 가능한 디코드율에 맞춰 정하기/자동 조정.**
  지금은 사용자가 감으로 ms를 정함 → capture 측 실측 처리율을 기준값으로 노출하거나
  권장 `page-display-ms`를 안내.
- [ ] **슬라이드 루프 동작 정리.** 끝까지 재생 후 처음부터 반복해 놓친 프레임이 다시
  오도록 보장(이미 그렇게 동작하면 문서로 명시). 루프 횟수/종료 조건 정의.
- 영향 범위: `SlideApp`/`SlidePlaybackController`(재생 타이밍·루프),
  capture 측 처리율 측정·노출.

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
