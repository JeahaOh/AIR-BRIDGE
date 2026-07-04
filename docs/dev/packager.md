# packager

`identify`, `pack`, `unpack` 동작을 개발 기준으로 정리한 문서입니다.

## 목적

이 모듈은 `sender.jar` 같은 배포 패키지 안의 일부 엔트리 이름에 `.txt` suffix를 붙였다가 다시 되돌리는 보조 기능을 제공합니다.

- `identify`: 패키지 내부 확장자 토큰을 수집해서 `target-ext.txt`를 만든다.
- `pack`: 대상 엔트리와 확장자 없는 엔트리에 `.txt`를 붙여 새 `.zip`으로 다시 쓴다.
- `unpack`: 패킹된 `.zip` 안의 `.txt` suffix를 제거하고 필요하면 다시 `.jar`로 되돌린다.

## 진입점

- CLI 루트: `libs/packager/src/main/java/airbridge/packager/PackagerApp.java`
- 서브커맨드:
  - `IdentifyCommand`
  - `PackCommand`
  - `UnpackCommand`

실제 앱에서는 이 기능이 다음처럼 노출됩니다.

- `receiver`: `identify`, `pack`
- `sender`: `unpack`

세 커맨드 모두 실행 전에 입력을 검증합니다. 입력 파일이 없거나 `.jar`/`.zip`이
아니거나 zip으로 열 수 없으면 스택 트레이스 대신 `"<커맨드> failed: <원인>"`
한 줄을 stderr에 출력하고 exit code 1로 끝납니다(`PackagerCli`).

## identify

입력 `jar` 또는 `zip`을 열고 내부 엔트리에서 확장자 토큰을 수집합니다.

동작 요약:

1. 최상위 패키지와 중첩 `jar`/`zip` 내부까지 재귀적으로 스캔한다. 중첩 아카이브는
   메모리 버퍼링 없이 스트리밍으로 읽는다.
2. 디렉터리는 제외한다.
3. `PackEntryFilters` 제외 패턴은 적용하지 않는다(`pack`만 적용). 잡파일 토큰은
   6번의 `ExtensionTokens` 필터에서만 걸러진다.
4. 파일명이 확장자를 가지면 마지막 확장자만 토큰으로 기록한다.
5. 확장자가 없으면 파일명 자체를 토큰으로 기록한다.
6. 수집 결과에 `ExtensionTokens.filterIncluded(...)`를 적용해 제외 토큰을 걷어낸다.
7. 결과를 입력 파일과 같은 디렉터리의 `target-ext.txt`로 저장한다.

이름이 `.jar`/`.zip`으로 끝나도 실제 바이트가 zip이 아니면(zip LFH 매직
`PK\x03\x04` 없음) 내부로 재귀하지 않고 일반 파일처럼 확장자 토큰만 기록합니다.

예:

- `assets/blob.dat` -> `dat`
- `scripts/run` -> `run`
- `config/settings.xml` -> `xml`

기본 제외 토큰에는 `class`, `xml`, `js`, `jsp`, `html`, `css`, `exe`, `zip`, `jar`, `properties`, `png`, `jpg`, `jpeg` 등이 들어 있습니다. 실제 값은 `/ext/ext.properties`를 우선 사용하고, 없으면 코드 fallback을 사용합니다.

## pack

`pack`은 입력 패키지를 새 `.zip` 파일로 다시 쓰면서 대상 엔트리 이름 끝에 `.txt`를 붙입니다.

출력 규칙:

- 입력이 `sample.jar`면 출력은 같은 디렉터리의 `sample.zip`
- 입력이 이미 `.zip`이면 원본을 덮어쓰지 않도록 `sample-packed.zip`으로 저장한다
- 원본 파일은 그대로 두고 새 파일을 만든다
- 출력 경로에 별개의 파일이 이미 있으면 `WARN overwriting existing file ...`을
  출력하고 덮어쓴다

대상 확장자 결정 순서:

1. 입력 파일과 같은 디렉터리에 `target-ext.txt`가 있으면 그 내용을 사용한다.
2. 없으면 패키지를 스캔해서 확장자 토큰을 추론한다.
3. 추론 시 `/ext/ext.properties`와 `ExtensionTokens.filterIncluded(...)` 기준을 적용한다.

추가 규칙:

- `png`, `jpg`, `jpeg`는 `target-ext.txt`에 있어도 실제 패킹 대상에서 제외된다.
- 확장자가 없는 엔트리는 항상 패킹 대상이다.
- 제외 패턴에 걸린 파일 엔트리는 출력 zip에서 아예 빠진다. 디렉터리 엔트리는
  디렉터리 지향 패턴(`X/**`, `/` 포함 전체경로 글롭)에만 걸려 빠지고, name-only
  패턴(`.DS_Store`, `.Trash-*` 등)은 디렉터리 엔트리에 적용하지 않는다 — 디렉터리만
  지우고 그 자식은 남겨 subtree를 orphan으로 만들지 않기 위해서다.
- 중첩 `jar`/`zip`도 내부까지 재귀적으로 rewrite 한다. 단, 실제 바이트가 zip일
  때만(`PK\x03\x04` 매직) 재귀하고, 이름만 아카이브처럼 생긴 엔트리는
  `WARN ... looks like an archive but is not`을 출력하고 내용 그대로 복사한다.
- rename 결과가 같은 레벨의 기존 엔트리 이름과 충돌하면(예: `a.cfg`와 진짜
  `a.cfg.txt`가 공존) rename을 포기하고 원래 이름을 유지하며 WARN을 출력한다.
  이렇게 남은 엔트리는 `.txt`로 끝나지 않으므로 unpack이 건드리지 않는다.
- rename 결과가 메타데이터 이름(`target.txt`, `target-ext.txt`)과 같아지면
  rename을 포기하고 WARN을 출력한다(메타데이터 슬롯 탈취 방지).
- 입력에 원래부터 `target.txt`/`target-ext.txt`라는 이름의 엔트리가 있으면
  보존할 수 없다. WARN을 출력하고 제거한 뒤 pack 메타데이터로 대체한다.
- 이름에 CR/LF가 들어간 엔트리는 줄 기반 rename 목록에 기록할 수 없으므로
  rename하지 않고 그대로 통과시킨다.
- 같은 이름의 중복 엔트리는 첫 번째 것만 남기고 WARN을 출력한다.

예:

- `assets/blob.dat` -> `assets/blob.dat.txt`
- `scripts/run` -> `scripts/run.txt`
- `config/settings.xml` -> 그대로 유지
- `assets/logo.png` -> 그대로 유지

`pack`은 출력 zip 루트에 메타데이터 두 파일을 추가한다.

- `target-ext.txt`: 이번 rewrite에 사용한 대상 확장자 목록
- `target.txt`: `.txt` suffix가 붙은 엔트리 이름 목록. 중첩 아카이브 자체의 rename과
  그 내부 엔트리(`outer.jar!/inner.dat.txt` 형식)도 포함한다

`target.txt`는 별도 사전 스캔이 아니라 rewrite 패스 자체에서 수집되므로 항상
실제 출력 zip과 일치한다(충돌로 rename이 억제된 엔트리는 기록되지 않는다).
메타데이터는 최상위 zip 루트에만 기록하며, 중첩 아카이브 안에는 넣지 않는다
(빈 메타데이터를 넣던 구버전 산출물도 unpack이 여전히 정리한다). 줄 구분자는
생성 OS와 무관하게 항상 `\n`이다.

이 메타데이터는 `unpack`이 복원 기준으로 사용합니다.

## unpack

`unpack`은 입력 패키지 안에 들어 있는 `target-ext.txt`를 먼저 읽습니다.

- 없으면 `WARN embedded target-ext.txt not found; aborting`를 출력하고 아무것도
  바꾸지 않은 채 exit code 1로 중단한다(스크립트에서 실패를 감지할 수 있다).
- 있으면 그 확장자 집합을 기준으로 `.txt` suffix를 제거한다.

동작 요약:

1. 입력 파일을 제자리 rewrite 한다(임시 파일에 쓴 뒤 원자적 move를 시도).
2. `target-ext.txt`, `target.txt` 메타데이터 엔트리는 최상위/중첩 모두 제거한다.
3. `target.txt`의 이름 목록에 있는 엔트리만 `.txt` suffix를 제거한다 — pack이 rename하지
   않은, 원래부터 `.txt`였던 파일(예: `readme.txt`)은 이름을 유지한다. 예외: 목록이 있어도
   `*.jar.txt`/`*.zip.txt`(대상 확장자인 아카이브)는 모양 기준으로 복원한다(중첩 아카이브
   rename을 기록하지 않던 구버전 목록 호환). 이 모양 예외는 아래 세 조건을 **모두**
   만족할 때만 적용된다.
   - (a) 엔트리 바이트가 실제 zip이다(`PK\x03\x04`).
   - (b) 목록에 그 아카이브 내부 엔트리 rename(`<후보>!/...`)이 실제로 기록돼 있다.
   - (c) un-rename 결과 이름(후보)이 같은 레벨에 아직 존재하지 않는다.
   현재 포맷 목록은 아카이브 자체 rename을 정확히 기록하므로 위 정확 매칭에서 끝나 모양
   예외까지 오지 않는다. 진짜 사용자 파일은 두 경로로 보호된다: 형제 아카이브가 없는
   외톨이 `build.jar.txt`(zip 바이트)는 내부 rename 기록이 없어 (b)에서 걸리고, 진짜
   `a.jar` 옆에 있는 `a.jar.txt`는 — 그 `a.jar`가 자기 내부 rename을 기록해 (b)를
   만족하더라도 — 후보 `a.jar`가 이미 존재하므로 (c)에서 걸린다. 그래서 진짜 파일은
   순서와 무관하게 이름·내용을 그대로 유지한다. 목록 자체가 없는 구버전 패키지는 확장자
   휴리스틱(대상 확장자·확장자 없음)으로 복원한다.
   그럼에도 un-rename 결과가 같은 레벨의 기존 이름과 충돌하면(정확 매칭 경로 포함) 원래
   이름으로 유지하며(내용 무손실) WARN을 출력한다 — 조용히 드롭하지 않는다.
4. `target.txt`의 줄은 정확한 엔트리 이름이므로 trim하지 않는다(공백으로 시작하는
   이름도 매칭된다). 구버전이 CRLF로 기록한 목록을 위해 끝의 `\r`만 제거한다.
5. 중첩 `jar`/`zip`도 내부까지 재귀적으로 복원한다. pack과 같은 zip 매직 게이트를
   적용해, zip이 아닌 바이트는 WARN과 함께 그대로 복사한다.
6. 결과 zip에 `META-INF/MANIFEST.MF`가 있으면 `.jar`로 다시 써서 파일 확장자도 `.jar`로 되돌린다.
   엔트리는 버퍼링 없이 스트리밍 복사하며(STORED는 central directory의 size/crc 사용),
   원본 zip 핸들을 닫은 뒤에 move/삭제하므로 Windows에서도 안전하다. 대상 `.jar`가
   이미 있으면 WARN 후 덮어쓴다(pack 후 같은 폴더에서 unpack하는 문서화된 흐름에서는
   원본 jar가 재구성본으로 대체된다는 뜻이다).

즉, `sample.zip`이 실제 jar 구조였다면 `unpack` 뒤에는 `sample.jar`가 남고 `sample.zip`은 삭제됩니다.

## 제외 패턴

출력에서 제거되는 엔트리 패턴은 `/ext/ext.properties`의 `pack.exclude-entry-patterns`를 사용합니다.

현재 기본 대상에는 아래 종류가 포함됩니다.

- `__MACOSX/**`
- `.DS_Store`
- `Thumbs.db`
- `.idea/**`
- `.vscode/**`
- `node_modules/**`
- `__pycache__/**`

이 패턴은 `pack`에만 적용됩니다(추론 fallback, 실제 rewrite).
`identify`는 이 패턴을 적용하지 않고 `ExtensionTokens` 토큰 필터만 사용합니다.

매칭 규칙:

- 글롭은 플랫폼 독립 구현으로, 패턴당 한 번 컴파일해 캐시한다. `**`는 세그먼트
  경계를 넘고 `*`/`?`는 한 세그먼트 안에서만 매칭한다. 그 밖의 문자는 리터럴이며
  (`{}`/`[]` 문법 없음) 대소문자를 구분한다 — OS가 달라도 결과가 같다.
- `/`가 들어간 패턴은 전체 엔트리 경로에, 없는 패턴(name-only)은 마지막 세그먼트에 매칭한다.
- 파일 엔트리에는 모든 패턴이 적용된다. 디렉터리 엔트리에는 디렉터리 지향 패턴만
  적용된다: `X/**` 패턴은 `X/` 디렉터리 엔트리 자체도 제거하고(그 자식도 전체경로
  매칭으로 함께 제거), `/` 포함 글롭도 전체 경로에 매칭한다. name-only 패턴은
  디렉터리 엔트리에 적용하지 않는다(자식 orphan 방지).

## 구현 포인트

- 입력은 `.jar` 또는 `.zip`만 허용한다.
- nested package 판단은 파일명 suffix + zip 매직(`PK\x03\x04`) 기준이다. suffix만
  맞고 바이트가 zip이 아니면 내용을 그대로 통과시킨다(파괴 방지).
- 확장자 판별은 항상 엔트리의 마지막 파일명 세그먼트 기준이며, 엔트리 이름을
  `java.nio.file.Path`로 파싱하지 않는다(`EntryNames`). Windows에서 `:` `*` `?`
  같은 문자가 든 엔트리 이름도 처리된다.
- rewrite 시 메서드, 시간, extra 필드(확장 타임스탬프 등), CRC 같은 zip entry
  메타데이터를 최대한 유지한다. 단 central directory에만 존재하는 엔트리 comment는
  스트림 rewrite에서 보존되지 않는다.
- 중첩 아카이브가 아닌 엔트리는 전체 버퍼링 없이 스트리밍 복사한다.
- rename 충돌·중복 엔트리는 `seen` 집합과 원본 이름 집합으로 방지하며, 조용히
  드롭하지 않고 WARN을 출력한다.
- rewrite 중 실패하면 입력 옆에 만든 `airbridge-*` 임시 파일을 정리한다.
- 순차 ZipInputStream 패스가 본 엔트리 이름 집합이 central directory 이름 집합을
  모두 포함하지 못하면(자가압축해제형 preamble, 앞에 다른 zip이 concat돼 뒤쪽 EOCD가
  이기는 경우 등) pack이 일부 엔트리를 빠뜨린 산출물을 만들거나 in-place unpack이
  central directory에 보이는 내용을 지워버리지 않도록 명시적으로 실패시킨다.
- 엔트리 이름이 UTF-8이 아닌 아카이브(EFS 플래그 없는 구형 코드페이지)는 열 때
  실패하며, `archive entry names are not valid UTF-8 ...` 한 줄 에러로 안내한다.

## 테스트로 보장하는 내용

`libs/packager/src/test/java/airbridge/packager/PackagerAppTest.java` 기준으로 아래 시나리오가 검증된다.

- `target-ext.txt`가 없을 때 추론 fallback 동작
- OS/IDE 잡파일 제외(파일 + 디렉터리 엔트리)
- `png`/`jpg` 같은 blocked 확장자 제외
- `target-ext.txt`와 `target.txt` 임베드, `\n` 구분자 고정
- `unpack` 시 메타데이터 제거, 중첩 아카이브에는 메타데이터 미기록
- manifest가 있으면 `.zip`을 다시 `.jar`로 복원
- nested `jar`/`zip` 재귀 rewrite (STORED 포함, jar-in-jar-in-jar)
- 이름만 아카이브인 엔트리(가짜 `.jar`)의 내용 보존 왕복
- rename 충돌(`a.cfg` + 진짜 `a.cfg.txt`) 시 양쪽 모두 보존
- `target`/`target-ext`/`target.txt`라는 이름의 사용자 엔트리 처리와 WARN
- 중복 엔트리 이름(수제 raw zip)의 first-wins + WARN
- Windows에서 불법인 문자(`:` `*` `?`), 공백/개행이 든 엔트리 이름 왕복
- rename 목록 없는 구버전 패키지의 휴리스틱 복원
- 중첩 아카이브 rename이 빠진 구버전 목록의 모양 기반 복원(zip 매직 게이트 포함)
- CRLF로 기록된 구버전 메타데이터 수용
- `unpack` 메타데이터 부재 시 exit code 1 + 입력 무변경
- 예측 가능한 사용자 실수(파일 없음, 잘못된 확장자, 손상 zip)의 한 줄 에러 + exit 1
- `identify`의 CLI 실행과 `target-ext.txt` 덮어쓰기
- 기존 출력 파일 덮어쓰기 WARN
- preamble 붙은 zip의 명시적 거부(입력 무변경, 임시 파일 미누출)

## 운영 문서와의 경계

이 문서는 내부 구현 기준입니다. 실제 사용자 실행 예시와 배포 절차는 아래 문서를 참고합니다.

- `docs/user/deploy-receiver.md`
- `docs/user/deploy-sender.md`
