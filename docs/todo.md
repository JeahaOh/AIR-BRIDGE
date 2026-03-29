# air-bridge TODO

기준 시점: 2026-03-29

## 현재 기준

- 최종 배포 산출물은 `sender`, `receiver` 두 앱만 유지한다.
- 루트 구조는 `apps/*`, `libs/*` 기준으로 유지한다.
- 공개 명령은 아래 기준으로 유지한다.
  - `sender`: `encode`, `slide`, `unpack`
  - `receiver`: `decode`, `capture`, `identify`, `pack`
- `printer`는 별도 명령이 아니라 `--help`, `--version`에서 쓰는 공통 배너 출력으로 본다.
- 공통 배너와 버전 출력 유틸은 `common`에 둔다.
- 자동 테스트는 이미 들어가 있으며 현재 `./gradlew test` 기준으로 통과한다.
- 앱 버전 표기는 `{major}.{minor}.{yymmdd}.{hh24mi}` 형식으로 관리한다.

## 남은 작업

### 1. 문서 정리

README.ko.md, deploy-\*.md 등 문서 정리

### 2. 구조 리팩터링

- `encode`/`decode`를 이름 그대로 라이브러리로 자르지 않고 `transfer-core`와 `carrier-qr`로 분리한다.
- 목표 의존 구조는 아래 기준으로 둔다.
  - `sender` -> `common`, `packager`, `slide`, `transfer-core`, `carrier-qr`
  - `receiver` -> `common`, `capture`, `packager`, `transfer-core`, `carrier-qr`
- `capture`는 카메라/프레임 수집에 집중하고, 실제 carrier 해석 로직과는 느슨하게 유지한다.
- 리팩터링 순서는 아래 기준으로 진행한다.
  1. QR 이미지 write/read 책임을 `carrier-qr`로 이동
  2. payload/chunk/hash/manifest/restore 규약을 `transfer-core`로 이동
  3. `sender`, `receiver`는 CLI와 orchestration 중심으로 정리
  4. 이후 QR 외 캐리어 추가 가능성 검토

### 3. 메모리 및 성능

- `encode`/`reencode`가 파일 전체를 `byte[]`와 Base64 문자열로 한 번에 올리는 구조를 줄인다.
- 큰 파일이나 많은 파일에서 heap 사용량이 급증하지 않도록 스트리밍 또는 단계별 처리 방식을 검토한다.
- `print-html`은 모든 PNG를 base64 inline으로 한 파일에 모으지 말고 분할 출력 또는 외부 파일 참조 방식도 지원한다.
- 대량 QR 세트에서 처리 시간과 메모리 사용량을 측정하는 벤치마크를 추가한다.
