# Sender Deployment

이 문서는 `sender` 배포와 실행 방식을 현재 코드 기준으로 정리한다.

## 용도

`sender`는 아래 작업을 담당한다.

- `encode`: 입력 파일을 QR 이미지 세트로 변환
- `slide`: QR 이미지 또는 일반 이미지 세트를 화면에 재생
- `unpack`: 패킹된 jar/zip의 `.txt` suffix를 제거

숨김 유지보수 명령:

- `reencode`: `_restore_result.txt` 기준 재생성

## 권장 배포 방식

기본 권장:

- `java -jar build/libs/sender-<version>.jar`

이유:

- `sender`는 별도 기본 JVM 메모리 옵션이 없다.
- 단일 fat jar 실행이 가장 단순하다.
- 운영 측에서 Java만 준비되어 있으면 바로 실행 가능하다.

## 산출물

```bash
build/libs/sender-<version>.jar
```

## 권장 실행 예시

도움말:

```bash
java -jar build/libs/sender-<version>.jar --help
```

인코딩:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT
```

슬라이드 도움말:

```bash
java -jar build/libs/sender-<version>.jar slide --help
```

언패킹:

```bash
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

## 배포 전 스모크 체크

- `java -jar build/libs/sender-<version>.jar --help`
- `java -jar build/libs/sender-<version>.jar encode --help`
- `java -jar build/libs/sender-<version>.jar unpack --help`
- `java -jar build/libs/sender-<version>.jar slide --help`

주의:

- `slide` 실제 GUI 오픈 여부는 운영 환경에서 별도 수동 확인이 필요하다.
