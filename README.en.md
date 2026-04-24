# air-bridge

`air-bridge` is a tool for moving data across air-gapped environments.

The project is built around a paired `sender` and `receiver`. Its main transfer flow is `encode`, `slide`, `capture`, and `decode`: the sender converts files into an image sequence, and the receiver restores them back into files.

The helper commands `identify`, `pack`, and `unpack` are intended for inspecting file contents and preparing packaging before moving `sender` into a target environment.

## Warning

Real-world use of this tool may raise policy, legal, or security issues depending on the environment. Users are responsible for checking applicable laws and internal rules before using it.

For a stronger warning and responsibility statement, see [`docs/user/warning.en.md`](docs/user/warning.en.md).

## Workflow Overview

The typical workflow is:

1. Run `encode` on the source machine for the files you want to transfer.
2. Run `slide` to present the generated image sequence.
3. Run `capture` on the receiving side to collect the transmitted images.
4. Run `decode` to restore the captured results back into files.

`slide` and `capture` are primarily designed around a USB capture board setup. If no capture board is available, the images can be moved manually by photographing or transferring them through another process.

## Requirements

- Java 21 or later
- Environment capable of running the Gradle Wrapper

## Build

```bash
./gradlew clean build
```

In restricted environments, you can also use a project-local Gradle home:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew build
```

## Artifacts

The primary artifacts are:

```bash
build/libs/sender-<version>.jar
build/libs/receiver-<version>.jar
```

The version format is `{major}.{minor}.{yymmdd}.{hh24mi}`.

The default runtime entrypoint for both `sender` and `receiver` is the fat jar.

## Public Commands

- `sender`: `encode`, `slide`, `unpack`
- `receiver`: `decode`, `capture`, `identify`, `pack`

## Quick Start

These commands are enough for a minimal smoke check.

```bash
./gradlew clean build
java -jar build/libs/sender-<version>.jar --help
java -jar build/libs/receiver-<version>.jar --help
```

## Deployment Docs

- `warning`: `docs/user/warning.en.md`
- `sender`: `docs/user/deploy-sender.md`
- `receiver`: `docs/user/deploy-receiver.md`
- `encode / decode`: `docs/user/encode-decode.md`
- `slide / capture`: `docs/user/slide-capture.md`
- `packager`: `docs/user/packager.md`
- `tuning`: `docs/user/tuning.md`
