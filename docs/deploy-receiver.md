# Receiver Deployment

이 문서는 `receiver` 배포와 실행 방식을 현재 코드 기준으로 정리한다.

## 용도

`receiver`는 아래 작업을 담당한다.

- `decode`: QR 이미지 세트를 읽어 원본 파일 복원
- `capture`: UVC 카메라 입력에서 QR 프레임 수집
- `identify`: jar/zip 내부 확장자 목록 추출
- `pack`: jar/zip 대상 엔트리에 `.txt` suffix 추가

## 권장 배포 방식

기본 권장:

- `java -jar build/libs/receiver-<version>.jar`

이유:

- [`apps/receiver/build.gradle`](../apps/receiver/build.gradle) 에 기본 JVM 옵션이 이미 정의돼 있다.
- `capture`와 대량 `decode`는 메모리 영향이 커서, 운영에서는 아래 옵션을 추가하는 편이 안전하다.

권장 JVM 옵션:

- `-Xms1g`
- `-Xmx4g`
- `-XX:+UseG1GC`
- `-XX:MaxGCPauseMillis=200`
- `-XX:+ParallelRefProcEnabled`
- `-XX:+UseStringDeduplication`
- `-XX:InitiatingHeapOccupancyPercent=30`

## 산출물

```bash
build/libs/receiver-<version>.jar
```

## 권장 실행 예시

도움말:

```bash
java -jar build/libs/receiver-<version>.jar --help
```

복원:

```bash
java -jar build/libs/receiver-<version>.jar decode \
  --in /path/qr-images \
  --out /path/restore
```

캡처:

```bash
java -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out
```

identify:

```bash
java -jar build/libs/receiver-<version>.jar identify --in /path/to/sender.jar
```

pack:

```bash
java -jar build/libs/receiver-<version>.jar pack --in /path/to/sender.jar
```

## 배포 전 스모크 체크

- `java -jar build/libs/receiver-<version>.jar --version`
- `java -jar build/libs/receiver-<version>.jar decode --help`
- `java -jar build/libs/receiver-<version>.jar capture --help`
- `java -jar build/libs/receiver-<version>.jar identify --help`
- `java -jar build/libs/receiver-<version>.jar pack --help`

주의:

- `capture`는 실제 카메라 접근이 필요하므로 운영 환경에서 별도 수동 스모크가 필요하다.
