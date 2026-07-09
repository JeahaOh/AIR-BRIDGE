# air-bridge

`air-bridge` is a tool for moving data across air-gapped environments.

The project is built around a paired `sender` and `receiver`. Its main transfer flow is `encode`, `slide`, `capture`, and `decode`: the sender converts files into an image sequence, and the receiver restores them back into files.

`sender query` is an optional source-generation command that exports DB query results to CSV files that can become part of the later `encode` input. The helper commands `identify`, `pack`, and `unpack` are intended for inspecting file contents and preparing packaging before moving `sender` into a target environment.

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

Default-command launch scripts are generated in the same directory:

```bash
build/libs/encode.sh
build/libs/encode.bat
build/libs/slide.sh
build/libs/slide.bat
build/libs/capture.sh
build/libs/capture.bat
build/libs/decode.sh
build/libs/decode.bat
```

The version format is `{major}.{minor}.{yymmdd}.{hh24mi}`.

The default runtime entrypoint for both `sender` and `receiver` is the fat jar.
Running the jar without a command opens the GUI. Supplying a command or CLI
option such as `encode`, `decode`, or `--help` keeps the existing CLI behavior.

## Public Commands

- `sender`: `encode`, `gui`, `query`, `slide`, `unpack`
- `receiver`: `decode`, `capture`, `gui`, `identify`, `pack`

## Default Paths

When `--in`/`--out` are omitted, jar-relative directories are used:

```
encode( source -> encoded ) -> slide( encoded ) -> capture( -> captured ) -> decode( captured -> decoded )
```

Override the directory names with an `airbridge-paths.properties` file next to the jar
(keys: `dir.source`/`dir.encoded`/`dir.captured`/`dir.decoded`). The GUI uses the same defaults.

## Quick Start

These commands are enough for a minimal smoke check.

```bash
./gradlew clean build
java -jar build/libs/sender-<version>.jar --help
java -jar build/libs/receiver-<version>.jar --help
```

## AI / Automation Notes

When an AI agent or script prepares a transfer, keep command responsibilities separate:

1. Use `sender query` only when DB `SELECT`/`WITH` results are needed as CSV source files.
2. Treat the query output directory as one possible input directory for `sender encode`, not as a required QR pipeline stage.
3. Run `sender encode` explicitly after choosing the files or folders to transfer.
4. Use `sender slide`, `receiver capture`, and `receiver decode` for the actual QR transfer path.
5. Do not assume `query`, `pack`, or `unpack` changes QR payload format.

## Deployment Docs

- `warning`: `docs/user/warning.en.md`
- `sender`: `docs/user/deploy-sender.md`
- `receiver`: `docs/user/deploy-receiver.md`
- `encode / decode`: `docs/user/encode-decode.md`
- `query`: `docs/user/query.md`
- `slide / capture`: `docs/user/slide-capture.md`
- `packager`: `docs/user/packager.md`
- `tuning`: `docs/user/tuning.md`
