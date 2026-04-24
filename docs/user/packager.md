# Packager Usage

이 문서는 `identify`, `pack`, `unpack`를 실제 사용 기준으로 정리합니다.

## 언제 쓰나

이 기능은 `sender.jar` 또는 다른 `jar`/`zip` 패키지를 반입용으로 점검하거나, `.txt` suffix를 붙여 다시 포장했다가 복원할 때 사용합니다.

일반적인 흐름은 아래와 같습니다.

1. `identify`로 패키지 안의 대상 확장자 목록을 확인한다.
2. 필요하면 `target-ext.txt`를 수정한다.
3. `pack`으로 새 `.zip` 산출물을 만든다.
4. 반입 후 `unpack`으로 원래 이름으로 복원한다.

## 입력과 출력

- 입력: `.jar` 또는 `.zip`
- `identify` 출력: 입력 파일과 같은 폴더의 `target-ext.txt`
- `pack` 출력: 같은 이름의 새 `.zip`
- `unpack` 출력: 입력 파일을 제자리에서 복원하고, jar 구조면 최종적으로 `.jar`로 바꾼다

예:

- 입력 `sender.jar`
- `pack` 결과 `sender.zip`
- `unpack sender.zip` 결과 `sender.jar`

## 1. identify

패키지 내부 확장자 목록을 추출한다.

```bash
java -jar build/libs/receiver-<version>.jar identify --in /path/to/sender.jar
```

실행 결과:

- 콘솔에 확장자 목록 출력
- 같은 폴더에 `target-ext.txt` 생성

예상 용도:

- 무엇이 패킹 대상이 될지 먼저 확인
- `target-ext.txt`를 수동 조정

## 2. pack

대상 엔트리 이름 끝에 `.txt` suffix를 붙여 새 `.zip`으로 만든다.

```bash
java -jar build/libs/receiver-<version>.jar pack --in /path/to/sender.jar
```

실행 결과:

- `/path/to/sender.zip` 생성
- 패킹 기준 메타데이터가 zip 안에 함께 저장됨

주의:

- 입력 파일과 같은 폴더의 `target-ext.txt`가 있으면 그 목록을 사용한다.
- `target-ext.txt`가 없으면 패키지를 읽어서 자동 추론한다.
- 이미지 확장자 일부는 목록에 있어도 패킹 대상에서 제외될 수 있다.

## 3. unpack

패킹된 `.zip`에서 `.txt` suffix를 제거해 원래 이름으로 복원한다.

```bash
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

실행 결과:

- 패킹 전 이름으로 복원
- 원래 jar 구조면 최종 산출물이 `.jar`

주의:

- `unpack`은 패키지 안에 들어 있는 메타데이터를 기준으로 복원한다.
- 패킹되지 않은 일반 zip에는 동작하지 않을 수 있다.

## 빠른 예시

```bash
java -jar build/libs/receiver-<version>.jar identify --in /work/sender.jar
vi /work/target-ext.txt
java -jar build/libs/receiver-<version>.jar pack --in /work/sender.jar
java -jar build/libs/sender-<version>.jar unpack --in /work/sender.zip
```

## 스모크 체크

- `java -jar build/libs/receiver-<version>.jar identify --help`
- `java -jar build/libs/receiver-<version>.jar pack --help`
- `java -jar build/libs/sender-<version>.jar unpack --help`

## 관련 문서

- `deploy-receiver.md`
- `deploy-sender.md`
- `warning.ko.md`
