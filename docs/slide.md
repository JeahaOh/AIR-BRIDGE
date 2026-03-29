# Slide

이 문서는 현재 [`SlideApp.java`](../libs/slide/src/main/java/airbridge/slide/SlideApp.java) 구현 기준의 `slide` 동작만 정리한 운영 문서다. 현재 `slide`는 독립 산출물이 아니라 `sender`에 번들된다.

## 목적

`slide`는 `sender`가 만든 QR 이미지 또는 일반 이미지 세트를 화면에 순서대로 재생하는 Swing 앱이다.

용도:

- QR 송출
- 재생 순서 확인
- 수동 페이지 이동
- 반복 재생

## 실행

기본 실행:

```bash
java -jar build/libs/sender-<version>.jar slide
```

도움말:

```bash
java -jar build/libs/sender-<version>.jar slide --help
```

개발 중 직접 실행이 필요하면 아래처럼 Gradle run도 사용할 수 있다.

```bash
./gradlew :sender:run --args="slide"
```

주의:

- `slide`는 별도 하위 옵션을 받지 않는다.
- 입력 디렉터리는 실행 후 GUI에서 `Browse`로 선택한다.
- 시작 직후 현재 단축키 안내를 CLI에 한 번 출력한다.

## 기본 UI

- 상단 바
  - 입력 디렉터리
  - `Browse`
  - `Reload`
  - `Page(ms)`
  - `Black(ms)`
  - `Loop`
  - `Full Screen`
  - `Always On Top`
  - `Play` / `Pause`
- 중앙
  - 현재 이미지 표시
- 우측 패널
  - 페이지 트리
- 하단 상태 바
  - 재생 상태
  - 현재 인덱스 / 전체 개수
  - page/black 시간
  - 현재 파일 경로

## 기본값

- `Page(ms)`: `400`
- `Black(ms)`: `100`
- `Loop`: `1`
- `Full Screen`: `On`
- `Always On Top`: `On`

현재 성능 튜닝 기준 캐시 값:

- cache size: `200`
- initial preload: `30`
- prefetch: `20`

## 지원 입력

- 재귀적으로 이미지 파일을 읽는다.
- 지원 확장자:
  - `.png`
  - `.jpg`
  - `.jpeg`

정렬 규칙:

1. `session-start` 포함 파일
2. 일반 파일
3. `session-end` 포함 파일

같은 우선순위 안에서는 상대 경로 문자열 기준으로 정렬한다.

## 재생 동작

- `Play`를 누르면 현재 인덱스부터 재생한다.
- `Black(ms) > 0` 이면 페이지 사이에 잠깐 검은 화면을 넣는다.
- `Loop` 횟수를 모두 마치면 post-finish blackout 상태로 들어간다.
- 이 상태에서는 검은 화면만 남기고 5분 뒤 자동 종료한다.

현재 프레임 로딩은 비동기다.

- cache hit면 즉시 표시
- cache miss면 백그라운드 로드 예약
- 그동안은 placeholder 또는 직전 프레임 유지
- 로드 완료 후 현재 프레임이면 화면 갱신

## 단축키

- `Space`: Play/Pause
- `Left`: 이전 이미지
- `Right`: 다음 이미지
- `Page Up`: 100장 뒤로 이동
- `Page Down`: 100장 앞으로 이동
- `F`: Full Screen 토글
- `T`: 우측 패널 토글
- `Q`: 종료

## 상태 바

하단 상태 바는 현재 재생 상태와 마지막 실제 이미지 기준 정보를 보여준다.

특징:

- black frame 중에도 `BLACK` 같은 별도 상태 문구로 바뀌지 않는다.
- 현재 `PLAY` / `PAUSE` 흐름을 유지한다.

## 운영 성격

현재 구현은 작업용 미리보기보다는 상영/송출 성격이 강하다.

이유:

- 시작 시 fullscreen 진입
- always-on-top 기본 활성
- foreground recovery 동작
- mouse jiggle 동작

mouse jiggle:

- 약 60초마다 1픽셀 왕복 이동

그래서 다른 작업을 병행할 때는 불편할 수 있다.

## 권장 설정

기본 권장:

- `Page(ms)`: `200`
- `Black(ms)`: `0`

보수적:

- `Page(ms)`: `250`
- `Black(ms)`: `0`

고속 테스트:

- `Page(ms)`: `140`
- `Black(ms)`: `10`

고속 설정은 `capture` 인식률과 같이 확인해야 한다.
