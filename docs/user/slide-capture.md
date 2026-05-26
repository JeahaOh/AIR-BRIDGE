# Slide / Capture Usage

이 문서는 `slide`와 `capture`를 함께 사용하는 흐름을 실제 사용 기준으로 정리합니다.

## 언제 쓰나

`encode`로 만든 QR PNG 세트를 송신 측 화면에 재생하고, 수신 측에서 카메라로 받아 저장할 때 사용한다.

일반적인 흐름:

1. 송신 측에서 `encode` 실행
2. 송신 측에서 `slide` 실행
3. 수신 측에서 `capture` 실행
4. 수신 측에서 `decode` 실행

즉 `slide`와 `capture`는 중간 전송 단계입니다.

## 준비물

- 송신 측:
  - `sender` jar
  - `encode` 결과 QR PNG 디렉터리
- 수신 측:
  - `receiver` jar
  - 카메라 또는 capture board
  - 캡처 결과를 저장할 디렉터리

## 1. 송신 측: slide

기본 실행:

```bash
java -jar build/libs/sender-<version>.jar slide
```

초기 입력 디렉터리를 지정해 열기:

```bash
java -jar build/libs/sender-<version>.jar slide --in /path/qr
```

도움말:

```bash
java -jar build/libs/sender-<version>.jar slide --help
```

입력 규칙:

- `slide`는 선택한 디렉터리 아래의 `.png`, `.jpg`, `.jpeg`를 재귀적으로 읽습니다.
- `session-start`가 포함된 파일은 먼저, `session-end`가 포함된 파일은 마지막에 배치됩니다.
- 나머지는 상대 경로 기준으로 정렬됩니다.

실행 후:

1. `Browse`로 QR PNG가 들어 있는 디렉터리를 선택한다.
2. 필요하면 `Page(ms)`, `Black(ms)`, `Loop`를 조정한다.
3. `Play`로 재생을 시작한다.

Browse 선택창은 macOS에서는 Finder 스타일 창을 사용하고, Windows에서는
native Explorer 스타일 창을 우선 시도한 뒤 필요하면 Swing 디렉터리 선택창으로
내려갑니다. 입력칸 경로가 유효하면 그 위치에서 시작하고, 비어 있거나
유효하지 않으면 앱을 시작한 위치에서 시작합니다.

주요 UI:

- `Browse`: 입력 디렉터리 선택
- `Reload`: 이미지 다시 읽기
- `Page(ms)`: 한 장 표시 시간
- `Black(ms)`: 페이지 사이 검은 화면 시간
- `Loop`: 반복 횟수
- `Full Screen`
- `Always On Top`
- `Play` / `Pause`

기본값:

- `Page(ms)`: `100`
- `Black(ms)`: `50`
- `Loop`: `1`
- `Full Screen`: 기본 켜짐
- `Always On Top`: 기본 켜짐

자주 쓰는 단축키:

- `Space`: 재생 / 일시정지
- `Left` / `Right`: 이전 / 다음
- `Page Up` / `Page Down`: 100장 이동
- `F`: 전체화면 토글
- `T`: 패널 토글
- `Q`: 종료

## 2. 수신 측: capture

기본 실행:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out
```

GUI 실행:

```bash
java -jar build/libs/receiver-<version>.jar gui
```

GUI의 `Capture` 탭은 CLI `capture`와 같은 `CaptureService`를 사용합니다.
출력 디렉터리와 장치 설정을 입력한 뒤 `Start`로 캡처를 시작하고,
필요하면 `Stop`으로 종료합니다.
GUI에서는 `duration`, `max payloads`, `same signal` 값을 직접 입력하지 않고
내부 기본값을 사용합니다. 캡처가 실행되는 동안에는 Capture 탭의 입력값이
잠기고 `Decode` 버튼도 비활성화됩니다. `Preview`와 `Preview FPS`는 캡처
실행 중에도 바꿀 수 있으며, GUI 미리보기 갱신 빈도에만 영향을 줍니다.
캡처가 끝난 뒤 같은 GUI의 `Decode` 탭에서 `captured-images` 디렉터리를
입력으로 선택해 복원할 수 있습니다.

Decode 탭에서 복원을 실행하는 동안에는 QR PNG 입력 디렉터리, 복원 출력
디렉터리, Browse 버튼, Decode 버튼이 잠깁니다.

장치 목록 확인:

```bash
java -jar build/libs/receiver-<version>.jar capture --list-devices
```

장치와 해상도를 직접 지정:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out \
  --device 0 \
  --width 1920 \
  --height 1080 \
  --fps 15
```

중단 후 이어서 저장:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out \
  --resume
```

주요 산출물:

- `captured-images/frame_000001.png`
- `captured-images/frame_000002.png`
- `capture-manifest.json`

## 3. capture 후 decode

캡처가 끝나면 저장된 PNG를 `decode`로 복원한다.

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/capture-out/captured-images \
  --out /path/restore
```

## 권장 설정

`slide` 기본 권장:

- `Page(ms)`: `100`
- `Black(ms)`: `50`

보수적 설정:

- `Page(ms)`: `250`
- `Black(ms)`: `0`

고속 테스트:

- `Page(ms)`: `140`
- `Black(ms)`: `10`

`capture` 기본 권장:

- `--fps 15`
- `--decode-workers 4`

처음에는 보수적으로 맞추고, `capture-manifest.json`과 `decode` 결과를 본 뒤 속도를 올리는 편이 안전합니다.

## 결과 확인

`capture-manifest.json`:

- 캡처 실행 요약
- 저장한 이미지 수
- 중복 제거 후 payload 수
- 종료 이유

`_restore_result.txt`:

- 최종 복원 성공 / 실패 결과

복원까지 확인해야 실제 전송 성공 여부를 판단할 수 있습니다.

## 운영 팁

- `slide`는 시작 직후 전체화면과 전면 유지 성격이 강하므로 일반 데스크톱 앱처럼 쓰기 어렵습니다.
- `slide`와 `capture`는 처음에는 같은 해상도와 안정적인 화면 비율에서 맞춘다.
- 고속 재생은 항상 `decode` 결과까지 같이 확인한다.
- 캡처가 중간에 끊기면 `--resume`으로 이어서 받는 편이 낫다.
- 장치 이름이 애매하면 먼저 `--list-devices`로 확인한다.
- `slide`는 화면 점유 성격이 강하므로 다른 작업과 병행하기 불편할 수 있습니다.

## 관련 문서

- `encode-decode.md`
- `deploy-sender.md`
- `deploy-receiver.md`
