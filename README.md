# air-bridge

## 소개 / Introduction

`air-bridge`는 air-gap 환경에서 데이터를 전송하기 위해 사용하는 도구입니다.

`air-bridge` is a tool for moving data across air-gapped environments.

이 프로젝트는 `sender`와 `receiver` 두 애플리케이션이 한 쌍으로 동작합니다. 주요 전송 흐름은 `encode`, `slide`, `capture`, `decode`로 이어지며, 송신 측에서 파일을 이미지 시퀀스로 변환하고 수신 측에서 이를 다시 복원합니다.

The project is built around a paired `sender` and `receiver`. Its main transfer flow is `encode`, `slide`, `capture`, and `decode`: the sender converts files into an image sequence, and the receiver restores them back into files.

보조 명령인 `identify`, `pack`, `unpack`는 `sender`를 대상 환경에 반입하기 전에 파일 구성 확인과 패키징 작업을 돕기 위한 기능입니다.

The helper commands `identify`, `pack`, and `unpack` are intended for inspecting file contents and preparing packaging before moving `sender` into a target environment.

## 경고 / Warning

이 도구의 실제 사용은 적용 환경의 정책, 법률, 보안 규정에 따라 문제가 될 수 있습니다.  
사용자는 관련 법률과 내부 정책을 직접 확인하고 스스로 판단해야 하며, 그에 따른 책임은 사용자에게 있습니다.

Real-world use of this tool may raise policy, legal, or security issues depending on the environment.  
Users are responsible for checking applicable laws and internal rules before using it.

참고(See):

- [`docs/user/warning.ko.md`](docs/user/warning.ko.md)
- [`docs/user/warning.en.md`](docs/user/warning.en.md)

## 사용 흐름 Overview / Workflow Overview

일반적인 사용 흐름은 아래와 같습니다.

1. 대상 컴퓨터에서 전송할 파일에 대해 `encode`를 수행합니다.
2. 생성된 이미지 파일을 송출하기 위해 `slide`를 실행합니다.
3. 데이터를 수신할 환경에서 `capture`를 수행합니다.
4. 수집된 결과를 복원하기 위해 `decode`를 수행합니다.

The typical workflow is:

1. Run `encode` on the source machine for the files you want to transfer.
2. Run `slide` to present the generated image sequence.
3. Run `capture` on the receiving side to collect the transmitted images.
4. Run `decode` to restore the captured results back into files.

`slide`와 `capture`는 USB capture board를 사용하는 구성을 기본 전제로 합니다.  
capture board가 없다면 이미지를 수동으로 촬영하거나 별도 방식으로 옮겨 처리할 수 있습니다.

`slide` and `capture` are primarily designed around a USB capture board setup.  
If no capture board is available, the images can be moved manually by photographing or transferring them through another process.

상세한 실행 방법은 아래 사용자 문서를 참고합니다.

See the user guides below for detailed usage.

## 빌드 / Build

아래 명령으로 전체 프로젝트를 빌드합니다.

Build the full project with:

```bash
./gradlew clean build
```

기본 산출물은 `build/libs/sender-<version>.jar` 와 `build/libs/receiver-<version>.jar` 입니다.

The primary artifacts are `build/libs/sender-<version>.jar` and `build/libs/receiver-<version>.jar`.

실행 기준은 `sender` 와 `receiver` 모두 jar입니다.

The default runtime entrypoint for both `sender` and `receiver` is the fat jar.

## 기본 명령 / Commands

- `sender`: `encode`, `slide`, `unpack`
- `receiver`: `decode`, `capture`, `identify`, `pack`

## 빠른 시작 / Quick Start

최소 실행 확인은 아래 명령으로 충분합니다.

These commands are enough for a minimal smoke check.

```bash
./gradlew clean build
java -jar build/libs/sender-<version>.jar --help
java -jar build/libs/receiver-<version>.jar --help
```

## 문서 / Docs

- [warning ko](docs/user/warning.ko.md)
- [warning en](docs/user/warning.en.md)
- [sender deployment](docs/user/deploy-sender.md)
- [receiver deployment](docs/user/deploy-receiver.md)
- [encode / decode usage](docs/user/encode-decode.md)
- [slide / capture usage](docs/user/slide-capture.md)
- [packager usage](docs/user/packager.md)
- [tuning](docs/user/tuning.md)
