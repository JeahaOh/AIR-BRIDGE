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

기본 실행(입력 미지정 시 jar 옆 `encoded` 디렉터리를 엽니다):

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

Browse 선택창은 macOS에서는 Finder 스타일 창을 사용하고, Windows/리눅스에서는
폴더 선택 전용 Swing 디렉터리 선택창을 사용합니다(시스템 Look&Feel 적용). 입력칸
경로가 유효하면 그 위치에서 시작하고, 비어 있거나 유효하지 않으면 앱을 시작한
위치에서 시작합니다.

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

조정 범위(고속 재생용):

- `Page(ms)`: 최소 `20`까지 내릴 수 있습니다(모니터 주사율보다 더 내려도 효과 없음).
- `Black(ms)`: 최소 `1`까지 내릴 수 있습니다.
- 빠르게 내릴수록 카메라가 놓칠 확률이 올라가므로, `decode`의 INCOMPLETE를 보며 한계를 찾습니다.
  capture가 알려 주는 권장값(아래 참고)을 기준으로 잡으면 안전합니다.

자주 쓰는 단축키:

- `Space`: 재생 / 일시정지
- `Left` / `Right`: 이전 / 다음
- `Page Up` / `Page Down`: 100장 이동
- `F`: 전체화면 토글
- `T`: 패널 토글
- `Q`: 종료

## 2. 수신 측: capture

기본 실행(`--out` 미지정 시 jar 옆 `captured` 디렉터리에 저장):

```bash
java -jar build/libs/receiver-<version>.jar capture
```

카메라가 열려 수신 준비가 끝나면 **READY 배너**가 출력됩니다. 그때 송신 측에서
`slide` 재생을 시작하면 됩니다.

출력 디렉터리를 직접 지정:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out
```

GUI 실행:

```bash
java -jar build/libs/receiver-<version>.jar gui
```

GUI의 `Capture` 탭은 CLI `capture`와 같은 `CaptureService`를 사용합니다.
출력 디렉터리와 장치를 고른 뒤 `Start`로 캡처를 시작하고, 필요하면 `Stop`으로
종료합니다. 장치는 `Devices` 버튼으로 목록을 불러와 **드롭다운에서 선택**합니다
(정수 인덱스 입력 대신).
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

장치와 해상도를 직접 지정(`--device`는 정수 인덱스 또는 장치 이름 일부로 지정 가능):

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out \
  --device 0 \
  --width 1920 \
  --height 1080 \
  --fps 15
```

이름으로 지정하는 예(대소문자 무시, 부분일치):

```bash
java -jar build/libs/receiver-<version>.jar capture --device FaceTime
```

중단 후 이어서 저장:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out \
  --resume \
  --resume-index
```

주요 산출물:

- `captured-images/frame_000001.png`
- `captured-images/frame_000002.png`
- `capture-manifest.json`

## 3. capture 후 decode

캡처가 끝나면 저장된 PNG를 `decode`로 복원한다. `decode`는 입력 디렉터리를
재귀적으로 훑으므로 `captured` 디렉터리를 그대로 입력해도 그 안의
`captured-images`를 찾습니다(옵션 미지정 시 기본 입력이 `captured`).

```bash
java -jar build/libs/receiver-<version>.jar decode
```

경로를 직접 지정:

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/capture-out \
  --out /path/restore
```

## 권장 설정

`slide` 기본 권장:

- `Page(ms)`: `100`
- `Black(ms)`: `50`

보수적 설정:

- `Page(ms)`: `250`
- `Black(ms)`: `1` (입력 하한)

고속 테스트:

- `Page(ms)`: `140`
- `Black(ms)`: `10`

`capture` 기본 권장:

- `--fps 15`
- `--decode-workers 4`

처음에는 보수적으로 맞추고, `capture-manifest.json`과 `decode` 결과를 본 뒤 속도를 올리는 편이 안전합니다.

### capture가 알려주는 권장 속도

`capture`는 실행 중 **약 10초마다**(상태 로그 주기), 그리고 종료 시 한 번 더 이런 줄을 출력합니다.

```text
[CAPTURE][INFO] 고유 12.3 QR/s -> slide page-display-ms >= 90ms 권장 — 지금 slide Page(ms)가 90보다 작으면 올리고, 크면 90까지 내려도 됩니다 (가이드값)
```

- 지금까지 실제로 받아낸 속도를 바탕으로 **다음 전송 때 쓸 `Page(ms)`(slide 한 장 표시 시간)** 를
  계산해 알려 줍니다. 캡처가 진행될수록 값이 갱신되고, 지금 쓰는 `Page(ms)`와 비교해 올릴지
  내릴지 방향까지 문구로 알려 줍니다.
- 수신 측에서 송신 측으로 신호를 보내는 통로는 없으므로 이 값은 **가이드(권장값)** 입니다. 그대로
  강제되는 설정이 아니라, 다음 `slide` 재생 때 `Page(ms)`를 이 값 이상으로 잡는 기준으로 쓰면 됩니다.
- 너무 빨라서 놓친 QR이 많았다면 이 값이 커지고, 여유가 있었다면 작아집니다. fountain 복구와 슬라이드
  반복(`Loop`)이 어느 정도 손실을 흡수하므로, 값을 약간 밑돌더라도 복원이 될 수 있습니다.

### 언제 slide를 멈춰도 되는지 (복원 가능 감지)

`capture`는 QR을 받으면서 **파일별로 심볼이 충분히 모였는지**를 실시간으로 판정합니다.
어떤 파일이 복원 가능해지는 순간, 그리고 지금까지 관측된 모든 파일이 복원 가능해지는 순간
이런 줄이 출력됩니다.

```text
[CAPTURE][DONE] dir/file.txt — 심볼 10개 수집(k=10), decode로 복원 가능
[CAPTURE][DONE] ========================================================
[CAPTURE][DONE] 관측된 파일 3개 모두 복원 가능 — slide를 정지해도 됩니다 (아직 한 번도 안 잡힌 파일이 있다면 계속 재생)
[CAPTURE][DONE] ========================================================
```

- 이 배너가 뜨면 송신 PC에서 `slide`를 멈추고 `decode`로 넘어가면 됩니다. 수신 측이 송신 측을
  자동으로 멈출 수는 없으므로 **정지는 직접** 합니다.
- 주의: "관측된 파일" 기준입니다. 어떤 파일의 QR이 **한 번도 카메라에 잡히지 않았다면** 그 파일은
  집계에 아예 들어가지 않습니다. 보낸 파일 개수를 알고 있다가, 상태 로그의 `decodableFiles=N/M`
  (복원 가능/관측)과 맞춰 보세요.
- 최종 확정은 언제나 `decode` 결과입니다(이 감지는 심볼 개수 기준이므로, 내용 이상은 decode의
  `HASH_MISMATCH` 단계에서 걸러집니다).

## 결과 확인

`capture-manifest.json`:

- 캡처 실행 요약
- 저장한 이미지 수
- 중복 제거 후 payload 수
- 관측 파일 수 / 복원 가능 파일 수 (`observedFiles` / `decodableFiles`)
- 종료 이유

`_restore_result.txt`:

- 최종 복원 성공 / 실패 결과

복원까지 확인해야 실제 전송 성공 여부를 판단할 수 있습니다.

## 운영 팁

- `slide`는 시작 직후 전체화면과 전면 유지 성격이 강하므로 일반 데스크톱 앱처럼 쓰기 어렵습니다.
- `slide`와 `capture`는 처음에는 같은 해상도와 안정적인 화면 비율에서 맞춘다.
- 고속 재생은 항상 `decode` 결과까지 같이 확인한다.
- 캡처가 중간에 끊기면 `--resume`으로 이어서 받는 편이 낫다. 대량 캡처라면 처음부터
  `--resume-index`를 함께 사용하고, 재개할 때도 두 옵션을 함께 준다. 이 옵션이 없으면 기존과 같이
  PNG 전체를 다시 읽어 중복 상태를 복원한다.
- 장치 이름이 애매하면 먼저 `--list-devices`로 확인한다.
- `slide`는 화면 점유 성격이 강하므로 다른 작업과 병행하기 불편할 수 있습니다.

## 관련 문서

- `encode-decode.md`
- `deploy-sender.md`
- `deploy-receiver.md`
