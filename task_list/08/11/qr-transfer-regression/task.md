# QR 전송 회귀 진단

- 요청 버전: `0.95.260803.1645`
- 기준 버전: 사용자가 제시한 이전 동작(`80ms + 10ms`, 인식률 약 98%)
- 범위: `encode` QR 생성 안정성, `slide` 표시 페이싱, `capture` 인식률/중복 제거, `decode` 복원 호환성
- 원칙: 기존 미커밋 변경을 보존하고, 우선 읽기 전용 원인 분석과 재현 증거를 수집한다. 코드 수정은 별도 지시 없이는 하지 않는다.

## 작업

- [x] 버전/커밋/배포 산출물 식별
- [x] 이전 대비 encode 변경점과 QR payload/PNG 생성 경로 검토
- [x] slide 표시 시간과 capture grab/analyze/decode 페이싱 검토
- [x] 자동 테스트 및 가능한 로컬 회귀 하니스 실행
- [x] 원인 후보를 증거 수준별로 분류하고 수정 우선순위 보고

## 검수 기준

- [x] source/runtime/DB와 같은 증거 경계를 적용해 encode와 capture 원인을 분리
- [x] 테스트 미실행 또는 하드웨어 미검증 영역을 명시
- [x] 현재 미커밋 변경을 결과에 포함하되 덮어쓰지 않음

## 검수 결과 요약

### 확인된 원인

1. `receiver-0.95.260803.1645.jar`는 2026-08-03 16:46 산출물이다.
   캡처 안정화 게이트 수정 `db48671`은 16:53 커밋이므로 1645에는 반영되지 않았다.
2. 1645 캡처 코드에는 grab loop의 `lastGrabbedFingerprint` 중복 프리필터가 있었고,
   analyze loop가 안정화를 위해 요구하는 두 번째 동일 프레임까지 앞단에서 버릴 수 있었다.
   이 경로는 `db48671`에서 제거됐다.
3. 0.95 계열은 0.9.6 대비 QR 오류정정을 `M -> L`, 데이터 청크를 `2000 -> 2600`으로
   바꿨다. QR 장수는 줄지만 카메라/리사이즈/초점 여유가 줄어 인식률 저하 위험이 직접 생긴다.
4. capture 전용 decoder는 `tryHarder=false`라서 일반 힌트 1회만 사용한다. 0.9.6의
   `tryHarder` 기본 동작과 비교하면 noisy frame에서 복원 여유가 줄었다.
5. sender 기본 worker 수는 CPU 코어 수이며, 1645 JAR의 GUI/AWT 모드 CLI encode는
   이 환경에서 SIGABRT(134)로 종료됐다. `-Djava.awt.headless=true`에서는 정상 생성됐다.
   따라서 이 현상은 QR payload 버그와 분리된 실행 안정성 후보로 기록한다.

### 검증 결과

- 1645 sender/receiver clean PNG round-trip: 102,400-byte 파일 해시 일치.
- 0.95.260731.1508 sender/receiver clean PNG round-trip: 해시 일치.
- 두 산출물 모두 78 QR 생성, 복원 성공 1개였고 QR 읽기/복원 오류 4건은
  복원 완료 후 잉여 QR을 삭제하는 과정에서 발생한 clean-file 재시도 결과다.
- `:common:test`, `:slide:test`, `EncodeServiceTest`, `CaptureOptionsTest`,
  `CaptureServiceInternalTest`: headless 조건에서 통과.
- 기본 조건의 `:sender:test`: SIGABRT(134)로 실패. 실카메라/실제 UVC 광학 조건은 검증하지 못했다.

### 결론

- 인식률 저하의 가장 확실한 버전 원인은 1645에 포함된 캡처 안정화 프리필터다.
- 인코딩 안정성/인식률 저하의 설계상 원인은 `ECC L + chunk 2600 + capture tryHarder=false` 조합이다.
- 단순 clean PNG round-trip만으로는 광학 채널 인식률 98%를 입증할 수 없다.
- 수정 우선순위는 1) 1645 폐기 및 `db48671` 이후 receiver 재배포, 2) M/2000 baseline A/B,
  3) capture `tryHarder` 및 worker/pacing 조합의 실카메라 측정이다.

## 최신 산출물

- 빌드 버전: `0.95.260811.1048`
- `./gradlew assemble`: 성공
- 전체 `./gradlew build`: JAR 생성 후 `capture:test` SIGABRT(134)로 실패
- sender: `build/libs/sender-0.95.260811.1048.jar`
- receiver: `build/libs/receiver-0.95.260811.1048.jar`
