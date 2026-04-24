# Sender Deployment

이 문서는 `sender`를 실제로 어떻게 실행하면 되는지 사용자 기준으로 정리합니다.

## 무엇을 하나

`sender`는 아래 작업을 담당한다.

- `encode`: 입력 파일을 QR 이미지 세트로 변환
- `slide`: QR 이미지 또는 일반 이미지 세트를 화면에 재생
- `unpack`: 패킹된 jar/zip의 `.txt` suffix를 제거

대부분은 `encode`와 `slide`만 알면 됩니다.

## 산출물

```bash
build/libs/sender-<version>.jar
```

## 기본 실행

기본 실행 방식:

```bash
java -jar build/libs/sender-<version>.jar
```

도움말:

```bash
java -jar build/libs/sender-<version>.jar --help
```

## 운영 메모

- `sender`는 단일 fat jar 기준으로 배포하는 편이 가장 단순합니다.
- 대부분의 경우 별도 JVM 옵션 없이 `java -jar ...`로 바로 실행하면 됩니다.
- 이 문서는 공개 명령만 다룹니다. 유지보수용 숨김 명령은 별도 사용자 문서로 다루지 않습니다.

## 자주 쓰는 작업

파일을 QR 이미지로 만들기:

```bash
java -jar build/libs/sender-<version>.jar encode \
  --in /path/in \
  --out /path/out \
  --project-name PROJECT
```

QR 이미지를 화면에 재생하기:

```bash
java -jar build/libs/sender-<version>.jar slide
```

패킹된 zip을 다시 복원하기:

```bash
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

## 먼저 보면 좋은 문서

- [`encode-decode.md`](encode-decode.md)
- [`slide-capture.md`](slide-capture.md)
- [`packager.md`](packager.md)

## 간단 확인

- `java -jar build/libs/sender-<version>.jar --help`
- `java -jar build/libs/sender-<version>.jar encode --help`
- `java -jar build/libs/sender-<version>.jar slide --help`
- `java -jar build/libs/sender-<version>.jar unpack --help`

주의:

- `slide` 실제 GUI 오픈 여부는 운영 환경에서 별도 수동 확인이 필요합니다.
