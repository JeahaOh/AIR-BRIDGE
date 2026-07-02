# Sender GUI 시작하기

이 문서는 송신 PC에서 `sender` GUI를 여는 방법과 처음 확인할 내용을 정리합니다.

## sender가 하는 일

송신 PC에서는 보통 두 가지 작업만 합니다.

- `Encode`: 보낼 파일을 QR 이미지 세트로 변환
- `Slide`: 만들어진 QR 이미지를 화면에 순서대로 재생

## 실행 파일

배포받은 sender jar를 사용합니다. 파일 이름은 보통 `sender-<version>.jar`
형식입니다. `<version>` 자리에는 실제 파일명에 표시된 버전을 그대로 사용합니다.

## GUI 열기

jar 파일을 실행하면 sender GUI가 열립니다.

```bash
java -jar sender-<version>.jar
```

## 기본 사용 순서

| 순서 | 화면 | 할 일 | 옵션 |
| --- | --- | --- | --- |
| 1 | 실행 | `java -jar sender-<version>.jar`로 sender GUI를 엽니다. | 없음 |
| 2 | `Encode` | 보낼 파일이 들어 있는 입력 폴더를 선택합니다. | 기본값 유지 |
| 3 | `Encode` | QR 이미지가 저장될 출력 폴더를 선택합니다. | 기본값 유지 |
| 4 | `Encode` | 옵션은 기본값 그대로 두고 `Encode`를 누릅니다. | 기본값 유지 |
| 5 | `Encode` | 출력 폴더에 QR PNG가 생겼는지 확인합니다. | 없음 |
| 6 | `Slide` | 수신 PC에서 `Capture`가 시작된 뒤 QR PNG 출력 폴더를 선택합니다. | 기본값 유지 |
| 7 | `Slide` | `Play`를 눌러 QR 이미지를 재생합니다. | 기본값 유지 |

`Encode` 탭에서 출력 폴더까지 지정한 뒤 `Slide` 버튼을 누르면, 같은 출력
폴더를 `Slide` 탭 입력으로 바로 열 수 있습니다.

실행 중에는 입력값과 `Browse` 버튼이 잠깁니다. 중간에 멈춰야 하면 `Stop`을
누릅니다. 중단된 실행에서 새로 만든 출력 파일은 정리됩니다.

## 기본값

- `Page(ms)`: 100
- `Black(ms)`: 50
- `Loop`: 1
- `Full Screen`: 켜짐
- `Always On Top`: 켜짐

## 폴더 선택창

`Browse`를 누르면 운영체제에 맞는 폴더 선택창이 열립니다.

- macOS: Finder 스타일 창
- Windows/기타: Java 기본(Swing) 폴더 선택창

입력칸에 이미 유효한 경로가 있으면 그 위치에서 시작합니다. 비어 있거나
잘못된 경로면 앱을 시작한 위치에서 시작합니다.

## 같이 볼 문서

- [`encode-decode.md`](encode-decode.md)
- [`slide-capture.md`](slide-capture.md)
