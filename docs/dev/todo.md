# air-bridge TODO

기준 시점: 2026-05-01

## 현재 기준

- 최종 배포 산출물은 `sender`, `receiver` 두 앱만 유지한다.
- 루트 구조는 `apps/*`, `libs/*` 기준으로 유지한다.
- 공개 명령은 아래 기준으로 유지한다.
  - `sender`: `encode`, `gui`, `slide`, `unpack`
  - `receiver`: `decode`, `capture`, `gui`, `identify`, `pack`
- `printer`는 별도 명령이 아니라 `--help`, `--version`에서 쓰는 공통 배너 출력으로 본다.
- 공통 배너와 버전 출력 유틸은 `common`에 둔다.
- 자동 테스트는 이미 들어가 있으며 현재 `./gradlew test` 기준으로 통과합니다.
- 앱 버전 표기는 `{major}.{minor}.{yymmdd}.{hh24mi}` 형식으로 관리한다.
- QR 전송 파이프라인은 `encode -> slide -> capture -> decode`로 본다.
- `identify -> pack -> unpack`는 `jar` 또는 `zip` 반입을 돕는 보조 흐름으로 본다.
- CLI/GUI 병행 지원 계획은 `gui-cli-plan.md`를 기준으로 진행한다.

## 남은 작업

### 1. GUI / CLI 병행 지원

1차 목표는 핵심 전송 흐름을 CLI와 GUI 양쪽에서 안정적으로 사용할 수 있게
하는 것입니다.

- 범위: `sender encode`, `sender slide`, `receiver capture`, `receiver decode`
- 제외: `sender reencode`, `receiver identify`, `receiver pack`, `sender unpack`
- 원칙: CLI와 GUI가 서로를 감싸지 않고 같은 서비스 계약을 호출한다.
- 세부 계획: `gui-cli-plan.md`

진행 상태:

- 완료: `capture` GUI adapter 추가
- 완료: `encode` / `decode` 공용 workflow 계약 정리
- 완료: `sender gui`, `receiver gui` 기본 창 추가
- 완료: `slide` 재생 상태 controller 일부 분리
- 남음: 보조 유틸리티 GUI 여부 재검토

### 2. 사용자 문서 정리

GUI/CLI 병행 지원 범위가 실제 구현으로 고정되면 아래 문서를 함께 갱신합니다.

- `README.md`
- `README.ko.md`
- `README.en.md`
- `docs/user/deploy-sender.md`
- `docs/user/deploy-receiver.md`
- `docs/user/encode-decode.md`
- `docs/user/slide-capture.md`

### 3. 메모리 및 성능

- `encode`/`reencode`가 파일 전체를 `byte[]`와 Base64 문자열로 한 번에 올리는 구조를 줄인다.
- 큰 파일이나 많은 파일에서 heap 사용량이 급증하지 않도록 스트리밍 또는 단계별 처리 방식을 검토한다.
- `print-html`은 모든 PNG를 base64 inline으로 한 파일에 모으지 말고 분할 출력 또는 외부 파일 참조 방식도 지원한다.
- 대량 QR 세트에서 처리 시간과 메모리 사용량을 측정하는 벤치마크를 추가한다.

### 4. 구조 리팩터링 후속 검토

현재는 `apps/*`, `libs/*`의 기존 모듈 경계를 유지하고, 우선 서비스 계약을
정리합니다. `transfer-core`나 `carrier-qr` 같은 추가 모듈 분리는 계약이
충분히 안정화된 뒤 별도 작업으로 검토합니다.

검토할 때의 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.
