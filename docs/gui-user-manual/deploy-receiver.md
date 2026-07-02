# Receiver GUI 시작하기

이 문서는 수신 PC에서 `receiver` GUI를 여는 방법과 처음 확인할 내용을 정리합니다.

## receiver가 하는 일

수신 PC에서는 보통 두 가지 작업만 합니다.

- `Capture`: 카메라나 캡처 장치로 QR 화면을 이미지로 저장
- `Decode`: 저장된 QR 이미지를 원본 파일로 복원

## 실행 파일

배포받은 receiver jar를 사용합니다. 파일 이름은 보통 `receiver-<version>.jar`
형식입니다. `<version>` 자리에는 실제 파일명에 표시된 버전을 그대로 사용합니다.

## GUI 열기

jar 파일을 실행하면 receiver GUI가 열립니다.

```bash
java -jar receiver-<version>.jar
```

## 기본 사용 순서

| 순서 | 화면 | 할 일 | 옵션 |
| --- | --- | --- | --- |
| 1 | 실행 | `java -jar receiver-<version>.jar`로 receiver GUI를 엽니다. | 없음 |
| 2 | `Capture` | 카메라 또는 캡처 장치를 연결하고 출력 폴더를 선택합니다. | 기본값 유지 |
| 3 | `Capture` | 송신 PC 화면이 보이도록 장치를 맞춘 뒤 `Start`를 누릅니다. | 기본값 유지 |
| 4 | 대기 | 송신 PC에서 `Slide` 재생이 끝날 때까지 캡처를 유지합니다. | 기본값 유지 |
| 5 | `Capture` | 재생이 끝나면 `Stop`을 누릅니다. | 없음 |
| 6 | `Decode` | QR PNG 입력 폴더로 `captured-images`를 선택합니다. | 기본값 유지 |
| 7 | `Decode` | 복원 출력 폴더를 선택하고 `Decode`를 누릅니다. | 기본값 유지 |
| 8 | 결과 | 복원 출력 폴더에 원본 파일이 생겼는지 확인합니다. | 없음 |

캡처 결과는 보통 출력 폴더 아래 `captured-images` 폴더에 저장됩니다.

GUI에서는 `duration`, `max payloads`, `same signal` 값을 직접 입력하지 않습니다.
내부 기본값으로 동작합니다. 캡처 실행 중에는 캡처 입력값과 `Decode` 버튼이
잠깁니다. `Preview`와 `Preview FPS`는 실행 중에도 바꿀 수 있으며, 저장 결과가
아니라 화면 미리보기 갱신 속도에만 영향을 줍니다.

복원이 실행되는 동안에는 QR PNG 입력 폴더, 복원 출력 폴더, `Browse`, `Decode`
버튼이 잠깁니다.

성공한 QR PNG는 입력 폴더 옆의 `*-success` 폴더로 이동될 수 있습니다.

예:

- `captured-images/frame_000001.png`
- `captured-images-success/frame_000001.png`

## 폴더 선택창

`Browse`를 누르면 운영체제에 맞는 폴더 선택창이 열립니다.

- macOS: Finder 스타일 창
- Windows/기타: Java 기본(Swing) 폴더 선택창

입력칸에 이미 유효한 경로가 있으면 그 위치에서 시작합니다. 비어 있거나
잘못된 경로면 앱을 시작한 위치에서 시작합니다.

## 같이 볼 문서

- [`encode-decode.md`](encode-decode.md)
- [`slide-capture.md`](slide-capture.md)
- [`tuning.md`](tuning.md)
