# Capture Process Description

이 문서는 현재 코드 기준 `receiver capture` 가 내부적으로 어떻게 동작하는지 설명한다.

기준 소스:
- [`Receiver.java`](../apps/receiver/src/main/java/airbridge/receiver/Receiver.java)
- [`CaptureService.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java)
- [`CaptureSupport.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureSupport.java)
- [`CaptureQrDecodeSupport.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureQrDecodeSupport.java)

## 1. 현재 활성 실행 경로

현재 실제로 쓰이는 capture 경로는 `capture` 모듈 기반이다.

- `java -jar build/libs/receiver-<version>.jar capture --out /path/out`
  - `Receiver.CaptureCommand.call()` 이 CLI 입력을 받는다.
  - 실제 파이프라인 실행은 `CaptureService.run()` 이 담당한다.

개발 중 직접 실행이 필요하면 `./gradlew :receiver:run --args="capture --out /path/out"` 도 사용할 수 있다.

주의:
- 현재 활성 경로는 `CaptureOptions`, `CaptureService`, `CaptureSupport` 를 쓰는 `capture` 기준이다.

## 2. 모듈 역할 분리

### `receiver`

역할:
- CLI 인자 파싱
- `capture` 모드 분기
- CLI 모드에서 `CaptureService` 실행

### `capture`

역할:
- 실제 카메라 캡처 실행
- 프레임 분석
- QR 디코드
- payload 기준 중복 제거
- PNG 저장
- manifest 생성

핵심 클래스:
- `CaptureOptions`: 실행 옵션 record
- `CaptureSupport`: 장치 목록 조회 및 사용 가능성 확인
- `CaptureService`: 실제 파이프라인 실행
- `CaptureQrDecodeSupport`: 이미지에서 QR payload 읽기
- `CaptureListener`: 로그/프리뷰/상태 콜백
- `CaptureStatus`, `CaptureSummary`: 상태/결과 전달 DTO

## 3. 전체 처리 흐름

전체 흐름은 아래 순서다.

1. 실행 모드 선택
2. 옵션 구성
3. 출력 디렉터리 준비
4. 카메라 열기
5. 프레임 수집
6. 화면 변화 분석
7. QR 디코드
8. payload 중복 제거
9. PNG 저장
10. 종료 조건 감지
11. manifest 작성
12. summary 반환

## 4. CLI 경로 상세

### 4.1 `capture`

`Receiver.CaptureCommand.call()` 은 CLI 옵션을 `CaptureOptions` 로 묶고 `CaptureService.run()` 을 호출한다.

옵션 매핑:
- `--device` -> `deviceIndex`
- `--width` -> `width`
- `--height` -> `height`
- `--fps` -> `fps`
- `--duration-seconds` -> `durationSeconds`
- `--max-payloads` -> `maxPayloads`
- `--decode-workers` -> `decodeWorkers`
- `--status-interval-ms` -> `statusIntervalMs`
- `--same-signal-seconds` -> `sameSignalSeconds`
- `--resume` -> `resume`

기본값:
- device: `0`
- width: `1920`
- height: `1080`
- fps: `15.0`
- durationSeconds: `0`
- maxPayloads: `0`
- decodeWorkers: `4`
- statusIntervalMs: `10000`
- sameSignalSeconds: `180`

정규화:
- `CaptureOptions` 생성 시 최소값 보정이 걸린다.
- 예: `decodeWorkers >= 1`, `sameSignalSeconds >= 1`, `fps >= 0.1`
- [`CaptureOptions.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureOptions.java)
## 5. 장치 탐색 방식

장치 탐색은 `CaptureSupport.listDevices()` 가 담당한다.

동작 순서:
1. macOS 인 경우 `ffmpeg -f avfoundation -list_devices true -i ""` 출력 파싱 시도
2. 이름을 얻으면 해당 인덱스를 `canOpenDevice(index)` 로 실제 열어본다
3. 이름 목록을 못 얻으면 `0..9` 를 순회하며 열리는 디바이스만 수집한다

`canOpenDevice()` 는 내부적으로:
- `OpenCVFrameGrabber(index)`
- `640x480`, `1fps` 설정
- `start()`
- `grab()`
- 프레임이 `null` 이 아니면 사용 가능으로 판단

즉 장치 탐색은 “이름 조회”와 “실제 오픈 가능 여부 확인” 두 단계를 가진다.

## 6. 캡처 파이프라인 구조

`CaptureService` 는 단일 루프가 아니라 단계가 나뉜 파이프라인으로 동작한다.

### 단계 1. Capture Loop

담당:
- 메인 `run()` 메서드

역할:
- 카메라 오픈
- 프레임 grab
- 프리뷰 전달
- 분석 큐에 프레임 투입
- 상태 로그 주기 출력

구성:
- `OpenCVFrameGrabber`
- `Java2DFrameConverter`
- `rawFrameQueue`

동작:
1. `captured-images` 디렉터리 생성
2. 시작 로그 전송
3. 마우스 jiggle 스케줄러 시작
4. 분석 스레드와 저장 스레드 시작
5. `OpenCVFrameGrabber` 시작
6. 프레임을 반복 획득
7. `BufferedImage` 로 변환
8. 프리뷰용 콜백 호출
9. `FramePacket(frameId, capturedAtMillis, image)` 생성
10. `rawFrameQueue` 에 enqueue

프리뷰는 매 프레임 그대로 전달하지 않는다.

- 약 `66ms` 간격 이하로는 건너뛴다.
- 큰 원본 대신 축소 preview 이미지를 전달한다.

큐가 가득 차면:
- `offer(..., 100ms)` 로 재시도한다
- 즉 프레임 유입이 너무 빠르면 캡처 루프가 자연스럽게 backpressure를 받는다

### 단계 2. Analyze Loop

담당:
- `qe-capture-analyze` 스레드
- [`CaptureService.java`](../libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java)

역할:
- black frame 제거
- 같은 화면 지속 여부 판정
- 화면 안정화 확인
- 디코드 대상 프레임만 선정

핵심 상태:
- `activeFingerprint`
- `activeSinceMillis`
- `pendingFingerprint`
- `pendingPacket`
- `pendingCount`

동시성 구조:

- analyze 상태 머신 자체는 단일 스레드다.
- 다만 fingerprint 계산은 별도 worker pool 에서 병렬로 수행된다.
- 결과 적용은 frame 순서를 유지한 채 다시 단일 스레드에서 처리된다.

처리 순서:
1. `rawFrameQueue` 에서 프레임 수신
2. `analyzedFrames` 증가
3. 프레임 fingerprint 계산
4. 평균 밝기(`meanLuma`)가 낮으면 black frame 으로 판단하고 skip
5. 현재 활성 화면과 유사하면:
   - 같은 화면이 너무 오래 지속되었는지 검사
   - `sameSignalSeconds` 초 이상이면 `requestStop("same-signal")`
   - 아니면 그냥 skip
6. 새 화면으로 보이면 `pendingFingerprint` 에 저장
7. 같은 pending 화면이 2번 연속 관찰되면 그 프레임을 decode 대상으로 확정

이 로직의 의미:
- 한 번만 스쳐간 화면은 무시할 수 있다
- 연속 두 프레임에서 비슷하게 보인 화면만 decode 대상으로 올린다
- black frame 같은 의도적 blank 구간은 QR로 착각하지 않는다

### 단계 3. Decode Worker Pool

담당:
- `decodeExecutor`
- 고정 스레드 수 = `decodeWorkers`

역할:
- 후보 프레임에서 QR payload 복원

제어 장치:
- `decodePermits` 세마포어
- 최대 pending decode 수 = `32`

처리 순서:
1. `submitDecode()` 에서 `decodePermits.acquire()`
2. decode task를 `decodeExecutor` 에 제출
3. 내부에서 `CaptureQrDecodeSupport.decodeQrPayloadWithRetries(image)` 실행
4. 성공 시 `decodedFrames` 증가
5. `SavePacket` 을 `saveQueue` 로 전달
6. 실패 시 `decodeFailures` 증가
7. 마지막에 permit 반환

즉 decode는 worker pool 병렬 처리지만, 무제한으로 쌓이지 않게 세마포어로 상한을 건다.

### 단계 4. Save Queue + Save Worker

담당:
- `qe-capture-save` 스레드

역할:
- payload 중복 제거
- 저장 작업 제출
- 저장 건수 기준 종료 판단

처리 순서:
1. `saveQueue` 에서 `SavePacket` 수신
2. `seenPayloads.add(payload)` 시도
3. 이미 본 payload면 저장하지 않고 skip
4. 새 payload면 `frame_000001.png` 형식으로 저장 예약
5. `savedImageCounter` 증가
6. UI/로그 콜백 전송
7. `maxPayloads > 0` 이고 한도 도달 시 `requestStop("max-payloads-reached")`

실제 PNG 쓰기:

- `qe-capture-save-worker` 풀에서 수행된다.
- 즉 save loop 가 직접 `ImageIO.write()` 에 오래 묶이지 않도록 분리되어 있다.

중요:
- 저장 파일명은 payload 기반 이름이 아니다
- 저장 순서 기반 일련번호 파일명이다
- 중복 제거 기준은 “이미지 유사도”가 아니라 “디코드된 payload 문자열”이다

## 8. QR 디코드 전략

실제 QR 디코드는 `CaptureQrDecodeSupport` 가 수행한다.

전략:
- 단일 이미지 한 번 읽고 끝내지 않는다
- 여러 후보 이미지를 만들어 순차 시도한다

후보 생성:
- 원본
- 1.5x / 2.0x 스케일
- 90 / 180 / 270 회전
- grayscale
- black and white
- 중앙 crop
- grid crop

주의:

- 현재 `capture` 쪽 디코드 후보 생성은 예전보다 가볍게 조정된 상태다.
- 목적은 `decode` 절대 인식률보다 `capture` 중 실시간 처리량과 메모리 피크 억제다.

바이너리화:
- `HybridBinarizer`
- `GlobalHistogramBinarizer`

힌트:
- 일반 모드
- `TRY_HARDER=true`

즉 capture 디코드는 “보이는 프레임을 바로 한 번 읽기”가 아니라, 꽤 공격적으로 후보를 늘려가며 QR payload를 복원하는 방식이다.

## 9. 종료 조건

서비스는 아래 조건 중 하나를 만나면 `requestStop(reason)` 으로 종료를 시작한다.

- 사용자가 Stop 버튼 클릭
- GUI 창 닫기
- `durationSeconds` 도달
- `maxPayloads` 도달
- 같은 화면이 `sameSignalSeconds` 이상 지속
- 저장 중 예외 발생
- 스레드 인터럽트 발생

대표 stop reason:
- `requested`
- `duration-reached`
- `max-payloads-reached`
- `same-signal`
- `save-error`
- `interrupted`

## 10. 산출물

출력 루트:
- 사용자가 지정한 `outputDir`

하위 출력:
- `captured-images/`
- `capture-manifest.json`

이미지:
- `captured-images/frame_000001.png`
- `captured-images/frame_000002.png`
- ...

manifest 필드:
- `schemaVersion`
- `command`
- `outputDir`
- `capturedImagesDir`
- `deviceIndex`
- `width`
- `height`
- `fps`
- `startedAt`
- `finishedAt`
- `stopReason`
- `totalFrames`
- `analyzedFrames`
- `decodedFrames`
- `uniquePayloads`
- `savedImages`
- `blackFramesSkipped`
- `decodeFailures`

즉 manifest 는 “어떤 조건으로 캡처했고, 몇 프레임 중 몇 개가 분석/디코드/저장되었는지”를 남기는 실행 결과 요약이다.

## 11. 스레드 구성

현재 기준 스레드는 대략 이렇게 나뉜다.

- 메인 실행 스레드
  - `CaptureService.run()` 의 capture loop 담당
- 분석 스레드
  - `qe-capture-analyze`
- 저장 스레드
  - `qe-capture-save`
- decode worker pool
  - 개수 = `decodeWorkers`
- mouse jiggle 스케줄러
  - 1개 daemon thread

즉 구조적으로는:
- 프레임 수집
- 프레임 판정
- QR 디코드
- 파일 저장
를 분리해 병렬 처리하고 있다.

## 12. Backpressure와 안정성 장치

현재 구현에는 몇 가지 안전 장치가 있다.

### 큐 용량 제한

- `rawFrameQueue` 용량: `64`
- `saveQueue` 용량: `128`

의미:
- 처리 속도보다 카메라 입력이 빠를 때 메모리가 무한정 늘어나지 않는다

### decode 동시성 제한

- `MAX_PENDING_DECODE = 32`
- 세마포어로 decode backlog를 제한한다

의미:
- QR 읽기가 느려져도 decode 작업이 무한 적체되지 않는다

### black frame 제거

- 평균 밝기 임계값 이하 프레임은 skip

의미:
- slide의 black page 같은 신호를 decode 대상으로 보내지 않는다

### same signal 감지

- perceptual fingerprint distance 기반
- 동일 화면이 너무 오래 지속되면 정지

의미:
- 신호가 멈췄는데 계속 같은 화면만 읽는 상황을 자동 종료할 수 있다

## 13. 현재 한계

현재 구현 기준으로 알아둘 점:

- decode 결과를 바로 소스 파일로 복원하지는 않는다
  - capture는 “유니크 QR 프레임 수집”까지 담당
  - 실제 복원은 별도 `receiver decode` 단계가 담당
- 장치 이름 조회는 macOS에서 `ffmpeg avfoundation` 에 의존한다
  - 다른 OS에서는 이름 없이 인덱스 탐색 fallback 이 동작한다
- mouse jiggle 은 best-effort 보조 장치다
  - OS 정책에 따라 효과가 없을 수 있다

## 14. 한 줄 요약

현재 capture는 `receiver` 가 진입점을 제공하고, 실제 동작은 `capture` 모듈의 `CaptureService` 가 담당한다.

내부적으로는:
- 카메라 프레임 수집
- 화면 fingerprint 분석
- 후보 프레임만 QR decode
- payload 기준 중복 제거
- PNG 저장
- manifest 기록
의 파이프라인으로 동작한다.
