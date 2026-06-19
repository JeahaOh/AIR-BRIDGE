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
- 자동 테스트는 이미 들어가 있으며 2026-05-26 기준 `./gradlew check`로 통과 확인했다.
- 앱 버전 표기는 `{major}.{minor}.{yymmdd}.{hh24mi}` 형식으로 관리한다.
- QR 전송 파이프라인은 `encode -> slide -> capture -> decode`로 본다.
- `identify -> pack -> unpack`는 `jar` 또는 `zip` 반입을 돕는 보조 흐름으로 본다.
- CLI/GUI 병행 지원은 현재 구현 문서 기준으로 유지하고, `gui-cli-plan.md`는
  계획 기록으로 남길지 정리할지 별도 판단한다.

## 남은 작업

### 1. 정적분석 후속 조치

2026-05-26 기준 `./gradlew check`는 통과했지만, 별도 SpotBugs/Checkstyle/PMD
같은 정적분석 플러그인은 아직 없습니다. 아래 항목은 코드 리딩 기반으로 확인한
후속 조치입니다.

#### 예상 버그

- [완료] `EncodeService`: `--no-folder-structure` 사용 시 QR 파일명이 basename 기반이라
  `a/sample.txt`, `b/sample.txt`처럼 같은 이름의 파일이 같은 출력 폴더에서
  덮어써질 수 있다. → 평탄화 출력 시 상대경로 기반 `flatSafePrefix`를 쓰도록 수정.
  `reencode`도 항상 평탄화하므로 동일 적용. (회귀 테스트 추가)
- `EncodeService`: `--encode-root`가 실제 소스 파일의 상위 경로인지 검증하지
  않는다. 잘못 지정하면 payload 상대경로에 `../`가 들어가 receiver에서
  복원 실패할 수 있으므로 encode 시작 전에 검증한다.
- [완료] `SlideApp`: `imageCache`와 `loadingImages`가 EDT와 이미지 로더 스레드에서
  동시에 접근된다. → EDT 쪽 `clear()`/`size()`도 `imageCache` 모니터로 보호
  (로더와 동일 락). `cacheSize()` 헬퍼 추가.
- [완료] `SourceCollector`: exclude path 비교가 문자열 `startsWith`라 `/src/a` 제외 시
  `/src/abc`도 제외될 수 있다. → `Path.equals`/`Path.startsWith` 기반 경계 비교로
  변경(`isExcluded`). 제외 디렉터리는 `SKIP_SUBTREE`로 가지치기. (회귀 테스트 추가)

#### 불필요 파일과 정리 대상

- [완료] git에 tracked 된 `apps/*/bin/main/*`, `libs/*/bin/main/*` 리소스 파일은 빌드
  산출물 성격이다. → `git rm --cached`로 추적 해제.
- [완료] `.gitignore`에 `**/bin/`을 추가해 IDE/Gradle이 만든 `bin` 출력물이 다시
  들어오지 않게 한다.
- `command.gui.description` 리소스 키는 현재 `@Command(description = "...")`
  하드코딩 때문에 실제 help 출력에 쓰이지 않는다. description을 리소스 키 기반으로
  바꾸거나 미사용 키를 제거한다.
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

### 4. 메모리 및 성능

- `encode`/`reencode`가 파일 전체를 `byte[]`와 Base64 문자열로 한 번에 올리는 구조를 줄인다.
- 큰 파일이나 많은 파일에서 heap 사용량이 급증하지 않도록 스트리밍 또는 단계별 처리 방식을 검토한다.
- `print-html`은 모든 PNG를 base64 inline으로 한 파일에 모으지 말고 분할 출력 또는 외부 파일 참조 방식도 지원한다.
- 대량 QR 세트에서 처리 시간과 메모리 사용량을 측정하는 벤치마크를 추가한다.

### 5. 구조 리팩터링 후속 검토

현재는 `apps/*`, `libs/*`의 기존 모듈 경계를 유지하고, 우선 서비스 계약을
정리합니다. `transfer-core`나 `carrier-qr` 같은 추가 모듈 분리는 계약이
충분히 안정화된 뒤 별도 작업으로 검토합니다.

검토할 때의 기준:

- sender code가 capture 전용 런타임에 의존하지 않는다.
- receiver code가 sender 전용 UI 동작에 의존하지 않는다.
- QR payload 형식 변경은 sender, receiver, tests, docs를 함께 갱신한다.
- `capture`는 카메라/프레임 수집 책임을 중심으로 유지한다.
