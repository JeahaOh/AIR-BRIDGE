# docs

현재 문서는 `user/` 와 `dev/` 아래로 분리해 관리합니다.

## 사용자용 문서

- `user/deploy-receiver.md`: `receiver`를 실제로 실행하는 방법
- `user/deploy-sender.md`: `sender`를 실제로 실행하는 방법
- `user/encode-decode.md`: `encode` / `decode` 사용자 실행 가이드
- `user/packager.md`: `identify` / `pack` / `unpack` 사용자 실행 가이드
- `user/slide-capture.md`: `slide` / `capture` 사용자 실행 가이드
- `user/tuning.md`: JVM과 실행 성능 관련 선택적 조정 가이드
- `user/warning.ko.md`: 사용 전 반드시 확인해야 하는 강한 경고 문서
- `user/warning.en.md`: `warning`의 영문 버전

## 개발용 문서

- `dev/encode-decode.md`: `encode` / `decode` 내부 동작과 payload 규칙 정리
- `dev/faster.md`: `slide` / `capture` 성능 튜닝 메모
- `dev/packager.md`: `identify` / `pack` / `unpack` 내부 동작 정리
- `dev/slide-capture.md`: `slide` / `capture` 내부 동작과 상태 전이 정리
- `dev/codex/README.md`: Codex 작업용 보조 문서, 프롬프트, 검증 스크립트 안내
- `dev/todo.md`: 현재 남아 있는 작업만 정리한 TODO

## 정리 원칙

- 사용자용 문서는 실행, 배포, 운영 경고처럼 실제 사용자가 바로 참고할 내용만 둡니다.
- 개발용 문서는 내부 파이프라인, 성능 메모, TODO처럼 구현과 유지보수에 필요한 내용만 둡니다.
- 루트 `docs/` 아래에는 인덱스 성격의 문서만 두고, 실제 문서는 `user/` 와 `dev/` 아래에 둡니다.
- 문서는 현재 `apps/*`, `libs/*` 구조와 실제 CLI 기준으로 유지한다.
- 이미 끝난 작업 기록이나 구현 전 설계 초안은 운영 문서에 남기지 않는다.
- 과거 구조 검토 문서는 별도 보관 가치가 없으면 삭제한다.
