# packager 강화 기록 (2026-07-04)

`identify`/`pack`/`unpack`에 대해 적대적 코드리뷰(감사 → diff 리뷰 → 수정본 회귀
검증, 3라운드)로 발굴한 결함을 수정한 내역입니다. 동작 계약과 시나리오는
`packager.md`에, 각 항목의 회귀 테스트는 `PackagerAppTest.java`에 있습니다.

## 요약

- 조용한 데이터 손실 경로 다수 제거(빈 zip 교체, rename 충돌 드롭, in-place wipe 등).
- Windows/크로스플랫폼 크래시 제거(엔트리 이름을 `Path`로 파싱하지 않음).
- CLI를 스크립트 친화적으로 정비(exit code, 한 줄 에러).
- 문서를 실제 코드와 일치시키고, 회귀 테스트 35→43 케이스로 확장.

## 신규 파일

- `EntryNames.java` — zip 엔트리 이름 전용 문자열 헬퍼(`lastSegment`,
  `isExtensionless`, `extensionOf`, `hasLineBreak`, `startsWithZipMagic`,
  `isEmptyZip`). 엔트리 이름을 절대 `java.nio.file.Path`로 넘기지 않기 위한 것.
- `PackagerCli.java` — 세 커맨드 공통 입력 검증 + 한 줄 에러 메시지 변환.

## 데이터 손실 / 정확성

| 심각도 | 문제 | 수정 |
|---|---|---|
| high | 이름만 `.jar`/`.zip`인 비-zip 엔트리가 빈 zip(22B)으로 교체됨 | 중첩 재귀 전에 zip 매직 `PK\x03\x04` 검사, 아니면 원본 그대로 복사 + WARN (`PackagerRewriter`, `PackagerInspector`) |
| high | pack rename 충돌(`a.cfg` + 진짜 `a.cfg.txt`)에서 한쪽 엔트리 무음 드롭 + rename 목록 오염 | 충돌·예약이름·CR/LF 시 rename 억제, 원래 이름 유지 + WARN |
| high | 루트에 원래부터 `target`/`target.txt`/`target-ext.txt`인 엔트리가 메타 슬롯 탈취/파괴 | 예약 이름 충돌 시 rename 억제, 원본 엔트리 이름 그대로 두고 WARN; 입력의 메타 이름 엔트리는 WARN 후 제거 |
| medium | unpack shape-compat un-rename이 현재 포맷 패키지에서 오발동 → 진짜 `a.jar.txt`가 real `a.jar`와 충돌해 드롭 | 3중 게이트로 제한: (a) 실제 zip, (b) 목록에 `<후보>!/` 내부 rename 기록, (c) 후보 이름이 같은 레벨에 미존재 — 순서 무관 |
| medium | concat된 zip(앞에 다른 zip)이 in-place unpack에서 뒤쪽 내용을 통째로 소실 | 순차 판독 가드를 `entriesSeen==0` → `seenSourceNames.containsAll(centralDirectoryNames)`로 강화, 커버 못 하면 실패 |
| medium | CR/LF 든 중첩 아카이브 이름이 줄 기반 `target.txt`를 깨뜨림 | rename 가드가 leaf가 아닌 기록될 전체 이름(`prefix+name`)을 검사 |
| medium | un-rename 충돌 시 스트리밍 fast-path에서만 무음 드롭(안전망이 buffered 경로에만 있었음) | 두 경로 모두 충돌 시 원래 이름으로 유지 + WARN |
| low | 입력 zip의 중복 엔트리 이름에서 후속본 무음 드롭 | first-wins + WARN, 목록 중복 제거 |
| low | pack이 모든 중첩 아카이브에 빈 `target-ext.txt`/`target.txt` 삽입 | 메타데이터는 최상위 루트에만 기록(구버전 빈 메타는 unpack이 계속 정리) |

## 크로스플랫폼 / 견고성

- 엔트리 이름을 `Path.of(...)`로 파싱하던 3곳(`renameIfMatch`, `addExtensionToken`,
  `isExtensionless`) 제거 → `EntryNames` 문자열 처리. Windows에서 `:` `*` `?` 등이
  든 (Linux/macOS에서 합법인) 엔트리 이름에 크래시하던 문제 해결.
- `PackEntryFilters`의 글롭을 `FileSystems.getDefault().getPathMatcher` 대신 패턴당
  1회 컴파일해 캐시하는 플랫폼 독립 구현으로 교체(대소문자 구분, OS 무관 결과;
  엔트리마다 재컴파일하던 성능 문제도 해소).
- 디렉터리 제외가 name-only 패턴(`.Trash-*` 등)으로 디렉터리만 지우고 자식은 남겨
  orphan을 만들던 문제 → 디렉터리에는 디렉터리 지향 패턴(`X/**`, 전체경로)만 적용.
- `rewriteZipToJarIfManifest`: 원본 `ZipFile` 핸들을 닫은 뒤 move/삭제(Windows에서
  열린 파일 삭제 실패 회피), 엔트리 스트리밍 복사(대용량 OOM 방지), 실패 시 임시
  파일 정리, 기존 `.jar` 덮어쓸 때 WARN.
- 세 rewrite 진입점 모두 실패 시 `airbridge-*` 임시 파일 정리; in-place unpack은
  `ATOMIC_MOVE` 우선 시도.
- `copyEntry`가 local-header extra 필드 보존(`setTime` 후 `setExtra`).
- 비-아카이브 엔트리는 전체 버퍼링 없이 스트리밍 복사(`streamEntry`).
- `PackagerInspector`의 읽기 전용 스캔도 중첩 아카이브를 close-shield로 스트리밍.

## CLI / UX

- `Identify`/`Pack`/`Unpack`을 `Callable<Integer>`로 전환.
  - 입력 검증을 앞단에서 수행(`PackagerCli.requireExistingPackage`).
  - 예측 가능한 오류(파일 없음, `.jar`/`.zip` 아님, 손상 zip)는 스택트레이스 대신
    `"<커맨드> failed: <원인>"` 한 줄 + exit 1.
  - `unpack`이 임베드 `target-ext.txt` 없을 때: 메시지 유지, exit 0 → **exit 1**,
    입력 무변경.
- `readEmbeddedTextFile`이 `reader.lines()` 대신 바이트로 읽어 손상 메타 엔트리의
  `UncheckedIOException` 누출(스택트레이스) 차단.
- `describe()`의 UTF-8 힌트를 아카이브 엔트리 이름 디코드 오류(ZipException
  `bad entry name`, ZipInputStream의 `CharacterCodingException)`)로만 한정. 사용자
  `target-ext.txt`가 UTF-8이 아닐 때는 전용 메시지로 안내.
- `target.txt` 줄은 정확한 엔트리 이름이므로 trim하지 않음(선행/후행 공백 이름 매칭
  보존, 구버전 CRLF 호환으로 끝의 `\r`만 제거). 메타데이터 줄 구분자는 생성 OS와
  무관하게 `\n` 고정.
- `pack`의 `target.txt` rename 목록을 별도 사전 스캔(`collectPackedNames`, 삭제)이
  아니라 rewrite 패스 자체에서 수집 → 실제 출력 zip과 항상 일치, 아카이브 트리를
  2~3회 재압축하던 중복 작업 제거.

## 검증

- `PackagerAppTest` 35 → 43 케이스(파라미터라이즈 양방향 포함), 신규 회귀:
  가짜 아카이브 왕복, rename 충돌 양쪽 보존, 예약 이름 처리, 중복 엔트리,
  Windows 불법문자·공백/개행 이름 왕복, 구버전(목록 없음/불완전/CRLF) 복원,
  디렉터리 제외 orphan 방지, concat-zip/ preamble 거부(입력 무변경, 임시파일
  미누출), fast-path 충돌 보존, 손상 메타/비UTF-8 `target-ext.txt` 에러.
- `GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test` 전체 통과.
- 실제 fat-jar(`receiver`/`sender`)로 end-to-end 확인: concat-zip 거부 시 입력
  바이트 동일, 충돌 시 양쪽 바이트 동일 보존, UTF-8 오라우팅 정정.

## 관련 문서

- `packager.md` — 내부 동작 계약(위 규칙 반영).
- `../user/packager.md` — 사용자 실행/오류 동작.
