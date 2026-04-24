# Tuning

이 문서는 기본 사용이 아니라, 성능이나 안정성 때문에 추가 조정이 필요할 때만 참고하는 문서입니다.

기본 원칙:

- 먼저 기본 실행으로 확인한다.
- 문제가 있을 때만 옵션을 추가한다.
- `capture`나 대량 `decode`처럼 메모리 사용량이 큰 작업에서만 JVM 옵션을 우선 검토한다.

## receiver JVM 옵션

`receiver`는 `capture`와 대량 `decode`에서 메모리 영향을 더 크게 받을 수 있습니다.

예:

```bash
java \
  -Xms1g \
  -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -jar build/libs/receiver-<version>.jar decode \
  --in /path/qr-images \
  --out /path/restore
```

같은 방식으로 `capture`에도 붙일 수 있습니다.

```bash
java \
  -Xms1g \
  -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -XX:InitiatingHeapOccupancyPercent=30 \
  -jar build/libs/receiver-<version>.jar capture \
  --out /path/capture-out
```

## 언제 조정하나

아래 상황이면 JVM 튜닝을 검토합니다.

- `decode` 중 메모리 부족이 나는 경우
- `capture`가 오래 돌면서 느려지거나 불안정한 경우
- 대량 QR PNG를 한 번에 처리하는 경우

## slide / capture 속도 조정

`slide`와 `capture`를 함께 쓸 때는 JVM 옵션보다 재생/수집 속도 조정이 먼저일 수 있습니다.

관련 문서:

- `slide-capture.md`

## 개발자용 성능 메모

더 자세한 내부 성능 메모는 개발용 문서를 참고합니다.

- `../dev/faster.md`
