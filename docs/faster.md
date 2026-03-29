# Faster Tuning Notes

이 문서는 `slide` 와 `capture` 속도 개선 항목을 현재 코드 상태 기준으로 정리한 체크리스트다.

상태 표기:
- `반영됨`: 코드에 이미 반영됨
- `부분 반영`: 방향은 들어갔지만 구조적으로 아직 덜 끝남
- `미반영`: 아직 안 한 항목

기준 소스:
- [`SlideApp.java`](../libs/slide/src/main/java/airbridge/slide/SlideApp.java)
- [`Receiver.java`](../apps/receiver/src/main/java/airbridge/receiver/Receiver.java)
- [`CaptureService.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java)

주의:
- `capture` 성능 상태는 현재 [`CaptureService.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java) 기준으로 읽는 것이 맞다.
- 이 문서의 `capture` 체크 상태는 `receiver` 엔트리포인트가 아니라 `capture` 모듈 기준으로 읽는 것이 맞다.

## 결론

속도를 올리는 방향은 여전히 아래 순서가 맞다.

1. `slide`: 이미지 로딩을 UI 스레드 밖으로 분리
2. `capture`: decode 처리량과 큐 용량 조정
3. 그 다음에 `page ms`, `fps` 를 더 공격적으로 조정

현재 상태 요약:
- `slide`: 핵심 구조 개선 반영
- `capture`: 핵심 튜닝 반영, 구조 최적화는 일부만 반영

## 1. Slide

### 현재 값

현재 코드 기준:
- `DEFAULT_PAGE_DISPLAY_MS = 400`
- `DEFAULT_BLACK_FRAME_MS = 100`
- `MAX_CACHE_SIZE = 200`
- `PRELOAD_COUNT = 30`
- `PREFETCH_COUNT = 20`

위치:
- [`SlideApp.java`](../libs/slide/src/main/java/airbridge/slide/SlideApp.java)

### 항목별 상태

- `blackFrameMs` 줄이기 또는 `0` 사용
  - `반영됨`
  - 코드 변경이 아니라 운영 설정으로 바로 적용 가능

- `MAX_CACHE_SIZE` 확대
  - `반영됨`
  - `100 -> 200`

- `PRELOAD_COUNT` 확대
  - `반영됨`
  - `20 -> 30`

- `PREFETCH_COUNT` 확대
  - `반영됨`
  - `10 -> 20`

- 초기 preload / 주변 prefetch를 백그라운드 로더로 분리
  - `반영됨`
  - `slide-image-loader` executor 추가
  - preload/prefetch는 비동기 로드로 바뀜

- 현재 프레임 표시를 완전 비동기 구조로 전환
  - `반영됨`
  - cache miss 시 현재 프레임도 비동기 로드를 예약
  - 준비되면 EDT 에서 현재 프레임만 교체

- `ImageIO.read()`를 EDT 경로에서 완전히 제거
  - `반영됨`
  - preload/prefetch/current frame 모두 비동기 로더 경로 사용

- placeholder/직전 이미지 유지 후 비동기 repaint
  - `반영됨`
  - cache miss 시 현재 캔버스 이미지를 유지하고
  - 준비되면 repaint

### 의미

현재 `slide`는:
- 다음 이미지 준비 확률을 높이는 수준을 넘어서
- 현재 프레임 miss 도 UI 블로킹 없이 넘길 수 있는 상태

### 현재 권장 운영값

안정형:
- `page=250ms`
- `black=0~30ms`

균형형:
- `page=200ms`
- `black=0ms`

고속형:
- `page=150~180ms`
- `black=0ms`
- 이 구간은 실제 환경에서 검증 후 사용

## 2. Capture

### 현재 값

현재 코드 기준:
- `RAW_QUEUE_CAPACITY = 64`
- `SAVE_QUEUE_CAPACITY = 128`
- `MAX_PENDING_DECODE = 32`

위치:
- [`CaptureService.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java)

기본값:
- CLI `fps = 15`
- CLI `decodeWorkers = 4`
- CLI `statusIntervalMs = 10000`

위치:
- [`Receiver.java`](../apps/receiver/src/main/java/airbridge/receiver/Receiver.java)
- [`Receiver.java`](../apps/receiver/src/main/java/airbridge/receiver/Receiver.java)

### 항목별 상태

- `decodeWorkers` 증가
  - `반영됨`
  - 기본 `8 -> 12` 로 올렸다가, OOM 리스크 때문에 현재 기본은 `4` 로 다시 낮춤

- `MAX_PENDING_DECODE` 증가
  - `반영됨`
  - `16 -> 32`

- `RAW_QUEUE_CAPACITY` 증가
  - `반영됨`
  - `32 -> 64`

- `SAVE_QUEUE_CAPACITY` 증가
  - `반영됨`
  - `64 -> 128`

- 프리뷰 렌더링 비용 절감
  - `반영됨`
  - 매 프레임 원본 크기 프리뷰 대신
  - 스로틀 + 축소 preview 이미지 전달

- capture FPS 기본값 상향
  - `반영됨`
  - `10 -> 15`

- analyze loop 자체를 4개/8개로 병렬화
  - `미반영`
  - 현재도 단일 스레드 유지
  - 적용 보류
  - 이유: `activeFingerprint`, `pendingFingerprint`, `same-signal` 판정이 프레임 순서에 의존하므로 무작정 병렬화하면 오작동 위험이 큼

- fingerprint 계산만 병렬화
  - `반영됨`
  - 계산은 worker pool 로 분산
  - 결과 적용과 상태 머신은 frame 순서대로 단일 스레드 유지

- 저장 경로 자체 최적화
  - `반영됨`
  - 중복 판정 뒤 실제 PNG 쓰기는 별도 save worker 로 분산
  - 저장 worker 적체도 세마포어로 제한한다
  - PNG 저장은 더 빠른 writer 경로를 사용한다
  - 포맷은 여전히 PNG 이다

- 실행 계측 추가
  - `반영됨`
  - manifest 에 queue/backpressure/단계별 시간 정보 기록

### 의미

현재 `capture`는:
- 안전한 1차 처리량 튜닝은 들어간 상태
- decode/queue/preview 비용 쪽은 개선됨
- fingerprint 계산 병렬화도 들어감
- 저장도 단일 루프에서 직접 쓰지 않고 worker 로 분산
- 실행 후 manifest 로 병목 위치를 읽을 수 있음
- 하지만 analyze 상태 머신 자체는 단일 스레드 유지
- 저장 포맷 자체 최적화나 상태 머신 구조 변경까지 끝난 것은 아님

## 3. Slide + Capture 같이 쓸 때

### 권장 조합

안정형:
- `slide: 250ms/page`
- `slide: black 0~30ms`
- `capture: 10~12fps`

균형형:
- `slide: 200ms/page`
- `slide: black 0ms`
- `capture: 12~15fps`

고속형:
- `slide: 140~180ms/page`
- `slide: black 0~10ms`
- `capture: 15fps`
- `decodeWorkers: 4~8`

현재 코드 기준 실사용 추천:
- `slide: page 140ms / black 10ms`
- `capture: fps 15 / decodeWorkers 4`

## 4. 다음 우선순위

### Slide

- 현재 프레임도 완전 비동기 전환
  - `반영됨`
- placeholder/직전 프레임 유지 후 준비되면 repaint
  - `반영됨`

### Capture

- fingerprint 계산만 병렬화
  - `반영됨`
- 상태 머신은 단일 스레드 유지한 채 analyze 전처리 분산
  - `부분 반영`
- 저장 경로 최적화
  - `반영됨`
- manifest 계측 추가
  - `반영됨`

## 5. Capture Manifest 계측

현재 `capture-manifest.json` 에는 아래 필드도 기록된다.

- `rawQueueOfferRetries`
- `rawQueueHighWaterMark`
- `saveQueueHighWaterMark`
- `fingerprintMillis`
- `decodeMillis`
- `saveMillis`

해석 기준:
- `rawQueueOfferRetries` 가 크다
  - 입력이 후단 처리보다 빠르다
- `rawQueueHighWaterMark` 가 높다
  - raw queue 가 자주 꽉 찬다
- `saveQueueHighWaterMark` 가 높다
  - decode 성공 후 저장 쪽 적체가 있다
- `fingerprintMillis` 가 크다
  - analyze 전처리 비용이 크다
- `decodeMillis` 가 가장 크다
  - QR 디코드가 주 병목이다
- `saveMillis` 가 가장 크다
  - PNG 저장이 병목이다

## 6. 한 줄 정리

지금 상태는:
- `slide`: 핵심 구조 개선 완료
- `capture`: 핵심 튜닝 반영, 일부 고급 최적화는 미완료

즉 문서 기준으로 `slide` 는 핵심 병목 개선이 들어갔고, `capture` 는 1차 튜닝에 더해 fingerprint 계산 병렬화, 저장 분산, 실행 계측까지 들어갔지만 아직 완전 종료 상태는 아니다.

## 7. 적용 보류

- `capture` analyze loop 4개/8개 병렬화
  - 현재는 의도적으로 보류한다.
  - 상태 머신이 프레임 순서에 의존하므로 안정성을 해칠 가능성이 크다.
  - 이후에는 실측 계측값 기준으로 `decode`, `save`, `fingerprint` 병목을 먼저 줄이고, 정말 필요할 때만 구조를 다시 설계한다.
