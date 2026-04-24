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

## identify

입력 `jar` 또는 `zip`을 열고 내부 엔트리에서 확장자 토큰을 수집합니다.

동작 요약:

1. 최상위 패키지와 중첩 `jar`/`zip` 내부까지 재귀적으로 스캔한다.
2. 디렉터리는 제외한다.
3. OS/IDE 잡파일 등 제외 패턴은 `PackEntryFilters` 기준으로 무시한다.
4. 파일명이 확장자를 가지면 마지막 확장자만 토큰으로 기록한다.
5. 확장자가 없으면 파일명 자체를 토큰으로 기록한다.
6. 수집 결과에 `ExtensionTokens.filterIncluded(...)`를 적용해 제외 토큰을 걷어낸다.
7. 결과를 입력 파일과 같은 디렉터리의 `target-ext.txt`로 저장한다.

예:

- `assets/blob.dat` -> `dat`
- `scripts/run` -> `run`
- `config/settings.xml` -> `xml`

기본 제외 토큰에는 `class`, `xml`, `js`, `jsp`, `html`, `css`, `exe`, `zip`, `jar`, `properties`, `png`, `jpg`, `jpeg` 등이 들어 있습니다. 실제 값은 `/ext/ext.properties`를 우선 사용하고, 없으면 코드 fallback을 사용합니다.

## pack

`pack`은 입력 패키지를 새 `.zip` 파일로 다시 쓰면서 대상 엔트리 이름 끝에 `.txt`를 붙입니다.

출력 규칙:

- 입력이 `sample.jar`면 출력은 같은 디렉터리의 `sample.zip`
- 원본 파일은 그대로 두고 새 파일을 만든다

대상 확장자 결정 순서:

1. 입력 파일과 같은 디렉터리에 `target-ext.txt`가 있으면 그 내용을 사용한다.
2. 없으면 패키지를 스캔해서 확장자 토큰을 추론한다.
3. 추론 시 `/ext/ext.properties`와 `ExtensionTokens.filterIncluded(...)` 기준을 적용한다.

추가 규칙:

- `png`, `jpg`, `jpeg`는 `target-ext.txt`에 있어도 실제 패킹 대상에서 제외된다.
- 확장자가 없는 엔트리는 항상 패킹 대상이다.
- 제외 패턴에 걸린 엔트리는 출력 zip에서 아예 빠진다.
- 중첩 `jar`/`zip`도 내부까지 재귀적으로 rewrite 한다.

예:

- `assets/blob.dat` -> `assets/blob.dat.txt`
- `scripts/run` -> `scripts/run.txt`
- `config/settings.xml` -> 그대로 유지
- `assets/logo.png` -> 그대로 유지

`pack`은 출력 zip 루트에 메타데이터 두 파일을 추가한다.

- `target-ext.txt`: 이번 rewrite에 사용한 대상 확장자 목록
- `target.txt`: `.txt` suffix가 붙은 엔트리 이름 목록

이 메타데이터는 `unpack`이 복원 기준으로 사용합니다.

## unpack

`unpack`은 입력 패키지 안에 들어 있는 `target-ext.txt`를 먼저 읽습니다.

- 없으면 `WARN embedded target-ext.txt not found; aborting`를 출력하고 중단한다.
- 있으면 그 확장자 집합을 기준으로 `.txt` suffix를 제거한다.

동작 요약:

1. 입력 파일을 제자리 rewrite 한다.
2. `target-ext.txt`, `target.txt` 메타데이터 엔트리는 제거한다.
3. 대상 확장자 엔트리와 확장자 없는 엔트리의 `.txt` suffix를 제거한다.
4. 중첩 `jar`/`zip`도 내부까지 재귀적으로 복원한다.
5. 결과 zip에 `META-INF/MANIFEST.MF`가 있으면 `.jar`로 다시 써서 파일 확장자도 `.jar`로 되돌린다.

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

이 패턴은 `identify`의 확장자 수집, `pack`의 대상 계산, 실제 rewrite 모두에 영향을 줍니다.

## 구현 포인트

- 입력은 `.jar` 또는 `.zip`만 허용한다.
- nested package 판단은 파일명 suffix 기준이다.
- 확장자 판별도 항상 엔트리의 마지막 파일명 기준이다.
- rewrite 시 메서드, 시간, comment, CRC 같은 zip entry 메타데이터를 최대한 유지한다.
- `pack`은 중복 엔트리명 생성을 `seen` 집합으로 방지한다.
- `unpack`은 메타데이터 엔트리를 최종 결과에서 제거한다.

## 테스트로 보장하는 내용

`libs/packager/src/test/java/airbridge/packager/PackagerAppTest.java` 기준으로 아래 시나리오가 검증된다.

- `target-ext.txt`가 없을 때 추론 fallback 동작
- OS/IDE 잡파일 제외
- `png`/`jpg` 같은 blocked 확장자 제외
- `target-ext.txt`와 `target.txt` 임베드
- `unpack` 시 메타데이터 제거
- manifest가 있으면 `.zip`을 다시 `.jar`로 복원
- nested `jar`/`zip` 재귀 rewrite

## 운영 문서와의 경계

이 문서는 내부 구현 기준입니다. 실제 사용자 실행 예시와 배포 절차는 아래 문서를 참고합니다.

- `docs/user/deploy-receiver.md`
- `docs/user/deploy-sender.md`
