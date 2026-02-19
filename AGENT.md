# AGENT.md

## AirBridge 구현 스펙 + 세션 인수인계 문서 (2026-02-20)

이 문서는 다음 세션에서 바로 이어서 개발할 수 있도록, 현재 코드 기준 동작/의사결정/주의사항을 상세 기록합니다.

---

## 1) 프로젝트 목표

AirBridge는 에어갭 환경에서 파일을 영상 프레임으로 전달하는 Java 21 기반 단일 fat JAR CLI 도구입니다.

전체 플로우:
1. `encode`로 파일/폴더를 PNG 프레임 + MP4로 변환
2. `play`로 송신측에서 MP4 재생
3. 외부 캡처 도구(OBS)로 재생 화면 녹화
4. `decode`로 캡처 영상을 복원
5. 파일별 SHA-256 검증

설계 원칙:
- 자동 복구(FEC/interpolation) 없이, 손상은 감지/리포트
- 중간 산출물(workdir) 보존으로 디버깅 가능성 우선
- 실행 시작 추정치 + 완료 요약 + JSON report 제공

---

## 2) 빌드/런타임 상태

### 빌드
- Gradle + Shadow plugin 사용
- 현재 산출물 JAR: `build/libs/airbridge-0.5.0.jar`

### 버전 문자열 주의
- Gradle 프로젝트 버전: `0.5.0` (`build.gradle`)
- CLI `-V` 출력: `airbridge 1.0.0` (`src/main/java/com/airbridge/cli/Root.java` + `Main.VERSION`)
- 즉, JAR 파일명 버전과 CLI 버전 문구가 다름

---

## 3) 명령/옵션 요약

### encode
- 필수: `--in`
- 기본값:
  - `--out transfer.mp4`
  - `--profile 1080p`
  - `--chunk-size 32768`
  - `--fps 24`
  - `--work` 자동 (`<out-parent>/work`)

### decode
- 필수: `--in`
- 기본값:
  - `--out decoded-output`
  - `--work` 자동 (`<out>_work`)
  - `--report` 기본 (`<work>/decode_report.json`)

### play
- 필수: `--in`
- 기본값:
  - `--fps 24`

---

## 4) 경로 fallback 정책 (중요)

### encode output (`--out`)
요청 경로가 쓰기 불가면 순차 fallback:
1. requested
2. `cwd/<requested-filename>`
3. `tmp/airbridge-output-<uuid>.mp4`

적용 위치:
- `EncodePipeline.prepareOutputPath(...)`

### encode/decode work (`--work`)
요청 work 생성 실패 시 순차 fallback:
1. requested (`--work` 또는 기본값)
2. 기본값 (`encode`: `<out-parent>/work`, `decode`: `<out>_work`)
3. `cwd/work`
4. `tmp/airbridge-work-<uuid>`

적용 위치:
- `EncodePipeline.prepareWorkDir(...)`
- `DecodePipeline.prepareWorkDir(...)`

주의:
- `decode --out`은 별도 fallback 없음 (루트 생성 실패 시 즉시 에러)

---

## 5) ffmpeg 선택 정책

실행 시 `FfmpegRunner.create()`에서 결정:
1. 번들 ffmpeg 추출 시도 (`FfmpegLocator.extractBundledBinary()`)
2. 번들에 대해 `-version` probe
3. 실패 시 시스템 `ffmpeg` probe
4. 시스템 ffmpeg 사용 시 WARN 출력

번들 리소스 경로:
- Windows: `/ffmpeg/win-x64/ffmpeg.exe`
- macOS arm64: `/ffmpeg/mac-arm64/ffmpeg`

참고:
- Linux 번들 없음 (시스템 ffmpeg 필요)
- Homebrew Cellar 의존 동적 링크 바이너리를 번들로 넣으면 macOS에서 dylib 로드 실패 가능

---

## 6) 데이터 프레임/패킷 포맷

### FrameCodec
- RGB 셀 그리드로 nibble 단위 인코딩
- payload 앞 4바이트 length prefix 저장
- overlay 영역은 텍스트 표시용, decode 시 무시

### FramePacket
- MAGIC: `ABR2`
- VERSION: `1`
- 타입: META / DATA
- payload CRC32 검증

메타 프레임:
- manifest JSON을 shard로 분할하여 전송

데이터 프레임:
- 파일 chunk 단위 payload 전송

---

## 7) 인코드 파이프라인 상세

파일: `src/main/java/com/airbridge/pipeline/EncodePipeline.java`

실행 순서:
1. 입력 스캔 (`ManifestBuilder.scanFiles`)
2. 파일별 SHA-256 계산 + manifest 생성
3. manifest shard 생성
4. `[encode:estimate]` 출력
5. 샘플(최대 24프레임) 기반 MP4 크기/로컬 시간 추정 시도
6. 프레임 생성 (`encode:frames`)
7. ffmpeg mux (`encode:mux`)
8. `encode_report.json` 기록
9. `[encode:done]` 출력

검증 로직:
- `chunkSize + estimatedHeaderSize <= codec.maxPayloadBytes()` 아니면 즉시 실패

---

## 8) 디코드 파이프라인 상세

파일: `src/main/java/com/airbridge/pipeline/DecodePipeline.java`

실행 순서:
1. 입력 영상 probe(가능 시 duration/fps/resolution)
2. PNG 추출 (`decode:extract`)
3. 프레임 파싱 (`decode:parse`)
4. 세션 선택(메타 coverage 최댓값)
5. manifest 복구
6. chunk 재조합 (`decode:reassembly`)
7. 파일 해시 검증 (`decode:verify`)
8. `decode_report.json` 기록
9. `[decode:done]` 출력

복원 정책:
- 누락 chunk는 0-byte padding으로 채움
- 결과 상태는 파일별로 `OK` / `MISSING_CHUNKS` / `HASH_MISMATCH`

안전 처리:
- 상대경로 sanitize
- path traversal 차단 (`resolveOutputPath`)

---

## 9) 플레이어 동작 상세

파일: `src/main/java/com/airbridge/pipeline/PlayPipeline.java`

핵심 동작:
- 입력 MP4를 임시 PNG로 추출 후 프레임 재생
- 시작 시 전체화면 시도
  - 전체화면 미지원 환경: `MAXIMIZED_BOTH` fallback
- 종료 시 임시 프레임 best-effort 삭제

키맵(현재 기준):
- `Space`: pause/resume
- `S`: start/resume
- `P`: pause
- `R`: restart from first frame
- `Left/Right`: ±5s seek
- `Shift + Left/Right`: frame step (auto-pause)
- `+/-`: zoom in/out
- `0`: fit
- `1`: original size
- `F`: fit/original toggle
- `ESC`: 전체화면 해제만 수행 (플레이어는 유지)
- `Alt + Enter`: 전체화면 재진입
- `Q`: 플레이어 종료

HUD:
- 상태(PLAYING/PAUSED), frame index, scale, key hint 표시

---

## 10) 리포트 스키마 핵심 필드

### encode_report.json
- 경로 정보: `input`, `requestedOutput`, `output`, `workdir`, `manifest`, `framesDir`
- 실행 정보: `ffmpegBinary`, `elapsedMillis`, `warnings`
- 결과 정보: `totalFiles`, `totalBytes`, `totalChunks`, `totalFrames`, `outputBytes`
- 파라미터: `frameWidth`, `frameHeight`, `overlayHeight`, `cellSize`, `fps`, `chunkSize`

### decode_report.json
- 경로 정보: `input`, `output`, `workdir`
- 실행 정보: `ffmpegBinary`, `elapsedMillis`, `warnings`
- 추정치: `estimatedVideoDurationSeconds`, `estimatedVideoFps`, `estimatedVideoWidth`, `estimatedVideoHeight`, `estimatedTransferFrames`
- 파싱/복원 통계: `totalExtractedFrames`, `validFrames`, `invalidFrames`, `invalidDataPackets`, `expectedChunks`, `receivedChunks`, `missingChunks`
- 검증 결과: `hashMismatches`, `recoveredBytes`, `ok`, `files[]`

---

## 11) 자주 발생한 이슈와 현재 상태

### A. Read-only filesystem 에러 (`/work`, `/work-encode`, `/result.mp4`)
- 원인: 루트 경로(/) 쓰기 시도
- 현재 대응:
  - encode output fallback 구현됨
  - encode/decode work fallback 구현됨

### B. picocli `NoClassDefFoundError`
- 원인: 런타임 classpath 문제 가능
- 현재 대응:
  - `Main.main`에서 `NoClassDefFoundError` catch하여 원인 출력
  - fat JAR 실행 권장

### C. 번들 ffmpeg dylib 로드 실패 (macOS)
- 원인: Homebrew Cellar 동적 라이브러리 링크된 바이너리 번들 포함
- 현재 대응:
  - 번들 probe 실패 시 시스템 ffmpeg fallback
  - 문서에 standalone 바이너리 교체 가이드 반영

---

## 12) 코드 맵 (빠른 진입)

- 진입점: `src/main/java/com/airbridge/Main.java`
- CLI 루트: `src/main/java/com/airbridge/cli/Root.java`
- 커맨드:
  - `src/main/java/com/airbridge/cli/EncodeCommand.java`
  - `src/main/java/com/airbridge/cli/DecodeCommand.java`
  - `src/main/java/com/airbridge/cli/PlayCommand.java`
- 파이프라인:
  - `src/main/java/com/airbridge/pipeline/EncodePipeline.java`
  - `src/main/java/com/airbridge/pipeline/DecodePipeline.java`
  - `src/main/java/com/airbridge/pipeline/PlayPipeline.java`
- 코어 포맷:
  - `src/main/java/com/airbridge/frame/FrameCodec.java`
  - `src/main/java/com/airbridge/frame/FramePacket.java`
  - `src/main/java/com/airbridge/core/Manifest.java`
  - `src/main/java/com/airbridge/core/ManifestBuilder.java`
- ffmpeg:
  - `src/main/java/com/airbridge/ffmpeg/FfmpegLocator.java`
  - `src/main/java/com/airbridge/ffmpeg/FfmpegRunner.java`

---

## 13) 다음 세션에서 우선 점검할 TODO

1. 버전 문자열 통일
- `build.gradle` 버전과 CLI `-V` 출력 일치화

2. decode output fallback 여부 결정
- 현재는 work만 fallback, output root는 실패 시 종료

3. Play 성능 개선 검토
- 현재는 전체 프레임 PNG 추출 후 재생
- 대용량 영상에서 시작 지연/디스크 IO 비용 큼

4. 플랫폼 정책 문서 정리
- "No Linux support" 문구 vs 시스템 ffmpeg fallback 실제 동작 정합성 확정

5. 자동화 테스트 강화
- encode->decode roundtrip 무결성 케이스
- 경로 fallback(read-only 상황) 케이스
- player key binding 최소 smoke test

---

## 14) 검증 명령 (회귀 확인용)

```bash
gradle build
java -jar build/libs/airbridge-0.5.0.jar --help
java -jar build/libs/airbridge-0.5.0.jar encode --help
java -jar build/libs/airbridge-0.5.0.jar decode --help
java -jar build/libs/airbridge-0.5.0.jar play --help
```

샘플 왕복 테스트:
```bash
java -jar build/libs/airbridge-0.5.0.jar encode --in sample --profile 1080p --chunk-size 32768 --fps 24
java -jar build/libs/airbridge-0.5.0.jar decode --in transfer.mp4
```

---

## 15) 최근 반영 사항 요약 (이번 세션 포함)

- play 키맵 개선:
  - 5초 seek, 프레임 step, zoom, fit/original
  - `ESC` = 전체화면 해제만
  - `Alt+Enter` = 전체화면 재진입
  - `Q` = 종료
- 플레이어 fullscreen/window 전환 안정화
- 경로 fallback 정책 강화 (read-only 대응)
- encode/decode 시작 추정치 및 완료 소요시간 출력
- README/AGENT 문서 동기화 및 상세화

