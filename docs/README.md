# docs

문서는 사용 방식에 따라 세 묶음으로 나눠 관리합니다.

- `user/` : **CLI(터미널 명령)** 로 쓰는 사람을 위한 문서
- `gui-user-manual/` : **GUI(화면이 있는 앱)** 로 쓰는 사람을 위한 문서
- `dev/` : 내부 동작·성능·TODO 등 개발/유지보수용 문서

## 새 담당자 읽는 순서

1. 루트 `README.md` — 무엇을 하는 도구인지, 빌드/실행
2. `AGENTS.md` — 지켜야 하는 제약(payload 호환성, 경로 안전, air-gap)과 모듈 경계
3. `dev/encode-decode.md` — QR payload 프레임 규칙과 encode/decode 내부 동작
4. `dev/slide-capture.md` — slide/capture 내부 동작과 페이싱
5. `dev/todo.md` — 남은 작업과 보류 항목

payload 프레임 형식을 바꾸는 변경은 sender·receiver·테스트·문서를 반드시 함께 고친다.

## CLI 사용자용 문서 (`user/`)

- `user/deploy-receiver.md`: `receiver`를 실제로 실행하는 방법
- `user/deploy-sender.md`: `sender`를 실제로 실행하는 방법
- `user/encode-decode.md`: `encode` / `decode` 사용자 실행 가이드
- `user/packager.md`: `identify` / `pack` / `unpack` 사용자 실행 가이드
- `user/slide-capture.md`: `slide` / `capture` 사용자 실행 가이드
- `user/tuning.md`: JVM과 실행 성능 관련 선택적 조정 가이드
- `user/warning.ko.md`: 사용 전 반드시 확인해야 하는 강한 경고 문서
- `user/warning.en.md`: `warning`의 영문 버전

## GUI 사용자용 문서 (`gui-user-manual/`)

- `gui-user-manual/README.md`: GUI 전체 흐름 한눈에 보기 (여기부터 읽으면 됩니다)
- `gui-user-manual/deploy-sender.md`: sender GUI 실행 방법
- `gui-user-manual/deploy-receiver.md`: receiver GUI 실행 방법
- `gui-user-manual/encode-decode.md`: GUI로 파일 ↔ QR 이미지 변환·복원
- `gui-user-manual/slide-capture.md`: GUI로 화면 재생(Slide)·캡처(Capture)
- `gui-user-manual/tuning.md`: 잘 안 될 때만 보는 속도/안정성 조정
- `gui-user-manual/warning.ko.md`: 사용 전 반드시 확인할 경고

## 개발용 문서

- `dev/encode-decode.md`: `encode` / `decode` 내부 동작과 payload 규칙 정리
- `dev/faster.md`: `slide` / `capture` 성능 튜닝 메모
- `dev/packager.md`: `identify` / `pack` / `unpack` 내부 동작 정리
- `dev/slide-capture.md`: `slide` / `capture` 내부 동작과 상태 전이 정리
- `dev/codex/README.md`: AI 어시스턴트 작업용 보조 문서, 프롬프트, 검증 스크립트 안내
- `dev/todo.md`: 현재 남아 있는 작업만 정리한 TODO

## 정리 원칙

- 사용자용 문서(`user/`, `gui-user-manual/`)는 실행, 배포, 운영 경고처럼 실제 사용자가 바로 참고할 내용만 둡니다.
- 같은 작업이라도 터미널로 쓰면 `user/`, 화면이 있는 앱으로 쓰면 `gui-user-manual/`을 봅니다.
- 개발용 문서는 내부 파이프라인, 성능 메모, TODO처럼 구현과 유지보수에 필요한 내용만 둡니다.
- 루트 `docs/` 아래에는 인덱스 성격의 문서만 두고, 실제 문서는 `user/`, `gui-user-manual/`, `dev/` 아래에 둡니다.
- 문서는 현재 `apps/*`, `libs/*` 구조와 실제 CLI 기준으로 유지한다.
- 이미 끝난 작업 기록이나 구현 전 설계 초안은 운영 문서에 남기지 않는다.
- 과거 구조 검토 문서는 별도 보관 가치가 없으면 삭제한다.
