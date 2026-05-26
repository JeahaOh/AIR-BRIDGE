# GUI / CLI 병행 지원 사전 점검과 개발 계획

기준 시점: 2026-04-24

## 목적

- `air-bridge`를 CLI와 GUI 두 방식으로 모두 사용할 수 있게 한다.
- 단, CLI가 GUI를 감싸는 구조로 가지 않고 같은 코어 서비스를 두 어댑터가 재사용하도록 정리한다.
- 기존 자동화와 배포 흐름은 유지한다.

## 현재 점검 결과

### 1. 현재 엔트리포인트와 모듈 구조

- 현재 배포 앱은 `sender`, `receiver` 두 개다.
- 모듈 구성은 `common`, `capture`, `packager`, `slide`, `sender`, `receiver` 기준이다.
- CLI 엔트리포인트는 `apps/sender/.../Sender.java`, `apps/receiver/.../Receiver.java`에 있다.
- `slide` GUI는 `libs/slide/.../SlideApp.java`에 있다.

### 2. 이미 분리돼 있어 재사용하기 쉬운 부분

- `encode`는 `EncodeService`가 실제 인코딩을 담당하고 CLI는 옵션 파싱과 출력까지 같이 맡고 있다.
- `decode`는 `DecodeService`가 실제 복원을 담당하고 CLI는 옵션 파싱과 출력까지 같이 맡고 있다.
- `capture`는 `CaptureService`가 이미 `CaptureListener`를 통해 로그, preview, status, finished 이벤트를 내보낸다.
- 즉 `capture`는 GUI 어댑터를 붙이기 가장 쉬운 상태다.

### 3. 현재 구조에서 바로 걸리는 문제

- `sender`는 `slide`를 Picocli 서브커맨드로도 선언했지만, 실제로는 `main(...)`에서 `"slide"` 토큰을 먼저 가로채 `SlideApp.launch(...)`로 넘긴다.
- 이 때문에 `slide`는 CLI 표면과 실제 실행 경로가 이중화돼 있다.
- `Sender.EncodeCommand`, `Receiver.DecodeCommand`, `Receiver.CaptureCommand`는 옵션 검증, 콘솔 출력, 서비스 호출, 요약 출력이 한 곳에 섞여 있다.
- `EncodeService`, `DecodeService`, `EncodeSummary`, `DecodeSummary`는 현재 앱 내부 package-private 타입이라 다른 GUI 어댑터가 직접 재사용하기 어렵다.
- `slide`는 `SlideApp` 한 클래스 안에 Swing 화면, 타이머, 상태 전이, 전체화면 정책, 포커스 복구가 강하게 묶여 있다.
- `slide` GUI 텍스트는 리소스 번들 기반이 아니라 하드코딩 문자열이 많다.
- `main(...)`이 바로 `System.exit(...)`를 호출하므로 GUI 런처나 통합 실행기에서 재사용하기 좋은 형태는 아니다.

### 4. 범위 판단

- 사용자 핵심 흐름은 `encode -> slide -> capture -> decode`다.
- `identify`, `pack`, `unpack`는 보조 유틸리티 성격이 강하다.
- 따라서 1차 목표는 핵심 흐름을 GUI/CLI 양쪽에서 안정적으로 돌리는 것이다.
- `identify`, `pack`, `unpack`, `reencode`는 우선 CLI 우선으로 두고 2차 범위로 미룬다.

## 목표 지원 매트릭스

### 1차 범위

- `sender encode`: CLI + GUI
- `sender slide`: GUI 주기능, CLI에서는 GUI launcher 유지
- `receiver capture`: CLI + GUI
- `receiver decode`: CLI + GUI

### 2차 범위

- `sender reencode`: CLI 유지, 필요 시 GUI 편입
- `receiver identify`: CLI 유지
- `receiver pack`: CLI 유지
- `sender unpack`: CLI 유지

## 결정 원칙

- GUI toolkit은 1차에서 `Swing`을 유지한다.
- CLI는 계속 `Picocli`를 사용한다.
- CLI와 GUI는 서로 호출하지 않는다.
- 둘 다 같은 request/result/service 계약을 호출한다.
- GUI는 명시적 `gui` 엔트리로 열 수 있고, 배포 jar를 명령 없이 실행하면 GUI를 연다.
- headless 환경에서 GUI를 요청하면 명확한 에러와 non-zero exit code를 반환한다.
- 기존 CLI 명령 이름과 자동화 호환성은 유지한다.

## 권장 진입점

- `java -jar sender.jar encode ...`
- `java -jar sender.jar slide`
- `java -jar sender.jar gui`
- `java -jar sender.jar`
- `java -jar receiver.jar capture ...`
- `java -jar receiver.jar decode ...`
- `java -jar receiver.jar gui`
- `java -jar receiver.jar`

정리:

- `slide`는 본질적으로 화면 송출 기능이므로 완전한 headless CLI 기능으로 바꾸지 않는다.
- 대신 `sender gui` 안에서 슬라이드 실행으로 이어지게 하고, 기존 `sender slide`는 GUI launcher 성격으로 유지한다.

## 개발 계획

### Phase 0. 계약과 범위 고정

- `docs/dev/cli-gui-architecture.md`를 이번 작업의 기본 원칙 문서로 고정한다.
- 1차 범위를 `encode`, `slide`, `capture`, `decode`로 고정한다.
- `identify`, `pack`, `unpack`, `reencode`는 이번 범위에서 제외한다고 명시한다.
- 현재 CLI help/version/대표 성공 경로를 테스트 기준으로 고정한다.

산출물:

- GUI/CLI 지원 범위 표
- CLI 회귀 기준 테스트 목록

### Phase 1. 공용 서비스 계약 정리

- `encode`, `decode`, `capture`, `slide` 각각에 대해 `Request`, `Result`, `ProgressEvent`, `Listener`, `Cancellation` 계약을 정의한다.
- 문자열 로그 콜백만 쓰는 `EncodeListener`, `DecodeListener`는 구조화된 이벤트 기반으로 교체하거나 확장한다.
- `System.out.println(...)` 중심 요약 출력은 CLI adapter 쪽으로만 남긴다.
- 공용 서비스 타입은 앱 내부 private/package-private 범위를 벗어나 재사용 가능한 위치로 올린다.

권장 방향:

- 처음부터 큰 모듈 분해를 하지 말고 현재 모듈 안에서 계약을 먼저 안정화한다.
- 계약이 굳은 뒤 `transfer-core` / `carrier-qr` 분리는 후속 작업으로 다룬다.

### Phase 2. Sender 쪽 분리

- `Sender`에서 `main(...)`의 `slide` 우회 진입을 정리하고 하나의 명확한 launcher 경로로 통합한다.
- `EncodeCommand`의 검증, 진행률 출력, 결과 출력 로직을 CLI adapter와 service 호출로 분리한다.
- `SlideApp`에서 UI 독립적인 상태 전이와 재생 제어를 `SlideController` 또는 `SlideService` 성격의 클래스로 분리한다.
- `SlideApp`은 Swing view/controller adapter로 얇게 줄인다.
- `sender gui`용 Swing 화면을 만든다.

`sender gui` 최소 화면:

- Encode 입력 폼
- 진행 로그 영역
- 결과 요약 영역
- Slide 실행 버튼 또는 Slide 탭 연결

### Phase 3. Receiver 쪽 분리

- `DecodeCommand`를 CLI adapter로 얇게 줄이고 서비스 호출만 남긴다.
- `CaptureCommand`는 현재 `CaptureService`를 유지하되 GUI launcher가 같은 `CaptureOptions`와 `CaptureListener`를 재사용하게 한다.
- `receiver gui`용 Swing 화면을 만든다.

`receiver gui` 최소 화면:

- Capture 설정 폼
- preview panel
- status / progress panel
- stop 버튼
- Decode 실행 폼과 결과 표시

### Phase 4. 공통 런처와 오류 처리

- `sender gui`, `receiver gui` 서브커맨드를 추가한다.
- GUI 런처에서 headless 환경을 먼저 감지한다.
- CLI는 exit code 매핑을 유지하고 GUI는 대화상자/상태 영역으로 오류를 표시한다.
- 예외 분류 기준을 `usage error`, `input error`, `runtime error`, `cancelled` 정도로 통일한다.

### Phase 5. 문서와 운영 정리

- `docs/user/deploy-sender.md`, `docs/user/deploy-receiver.md`에 GUI 실행법을 추가한다.
- `docs/user/encode-decode.md`, `docs/user/slide-capture.md`에 GUI 절차와 CLI 절차를 병기한다.
- `README.md`, `README.ko.md`, `README.en.md`의 Quick Start를 GUI/CLI 병행 기준으로 갱신한다.
- GUI 스모크 체크리스트를 개발 문서에 추가한다.

## 권장 구현 순서

1. `capture` GUI adapter 추가
2. `encode` / `decode` 공용 이벤트 계약 정리
3. `sender gui`, `receiver gui` 기본 창 추가
4. `slide` controller 분리
5. 보조 유틸리티 GUI 여부 재검토

현재 구현 메모:

- `receiver gui`는 `Capture`와 `Decode` 탭을 제공한다.
- `sender gui`는 `Encode` 탭과 `Slide` 탭을 제공한다.
- `slide`는 초기 입력 디렉터리 인자와 재생 상태 controller 일부 분리를 지원한다.
- `sender`와 `receiver`는 명령 없이 jar를 실행하면 GUI를 열고, 명령이나 CLI 옵션이 있으면 CLI로 동작한다.
- sender Encode GUI는 실행 중 입력을 잠그고 `Stop`으로 취소한다. 취소 시 이번 실행에서 만든 encode 산출물을 정리한다.
- Browse 버튼은 공통 `DirectoryChooser`를 사용해 macOS Finder, Windows native Explorer 스타일을 우선 시도하고 Swing chooser로 fallback한다.

이 순서를 권장하는 이유:

- `capture`는 이미 preview/status 이벤트가 있어 성공 확률이 높다.
- `encode` / `decode`는 코어 로직이 이미 분리돼 있어 다음 단계 후보로 적합하다.
- `slide`는 현재 결합도가 가장 높아 마지막에 다루는 편이 안전하다.

## 완료 기준

- 기존 CLI 명령의 help/version/기본 실행 회귀가 깨지지 않는다.
- `sender gui`, `receiver gui`가 각각 정상 실행된다.
- GUI와 CLI가 같은 서비스 계약을 사용한다.
- `encode`, `capture`, `decode`는 GUI에서도 중간 진행 상황을 표시할 수 있다.
- GUI 요청 시 headless 환경에서 실패 메시지가 명확하다.
- 핵심 흐름 `encode -> slide -> capture -> decode`를 GUI/CLI 조합으로 수동 검증할 수 있다.

## 테스트 계획

- service 단위 테스트: 입력 검증, 상태 전이, 결과 객체, 취소, 오류 분류
- CLI 테스트: 옵션 파싱, 종료 코드, help/version, 대표 성공 경로
- GUI 테스트: 최소한의 launcher, 이벤트 바인딩, 버튼 상태, 필드 입력 반영
- 수동 테스트: 실제 디스플레이 환경에서 `slide`, 카메라 환경에서 `capture`

## 보류 항목

- `identify`, `pack`, `unpack`의 GUI 편입 여부
- `reencode`를 사용자 GUI에 노출할지 여부
- 계약 안정화 후 `transfer-core`, `carrier-qr` 모듈 분리 착수 여부
- GUI 텍스트 국제화 범위를 어디까지 가져갈지 여부
