# air-bridge TODO

기준 시점: 2026-07-04

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

핵심 작업은 아래 순서로 진행한다.

```
① 4색 컬러 심볼  →  ② 구조 리팩터링(모듈 분리)
```

### 1. 4색 컬러 심볼 (흰/녹/적/흑, 2 bit/셀)

추가 처리율이 필요할 때 진행하는 net ≈ 1.4~1.6× 개선. 가장 엔지니어링 비용이 큰 항목.

- [ ] 휘도 255/150/76/0 으로 네 단계가 또렷 → 채도가 압축으로 무너져도 밝기만으로
  구분 가능(5색의 Blue↔Black 충돌 회피). 모노크롬에 가까운 견고함 유지.
  - 필수 동반 작업:
    - 프레임마다 고정 위치 **컬러 캘리브레이션 패치**(화이트밸런스/감마/조명 정규화).
    - RGB 유클리드 거리 대신 **휘도 우선 + 색상(hue) 보조 분류기**.
    - 크로마 서브샘플링(4:2:0) 대비 **셀 크기 하한** 확보.
  - fountain 적용 후에도 추가 처리율이 필요할 때 진행.
  - 영향 범위: `QrImageWriter`(컬러 렌더링) 또는 별도 carrier 모듈, `QrDecodeSupport`
    (컬러 분류·캘리브레이션), payload 비트 패킹.

### 2. 구조 리팩터링 후속 검토 (모듈 분리)  ← 마지막

`transfer-core`/`carrier-qr` 정식 모듈 분리는 payload 계약이 안정화된 뒤
별도 작업으로 검토한다. 그 전까지는 `apps/*`, `libs/*`의 기존 모듈 경계를 유지한다.

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

### 정확성·성능 감사 후속 항목 중 미해결 과제

- [ ] **QrImageDecoder 회전 변형 제거 검토** — zxing QR 디코더는 회전 불변이라 90/180/270
  재렌더 시도가 실패 경로 비용만 4배로 늘린다는 지적. 경계 프레임에서의 영향을 벤치마크로
  확인한 뒤 제거.
