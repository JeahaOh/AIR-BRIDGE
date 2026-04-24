# slide / capture

`sender slide`와 `receiver capture`의 내부 동작을 개발 기준으로 정리한 문서입니다.

## 범위

- `slide`: 이미지 세트를 재생하는 Swing 앱
- `capture`: UVC 카메라 입력에서 QR payload를 안정적으로 수집하는 파이프라인

## 관련 구현

- `libs/slide/src/main/java/airbridge/slide/SlideApp.java`
- `libs/slide/src/main/java/airbridge/slide/SlideImageCatalog.java`
- `libs/slide/src/main/java/airbridge/slide/SlideDirectoryChooser.java`
- `libs/slide/src/main/java/airbridge/slide/SlideDefaults.java`
- `libs/capture/src/main/java/airbridge/receiver/capture/CaptureService.java`
- `libs/capture/src/main/java/airbridge/receiver/capture/CaptureOptions.java`
- `libs/capture/src/main/java/airbridge/receiver/capture/CaptureSupport.java`
- `libs/capture/src/main/java/airbridge/receiver/capture/CaptureDefaults.java`

## slide 진입점

`sender`는 `slide`를 별도 Picocli 서브커맨드로 처리하지 않고, `main(...)` 초반에 `"slide"` 토큰을 감지하면 `SlideApp.launch(...)`로 바로 넘긴다.

특징:

- `java -jar sender.jar slide`
- `--help` 또는 `-h`만 간단히 처리
- 나머지는 Swing 앱 실행

즉 `slide`는 CLI 옵션 파싱보다 GUI 런처 성격이 강합니다.

## slide 입력 수집

입력 디렉터리 선택은 `SlideDirectoryChooser`가 담당합니다.

- macOS: `FileDialog` 디렉터리 선택
- 그 외: `JFileChooser(DIRECTORIES_ONLY)`

이미지 목록은 `SlideImageCatalog.load(...)`가 만듭니다.

규칙:

- 재귀적으로 파일 수집
- 지원 확장자: `.png`, `.jpg`, `.jpeg`
- 정렬 우선순위:
  - 파일명에 `session-start` 포함: 먼저
  - 일반 파일: 중간
  - 파일명에 `session-end` 포함: 마지막
- 같은 우선순위에서는 입력 루트 기준 상대경로 문자열 정렬

동시에 우측 패널용 트리 모델과 `Path -> TreeNode` 인덱스도 생성합니다.

## slide 주요 상태

핵심 상태 변수:

- `imageFiles`: 재생 대상 파일 목록
- `imageCache`: LRU 캐시
- `loadingImages`: 현재 로딩 중인 이미지
- `currentIndex`: 현재 선택/재생 인덱스
- `completedLoops`: 완료한 loop 수
- `playing`: 재생 중 여부
- `showingBlackFrame`: 현재 검은 화면 단계 여부
- `postFinishBlackout`: 종료 전 블랙아웃 상태 여부
- `controlsVisible`: 상단/우측 UI 표시 여부

캐시 관련 기본값:

- max cache: `200`
- initial preload: `30`
- prefetch ahead: `20`

## slide 로딩 전략

`loadImagesFromInput()` 호출 시 아래를 초기화합니다.

- 재생 중단
- generation 증가
- 파일 목록, 캐시, 로딩 상태, 트리 인덱스 초기화
- 캔버스 초기화

이미지가 있으면:

1. 앞에서부터 `PRELOAD_COUNT`만큼 선로딩
2. 현재 인덱스 이미지를 표시
3. 현재 인덱스 기준으로 뒤쪽 `PREFETCH_COUNT`를 비동기 예약

이미지 로딩은 `imageLoadExecutor` 고정 스레드풀에서 수행합니다.

설계 의도:

- 첫 장은 빠르게 표시
- 뒤쪽 이미지는 재생 중 미리 캐시
- 디렉터리 다시 읽기나 새 입력 로딩과 충돌하지 않도록 generation 번호로 stale load를 폐기

## slide 재생 상태 전이

기본 흐름:

1. `Play`
2. 현재 이미지 표시
3. `pageDisplayTimer`
4. 필요 시 black frame 표시
5. `blackTimer`
6. 다음 이미지로 이동
7. loop 종료 시 첫 장으로 복귀 또는 post-finish blackout 진입

세부 규칙:

- `Black(ms) > 0`이고 이미지가 2장 이상이면 페이지 사이에 검은 화면 삽입
- `Loop = 0`이면 무한 반복처럼 동작
- loop 한도를 모두 채우면 마지막 인덱스 기준으로 `enterPostFinishBlackout()` 호출

`enterPostFinishBlackout()` 동작:

- 재생 중단
- 화면을 검게 전환
- 상단/우측 컨트롤 숨김
- 5분 뒤 자동 종료 타이머 시작

## slide 단축키와 UI 상호작용

핵심 단축키:

- `Space`: 재생/일시정지
- `Left` / `Right`: 이전/다음
- `Page Up` / `Page Down`: 100장 이동
- `F`: 전체화면 토글
- `T`: 패널 토글
- `Q`: 종료

트리에서 특정 이미지를 선택하면 `currentIndex`를 바꾸고 즉시 표시한다. 재생 중이면 다음 타이밍 스케줄도 다시 잡는다.

spinner 값 변경 시:

- 재생 중이 아니면 상태 바만 갱신
- black frame 중이면 black timer 재시작
- 일반 재생 중이면 display timer 재시작

## slide 운영 성격

이 앱은 단순 미리보기보다 송출 안정성 쪽으로 기울어 있습니다.

관련 구현:

- 시작 직후 전체화면 진입
- always-on-top 기본 활성
- window deactivate/iconify 시 foreground recovery 시도
- 60초마다 mouse jiggle

즉 다른 앱과 공존하는 일반 데스크톱 UX보다, 화면 점유와 전면 유지 쪽을 우선한다.

## slide 테스트로 보는 보장 범위

현재 테스트에서 직접 보장하는 항목:

- 숫자 spinner editor가 invalid input을 막는지
- 이미지 카탈로그가 정렬 규칙을 지키는지
- 트리 인덱스가 각 이미지 파일에 대해 만들어지는지

GUI 재생 전체는 통합 테스트보다 구현 검토와 수동 확인 비중이 높다.

## capture 진입점

`receiver capture`는 `Receiver.CaptureCommand`에서 `CaptureOptions`를 만들고 `CaptureService.run()`을 호출합니다.

옵션 record가 생성될 때 최소값 보정이 들어갑니다.

예:

- `width >= 1`
- `height >= 1`
- `fps >= 0.1`
- `decodeWorkers >= 1`
- `sameSignalSeconds >= 1`

## capture 장치 탐색

장치 목록 조회는 `CaptureSupport.listDevices()`가 담당합니다.

동작:

1. macOS면 `ffmpeg -f avfoundation -list_devices true -i ""` 출력 파싱 시도
2. 인덱스와 이름을 얻으면 각 장치를 실제로 열어본다
3. 이름 파싱 실패 시 `0..9`를 순회하며 오픈 가능한 장치만 노출

실제 사용 가능 여부는 `OpenCVFrameGrabber.start()` + `grab()` 성공 여부로 확인합니다.

## capture 파이프라인 개요

`CaptureService`는 단일 루프가 아니라 4단계 파이프라인으로 동작합니다.

1. frame grab
2. fingerprint analyze
3. QR decode
4. dedupe + save

중간 큐와 상한:

- `rawFrameQueue`: 캡처 -> 분석
- `saveQueue`: 디코드 -> 저장
- `decodePermits`: decode 동시 처리 상한
- `savePermits`: save 동시 처리 상한

이 구조는 느린 decode/save 때문에 전체 캡처가 메모리로 무한 적재되는 것을 막기 위한 것이다.

## capture 단계 1: grab loop

`run()` 내부 메인 루프가 담당합니다.

역할:

- `OpenCVFrameGrabber` 시작
- 프레임 grab
- `BufferedImage` 변환
- preview 콜백 전달
- `rawFrameQueue`에 `FramePacket` 적재
- 상태 로그 주기 출력

특징:

- preview는 `PREVIEW_FRAME_INTERVAL_MS`보다 자주 보내지 않음
- 큐가 가득 차면 `offer(..., 100ms)` 재시도로 자연스럽게 backpressure 형성
- `durationSeconds` 조건이 차면 종료 요청

## capture 단계 2: analyze loop

별도 analyze 스레드가 `rawFrameQueue`에서 프레임을 받아 fingerprint 계산 결과를 순서대로 소비합니다.

핵심 상태:

- `activeFingerprint`
- `activeSinceMillis`
- `pendingFingerprint`
- `pendingPacket`
- `pendingCount`

의사결정 규칙:

- 평균 밝기가 매우 낮으면 black frame으로 간주하고 skip
- 현재 활성 화면과 충분히 비슷하면 같은 화면으로 판단
- 같은 화면이 `sameSignalSeconds` 이상 유지되면 `same-signal` 종료
- 새 화면이 한 번만 보인 경우는 pending
- 같은 pending 화면이 두 번 연속 관찰되면 decode 대상으로 확정

의미:

- 검은 프레임이나 순간적인 노이즈를 줄임
- 화면이 안정적으로 바뀐 경우만 decode에 올림

## capture 단계 3: decode worker pool

decode는 `decodeExecutor` 고정 풀에서 수행합니다.

특징:

- worker 수는 `options.decodeWorkers()`
- `decodePermits`로 pending decode 개수 상한 제어
- 성공 시 `SavePacket(frameId, capturedAtMillis, image, payload)`를 `saveQueue`로 전달
- 실패 시 `decodeFailures` 증가

decode 구현 자체는 `CaptureQrDecodeSupport.decodeQrPayloadWithRetries(...)`에 위임됩니다.

## capture 단계 4: dedupe + save

`saveLoop()`는 `saveQueue`를 소비하면서 payload 중복 제거와 저장 작업 제출을 담당합니다.

규칙:

- `seenPayloads.add(payload)`가 실패하면 이미 본 payload이므로 저장 안 함
- 저장할 때만 `savedImageCounter` 증가
- 파일명은 `frame_000001.png` 형태
- `maxPayloads > 0`이고 저장 건수가 상한에 도달하면 종료 요청

실제 저장은 `saveExecutor` 병렬 풀에서 `writeSavedImage(...)`가 수행됩니다.

PNG 저장 시:

- 가능하면 `ImageWriter` 압축 파라미터를 직접 설정
- 안 되면 `ImageIO.write(...)` fallback

## capture resume 동작

`--resume`이 켜져 있으면 시작 시 `captured-images`를 스캔합니다.

복구 내용:

- 기존 `frame_*.png` 파일 번호 중 최댓값을 찾아 `savedImageCounter` 복원
- 각 PNG를 다시 decode해 `seenPayloads`를 복원

효과:

- 이미 저장한 payload를 다시 저장하지 않음
- 이어서 저장할 파일 번호가 겹치지 않음

읽을 수 없는 이미지나 decode 실패 이미지는 경고 로그만 남기고 건너뛴다.

## capture 종료 조건

주요 종료 이유:

- `duration-reached`
- `max-payloads-reached`
- `same-signal`
- `requested`
- `interrupted`
- `analyze-error`
- `save-error`

종료 후에는:

- 각 executor와 스레드 정리
- `capture-manifest.json` 작성
- `CaptureSummary` 생성

## capture 산출물

기본 출력 구조:

- `captured-images/frame_000001.png`
- `captured-images/frame_000002.png`
- `capture-manifest.json`

manifest에는 아래 정보가 들어간다.

- 장치 인덱스, 해상도, fps
- resume 여부
- 시작/종료 시각
- stop reason
- 총 frames / analyzed / decoded / unique payloads / saved images
- black frame skip 수
- decode failure 수
- queue high-water mark
- fingerprint / decode / save 누적 시간

즉 단순 결과 목록이 아니라, 병목과 품질을 보는 운영 메트릭 파일 성격도 가집니다.

## 테스트로 보는 보장 범위

`CaptureServiceInternalTest` 기준:

- resume scan이 기존 payload와 최고 저장 번호를 복원하는지
- manifest JSON이 현재 메트릭과 문자열 escaping을 반영하는지

`slide` 쪽과 마찬가지로 실제 카메라/GUI 장치 상호작용은 수동 검증 비중이 높다.

## 개발 시 주의점

- `slide`는 송출 UX를 우선하므로 일반 GUI 앱처럼 동작하지 않는다.
- `capture`는 프레임마다 decode하지 않고 안정화된 화면만 decode한다.
- `capture` 중복 제거 기준은 payload 문자열 전체다.
- `capture`의 성능 병목은 보통 decode worker, save queue, fingerprint 비용 중 하나에 생긴다.
- `slide`와 `capture` 타이밍을 함께 조정할 때는 page/black 시간만 볼 게 아니라, `capture-manifest.json`의 decoded/saved 지표도 같이 봐야 한다.
