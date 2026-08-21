# air-bridge sender/receiver 개선 TODO

작성 2026-08-21. 근거는 2026-08-20~21 실전 전송 2회차(s2b_admin-main.zip, k=71,841,
gzipLen=143,681,918)에서 나온 실측이다. 추정과 실측을 구분해서 표기했다.

## 실측 기준선 (이 수치와 비교해서 개선 여부를 판단할 것)

capture-manifest.json (2026-08-21 14:41 ~ 17:28, interrupted-signal):

| 항목 | 값 | 비고 |
|---|---|---|
| 요청 fps / 실제 fps | 60.0 / **46.7** | totalFrames 469,312 / 10,057초 |
| 페이지 실측 주기 | **109~110ms** | 설정 80+10=90ms 대비 21% 초과 |
| page / black 실측 분해 | 83ms / **32ms** | black 설정 10ms의 3.2배 |
| 캡처율 | **94.9%** | esi 표본 7점 전 구간 균일 (오차 0.1%p) |
| decode 실패율 | 18.4% | 24,274 / 130,277 제출 |
| decode CPU | 0.39 코어 | decodeMillis 3,921,863 / 10,057초 |
| save CPU | 0.29 코어 | saveMillis 2,918,309 / 10,057초 |
| 파이프라인 백프레셔 | 없음 | rawQueueHW=1/64, saveQueueHW=3/128, dropped=186 |
| LT peeling | 1.149k에서 **실패** | 82,562심볼 / k=71,841, LtPeelTracker 판정 |

측정 방법(재현용): PNG mtime으로 속도, jshell로 PNG를 디코드해 `esi`를 읽어 캡처율 산출.
`frame_N`과 `esi`가 기울기 1.0534(=1/0.949)의 직선이면 슬라이드 순서 = esi 순서이고
기울기 역수가 캡처율이다.

---

## P0 — 다음 전송 전에 반드시

### 1. [slide] black frame 실측 32ms (설정 10ms)
- 근거: `blackFramesSkipped`=131,393 / 표시 86,968페이지 = 1.51프레임/페이지 x 21.4ms
- 원인 추정: Swing `Timer`가 10ms를 못 맞춤(통상 하한 15~16ms) + `showBlackFrame()`이
  매번 `setStatusText` 호출 (`SlideApp.java:581-584`)
- 영향: 전송 시간 21% 증가. **1패스가 완주되지 못한 직접 원인.**
  107,762장 x 109ms = 3시간 16분이 필요했는데 2시간 47분만 돌았다.
- 조치: 정밀 스케줄링으로 교체하거나, 하한을 실측해서 `MIN_BLACK_FRAME_MS`(현재 1ms,
  `SlideDefaults.java:26`)를 현실값으로 올리고 문서화. 지금은 1ms까지 설정 가능한데
  실제로는 32ms가 나오므로 설정값이 거짓말을 한다.

### 2. [capture] decode strategy 과도하게 좁음
- 근거: decode 실패 18.4%, 그중 페이지 5.1%는 **결정론적 실패**(재생해도 또 실패).
  프리즈 구간에서 중복 1,000장 재생 시 신규 0개 = 놓친 페이지는 다시 봐도 못 읽는다.
- 여력 있음: decode CPU 0.39코어, `droppedNoDecodeCapacity`=186(거의 0)
- 조치: `CaptureQrDecodeSupport.java:29-36`에서 제거한 upscale/grid crop을 일부 복구.
  2026-08-20에 15후보 -> 2후보로 줄였는데, 실측상 예산이 남으므로 4~6후보로 되돌린다.
- 기대: 캡처율 94.9% -> 98%+ 및 결정론적 유실 제거

### 3. [sender] repair-overhead 0.5로는 peeling 부족
- 근거: k=71,841에서 82,562심볼(**1.149k**) 확보했는데 `LtPeelTracker` 판정 복원 불가
- 원인: Ideal Soliton 분포는 peeling stall이 잦다(그래서 Robust Soliton이 존재).
  k가 클수록 오버헤드 요구가 커진다.
- 조치(단기): `SenderDefaults.DEFAULT_REPAIR_OVERHEAD` 0.5 -> 0.8 검토.
  프레임은 20% 늘지만 **2패스를 도는 것보다 1패스 완주가 훨씬 빠르다**(오늘 2패스 비용
  = 슬라이드 전체 재생 3시간 16분).
- 조치(근본): 아래 P2 #9 참조

---

## P1 — 데이터 손실/운영 사고 방지

### 4. [capture] 비어있지 않은 폴더에 캡처하면 조용히 덮어씀
- 근거: 2026-08-21 실전 사고. `--resume` 없이 기존 25,527장이 있는 폴더에 캡처를 시작해
  `frame_000001.png`부터 전날 파일을 덮어썼다. 경고도 로그도 없었다.
- 조치: 시작 시 출력 폴더에 `frame_*.png`가 있으면 (a) 거부하고 `--resume` 또는 빈 폴더를
  안내, 또는 (b) 기존 최대 번호 다음부터 번호를 매긴다. 조용한 덮어쓰기는 금지.

### 5. [capture] `--resume-index`가 opt-in
- 근거: `Receiver.java:253` 기본 false. 인덱스 없이 `--resume`하면 저장된 PNG를 전량
  재디코딩한다. 실측 30ms/장이므로 82,562장 = **41분**.
- 문제의 본질: 긴 전송이 끊겼을 때 정작 필요한 게 resume인데, 그때 resume이 가장 비싸다.
- 조치: 기본 켜기. 텍스트 한 줄(`name \t base64(payload)`) 추가 비용은 무시할 수준이고
  (`CaptureResumeIndex.java:63-66`), 얻는 건 41분 -> 수초.

### 6. [slide] 재생 위치를 알 수 없고, 재시작하면 처음으로 돌아감
- 근거: 2026-08-21 실전 사고. 1패스가 esi 86,968(80.7%)에서 진행 중인데 끝난 줄 알고
  재시작해 esi 0으로 리셋. 2시간 38분의 진행 위치를 잃었다.
- 조치: (a) 현재 인덱스/전체를 재생 중에도 항상 크게 표시,
  (b) 재생 완료와 "아직 진행 중"을 명확히 구분,
  (c) 마지막 위치 저장 후 재시작 시 이어서 재생 옵션.
  트리 클릭 점프는 이미 동작한다(`SlideApp.java:586-615`) — 발견이 어려운 게 문제다.

### 7. [sender] 대용량 파일 분할 (`--split-size`)
- 근거: `reencode`는 실패한 파일의 **심볼 스트림 전체**를 재발행한다(청크 단위 없음).
  137MB 파일이 2% 부족하면 3시간 16분을 다시 보내야 한다.
- 하드 한계는 아님: k(u32), gzipLen(int 2GiB 가드 `FileEncodingPlan.java:102-104`),
  PNG/디렉터리(500개 분할) 전부 여유. 메모리도 완료 파일은 해제됨(`DecodeService.java:162`).
  즉 **분할의 이유는 한계가 아니라 재시도 단위**다.
- 임시 대응(코드 0): 보내기 전 `split -b 16m`, 받은 뒤 `cat part_* >`. 지금 바로 가능.
- 정식 기능: sender에 `--split-size` + 조각 목록/순서/원본 전체 해시 매니페스트,
  receiver에 자동 재결합 + 해시 검증 + 누락 조각 보고. **QR 프레임 포맷은 안 건드린다**
  (조각은 그냥 파일이므로 `QrPayloadSupport`에 필드 추가 없음).

### 8. [sender] Picocli defaultValue가 필드 초기화값을 덮어씀  — 편집 완료, 검증 대기
- 근거: `defaultValue` 문자열 리터럴이 우선하므로 CLI과 GUI가 다른 값으로 인코딩했다.
  전수 감사 16곳 중 실제 불일치 2곳: `--chunk-data-size`(2000 vs 2600),
  `--qr-error-level`(M vs L). `ef1e1ba`의 "chunk 2600, ECC L"은 CLI에 적용된 적이 없다.
- 상태: 워킹트리에 수정 완료(중복 리터럴 제거, 상수를 정본으로). 회귀 방지 테스트 추가.
  **아직 컴파일/테스트 안 했다.** `./gradlew test` 필요.
- 남긴 것: `--folder-structure`는 `negatable=true` + `${DEFAULT-VALUE}` 조합이고 값이
  이미 일치하므로 손대지 않았다. `sender encode --help` 확인 후 정리.

---

## P2 — 구조 개선

### 9. [common] Ideal Soliton -> Robust Soliton 검토
- 근거: #3의 근본 원인. Ideal Soliton은 이론상 기대 degree는 맞지만 분산이 커서
  peeling이 stall한다. 오늘 1.149k 실패가 그 사례.
- 제약: `AGENTS.md`가 이웃 생성의 크로스플랫폼 결정성을 요구하고 `Math.log`/`sqrt` 금지.
  Robust Soliton은 log가 필요하므로 **고정소수점 테이블 방식**으로 결정성을 유지해야 한다.
- 호환성: 프레임 포맷은 안 바뀌지만 **어느 esi가 어느 소스 심볼을 XOR하는지가 바뀐다**.
  sender/receiver를 반드시 같은 버전으로 맞춰야 하고, 구버전 encoded 세트는 신버전
  receiver로 복원 불가. 버전 협상 수단이 프레임에 없으므로(설계상 없음) 운영 절차로 관리.
- 기대: 오버헤드 요구가 1.4k -> 1.1k 수준으로 내려가면 전송 시간 20%+ 단축

### 10. [capture] dwell 권장값 계산식이 순환 논리
- 근거: `CaptureService.java:629-634` `ceil(elapsed/unique * 1.3)`.
  달성 간격은 sender의 페이지 주기보다 짧아질 수 없으므로 **100% 캡처해도 항상
  현재 주기의 1.3배로 늦추라고 말한다.** 현재 설정을 승인하거나 더 빠르게 하라고 말할 수
  없는 구조다. 게다가 msPerUnique는 page+black 전체 주기인데 권장 대상은 page만이다.
- 조치: 실제 fps와 `decodedFrames/uniquePayloads`(페이지당 시도 횟수)로 "놓친 비율"을
  추정해서 권장하도록 재작성. 아니면 삭제하고 원시 지표만 노출.

### 11. [capture] PNG 대신 심볼 로그 (200MB급 처리량 항목)
- 근거: 프레임당 ~3MB PNG를 쓰는데 payload는 ~2.6KB다. `CaptureResumeIndex`가 이미
  `name \t base64(payload)` 형식이라 3/4은 만들어져 있다.
- 조치: `captured-symbols.log`를 1급 입력으로 승격, `decode`가 PNG 폴더 대신/과 함께
  받도록. PNG 저장은 `--save-frames` opt-in.
- 주의: 경로 안전성(`RelativePathSupport`) 유지, 잘린 마지막 줄 허용, 기존 PNG 경로 유지.

### 12. [receiver] decode 입력으로 여러 폴더 허용
- 근거: 오늘 1패스와 꼬리 캡처가 별도 폴더에 있고 양쪽 파일명이 모두 `frame_000001.png`
  부터라 수동 병합이 필요했다. 심볼은 relPath로 묶이므로 파일명은 무관한데, 폴더를
  하나만 받는 제약 때문에 사람이 복사/개명을 해야 한다.
- 조치: `--in`을 복수 지정 가능하게 하거나 `--in-dirs` 추가.

---

## 반증된 가설 (다시 쫓지 말 것)

- **"`BLACK_FRAME_LUMA_THRESHOLD=8`이 실제 카메라에서 발화하지 않는다"** — 반증됨.
  `blackFramesSkipped`=131,393(전체 프레임의 28%). black frame 검출은 정상 동작한다.
  슬라이드 컨트롤(`COLOR_PANEL(18,18,18)`)을 숨길 필요도 없었다.
- **"`DEFAULT_FPS=15`가 Nyquist 제약"** — grab 루프에 pacing/sleep이 없고 `setFrameRate`는
  드라이버 best-effort다. 실제로 47fps 나온다. fps는 더 이상 병목이 아니다.
- **"캡처율이 78%다"** — 실측 94.9%. 78%는 슬라이드가 도달하지도 못한 구간을 손실로
  오독한 값이었다. 캡처율 판정은 반드시 esi 표본으로 하라.
- **"`db48671`이 성능 회귀의 원인"** — 오히려 fix다. `ef1e1ba`가 넣은 grab단 dedupe가
  기존 analyze단 게이트와 논리적으로 상호배타였다.
- **"decode 경로에 O(n^2)가 있다"** — 없음. `DecodeService`/`LtDecoder` 모두 amortized.

## 완료된 것

- [x] decode 제출 drop-on-full (`tryAcquire` + 커스텀 rejection handler). 실측 백프레셔 0
- [x] retry-until-decoded 분석 루프 (성공한 화면만 억제, 실패는 다음 프레임 재시도)
- [x] `DEFAULT_FPS` 15 -> 30, CLI 기본값 동기화
- [x] 손실 계측 노출: `framesDroppedNoDecodeCapacity`, raw/saveQueueCapacity
- [x] Ctrl+C에도 manifest 기록(셧다운 훅, 멱등 쓰기, `completed` 필드, resume index 플러시)
      — 2026-08-21 실전에서 `stopReason: interrupted-signal`로 정상 동작 확인
