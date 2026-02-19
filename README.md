# AirBridge

AirBridge는 에어갭(air-gapped) 환경에서 파일/폴더를 영상 프레임으로 전송하기 위한 Java 21 CLI 도구입니다.

현재 구현 기준:
- `encode`: 입력 파일(들) -> PNG 프레임 -> MP4
- `play`: MP4 재생(키보드 제어 포함)
- `decode`: 캡처 MP4 -> PNG 프레임 -> 원본 파일 복원 + SHA-256 검증

---

## 1) 전송 흐름

1. 송신 PC에서 `encode`
2. 송신 PC에서 `play`로 MP4 재생
3. 수신 PC에서 OBS 등으로 화면 캡처
4. 수신 PC에서 `decode`
5. 파일별 무결성 결과(`OK`, `MISSING_CHUNKS`, `HASH_MISMATCH`) 확인

---

## 2) 요구사항

- Java 21
- Gradle
- FFmpeg
  - 번들 ffmpeg 우선 시도
  - 번들 실행 실패/미탑재 시 시스템 `ffmpeg` fallback

참고:
- 번들 경로는 `win-x64`, `mac-arm64`만 포함되어 있습니다.
- Linux는 번들 ffmpeg가 없으므로 시스템 `ffmpeg`가 필요합니다.

---

## 3) 빌드

```bash
gradle build
```

산출물:
- `build/libs/airbridge-0.5.0.jar`

실행 예시에서 버전 번호가 바뀔 수 있으니 필요하면 아래처럼 변수로 실행하세요.

```bash
JAR=$(ls build/libs/airbridge-*.jar | head -n 1)
java -jar "$JAR" --help
```

---

## 4) 빠른 시작

### 4-1. Encode

```bash
java -jar build/libs/airbridge-0.5.0.jar encode --in /path/to/input
```

### 4-2. Play

```bash
java -jar build/libs/airbridge-0.5.0.jar play --in ./transfer.mp4
```

### 4-3. Decode

```bash
java -jar build/libs/airbridge-0.5.0.jar decode --in /path/to/captured.mp4
```

---

## 5) CLI 기본값/옵션

### Encode

```bash
java -jar build/libs/airbridge-0.5.0.jar encode \
  --in /path/to/input \
  [--out /path/to/transfer.mp4] \
  [--work /path/to/work] \
  [--profile 1080p|4k] \
  [--chunk-size 32768] \
  [--fps 24]
```

기본값:
- `--out`: `transfer.mp4`
- `--profile`: `1080p`
- `--chunk-size`: `32768`
- `--fps`: `24`
- `--work`: 자동 계산 (`<out-parent>/work`)

### Decode

```bash
java -jar build/libs/airbridge-0.5.0.jar decode \
  --in /path/to/captured.mp4 \
  [--out /path/to/decoded-output] \
  [--work /path/to/decode-work] \
  [--report /path/to/decode_report.json]
```

기본값:
- `--out`: `decoded-output`
- `--work`: `<out>_work`
- `--report`: `<work>/decode_report.json`

### Play

```bash
java -jar build/libs/airbridge-0.5.0.jar play \
  --in /path/to/transfer.mp4 \
  [--fps 24]
```

기본값:
- `--fps`: `24`

---

## 6) 경로 처리/자동 fallback 정책

### Encode `--out`

요청한 출력 경로가 쓰기 불가하면 아래 순서로 fallback:
1. 요청한 `--out`
2. 현재 실행 디렉터리의 같은 파일명
3. 시스템 임시 디렉터리의 `airbridge-output-<uuid>.mp4`

실제 fallback 발생 시 `WARN` 로그를 출력합니다.

### Encode/Decode `--work`

요청한 work 경로 생성 실패 시 아래 순서로 fallback:
1. 요청한 `--work` (없으면 기본값)
2. 기본 work 경로 (`encode`: `<out-parent>/work`, `decode`: `<out>_work`)
3. 현재 실행 디렉터리의 `work`
4. 시스템 임시 디렉터리의 `airbridge-work-<uuid>`

실제 fallback 발생 시 `WARN` 로그를 출력합니다.

주의:
- `decode --out` 자체는 별도 fallback 로직이 없습니다. 출력 루트 생성 실패 시 에러로 종료됩니다.

---

## 7) Player 키맵 (현재 구현)

- `Space`: 일시정지/재개 토글
- `S`: 재생/재개
- `P`: 일시정지
- `R`: 처음 프레임으로 재시작
- `←/→`: 5초 단위 seek
- `Shift + ←/→`: 프레임 단위 이동(자동 일시정지)
- `+/-`: 확대/축소
- `0`: FIT 모드
- `1`: 100%(원본 크기)
- `F`: FIT/원본 토글
- `ESC`: 전체화면 해제(윈도우 모드 유지)
- `Alt + Enter`: 전체화면 재진입
- `Q`: 플레이어 종료

렌더링 기본 모드:
- FIT

참고:
- `play`는 내부적으로 입력 MP4를 임시 PNG 시퀀스로 추출한 뒤 재생합니다.
- 재생 종료 시 임시 프레임은 삭제됩니다.

---

## 8) 진행 로그와 예상치/완료 로그

### Encode

진행 단계:
- `encode:hash`
- `encode:frames`
- `encode:mux`

시작 시 예상 로그:
- 프레임 수(메타/데이터 분리)
- 예상 재생 시간
- 샘플링 기반 MP4 크기 추정(range)
- 샘플링 기반 로컬 처리시간 추정(frame-gen + mux)

완료 로그:
- `[encode:done] elapsed=..., output=..., size=..., frames=...`

### Decode

진행 단계:
- `decode:extract`
- `decode:parse`
- `decode:reassembly`
- `decode:verify`

시작 시 예상 로그:
- 입력 영상 크기
- 영상 해상도/fps/재생시간(프로브 가능 시)

메타데이터 복구 후:
- 예상 복원 크기/파일 수/청크 수

완료 로그:
- `[decode:done] elapsed=..., output=..., recovered=..., files=..., ok=...`

---

## 9) 리포트 파일

### Encode report (`<work>/encode_report.json`)

주요 필드:
- 입력/출력/work/manifest/frames 경로
- ffmpeg 바이너리 경로
- 파일/바이트/청크/프레임 통계
- 프레임 파라미터(`frameWidth`, `frameHeight`, `overlayHeight`, `cellSize`)
- 전송 파라미터(`fps`, `chunkSize`)
- `elapsedMillis`, `outputBytes`
- `warnings`

### Decode report (`<work>/decode_report.json` 또는 `--report`)

주요 필드:
- 입력/출력/work 경로
- ffmpeg 바이너리
- 입력 영상 크기
- 영상 추정 정보(`estimatedVideoDurationSeconds`, `estimatedVideoFps`, `estimatedVideoWidth`, `estimatedVideoHeight`, `estimatedTransferFrames`)
- 프레임 파싱 통계(`totalExtractedFrames`, `validFrames`, `invalidFrames`, `invalidDataPackets`)
- 청크 통계(`expectedChunks`, `receivedChunks`, `missingChunks`)
- 검증 결과(`hashMismatches`, `ok`)
- `recoveredBytes`, `elapsedMillis`
- 파일별 상태(`files[].status`)
- `warnings`

---

## 10) work 디렉터리 산출물

Encode work:
- `manifest.json`
- `encode_report.json`
- `frames/frame_000001.png` ...

Decode work:
- `decoded_frames/frame_000001.png` ...
- `decode_report.json` (또는 `--report` 지정 경로)

주의:
- encode/decode의 PNG 프레임은 자동 삭제하지 않습니다.
- play의 임시 PNG는 자동 삭제됩니다.

---

## 11) 튜닝 가이드 (`chunk-size`, `fps`, `profile`)

핵심 트레이드오프:
- `chunk-size` 증가 -> 총 프레임 수 감소 -> 전송 시간 단축 가능
- 반대로 프레임 손실 1장의 피해 데이터량 증가 -> 복원 실패 위험 증가

권장 시작점:
1. 안정형: `--profile 1080p --chunk-size 16384 --fps 24`
2. 균형형: `--profile 1080p --chunk-size 32768 --fps 24`
3. 고속형: `--profile 1080p --chunk-size 44000 --fps 30`

실무 팁:
- 송신 재생 해상도, OBS 캡처 해상도, decode 입력 해상도를 가능한 동일하게 유지
- 캡처 중 리사이즈/스케일링/프레임 드랍이 생기면 `HASH_MISMATCH`/`MISSING_CHUNKS`가 증가

---

## 12) 자주 겪는 문제와 대응

### 12-1. Read-only file system (`/work`, `/work-encode`, `/result.mp4`)

원인:
- 루트(`/`) 같은 쓰기 불가 위치로 출력/작업 경로가 잡힘

대응:
- 기본값 사용 시 현재 경로 기준으로 생성되도록 구현되어 있음
- 필요 시 `--out`, `--work`를 명시적으로 사용자 홈/프로젝트 하위로 지정

### 12-2. `NoClassDefFoundError` (picocli 내부 클래스)

원인:
- 클래스패스/혼합 실행 문제 가능성

대응:
- fat JAR로 실행: `java -jar build/libs/airbridge-0.5.0.jar ...`

### 12-3. macOS에서 ffmpeg dylib 로드 실패

원인:
- 번들 ffmpeg가 Homebrew Cellar 동적 라이브러리에 링크됨

대응:
- standalone 정적/독립 실행 가능한 ffmpeg로 교체
- 번들 실행 불가 시 시스템 `ffmpeg` fallback 확인

---

## 13) macOS arm64 번들 ffmpeg 교체 체크리스트

1. `src/main/resources/ffmpeg/mac-arm64/ffmpeg` 교체
2. `chmod +x src/main/resources/ffmpeg/mac-arm64/ffmpeg`
3. `otool -L src/main/resources/ffmpeg/mac-arm64/ffmpeg` 확인
4. `/opt/homebrew/Cellar/...` 의존성 없어야 함
5. `gradle build` 후 encode 1회 테스트

---

## 14) 개발자 참고 (코드 진입점)

- CLI 엔트리: `src/main/java/com/airbridge/Main.java`
- 명령 파싱: `src/main/java/com/airbridge/cli/Root.java`
- Encode: `src/main/java/com/airbridge/pipeline/EncodePipeline.java`
- Decode: `src/main/java/com/airbridge/pipeline/DecodePipeline.java`
- Play: `src/main/java/com/airbridge/pipeline/PlayPipeline.java`
- 프레임 인코딩/디코딩: `src/main/java/com/airbridge/frame/FrameCodec.java`
- 프레임 패킷 포맷: `src/main/java/com/airbridge/frame/FramePacket.java`
- ffmpeg 선택/실행: `src/main/java/com/airbridge/ffmpeg/FfmpegLocator.java`, `src/main/java/com/airbridge/ffmpeg/FfmpegRunner.java`

---

## 15) 현재 `.gitignore` 반영 기본 실행 산출물

- `/transfer.mp4`
- `/decoded-output/`
- `/decoded-output_work/`
- `/work/`

