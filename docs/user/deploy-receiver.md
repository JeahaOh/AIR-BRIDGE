# Receiver Deployment

이 문서는 `receiver`를 실제로 어떻게 실행하면 되는지 사용자 기준으로 정리합니다.

## 무엇을 하나

`receiver`는 아래 작업을 담당한다.

- `decode`: QR 이미지 세트를 읽어 원본 파일 복원
- `capture`: UVC 카메라 입력에서 QR 프레임 수집
- `identify`: jar/zip 내부 확장자 목록 추출
- `pack`: jar/zip 대상 엔트리에 `.txt` suffix 추가

대부분은 `capture`와 `decode`만 알면 됩니다.

## 산출물

```bash
build/libs/receiver-<version>.jar
```

## 기본 실행

기본 실행 방식:

```bash
java -jar build/libs/receiver-<version>.jar
```

도움말:

```bash
java -jar build/libs/receiver-<version>.jar --help
```

## 운영 메모

`capture`나 대량 `decode`는 메모리 영향을 받을 수 있습니다.

필요하면 아래처럼 JVM 옵션을 추가해 실행합니다.

```bash
java \
  -Xms1g \
  -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -jar build/libs/receiver-<version>.jar
```

처음에는 기본 실행으로 시작하고, 실제 입력 규모나 캡처 환경에서 메모리 부족이 보일 때만 조정하는 편이 안전합니다.

## 자주 쓰는 작업

QR PNG를 원본 파일로 복원하기:

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/qr-images \
  --out /path/restore
```

카메라에서 QR을 수집하기:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out
```

장치 목록 보기:

```bash
java -jar build/libs/receiver-<version>.jar capture --list-devices
```

패키지 확장자 목록 만들기:

```bash
java -jar build/libs/receiver-<version>.jar identify --in /path/to/sender.jar
```

패키지를 zip으로 다시 만들기:

```bash
java -jar build/libs/receiver-<version>.jar pack --in /path/to/sender.jar
```

## 먼저 보면 좋은 문서

- [`encode-decode.md`](encode-decode.md)
- [`slide-capture.md`](slide-capture.md)
- [`packager.md`](packager.md)
- [`tuning.md`](tuning.md)

## 간단 확인

- `java -jar build/libs/receiver-<version>.jar --version`
- `java -jar build/libs/receiver-<version>.jar decode --help`
- `java -jar build/libs/receiver-<version>.jar capture --help`
- `java -jar build/libs/receiver-<version>.jar identify --help`
- `java -jar build/libs/receiver-<version>.jar pack --help`

주의:

- `capture`는 실제 카메라 접근이 필요하므로 운영 환경에서 별도 수동 스모크가 필요합니다.
