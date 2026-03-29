# air-bridge

`air-bridge` is a tool for moving data across air-gapped environments.

The project is built around a paired `sender` and `receiver`. Its main transfer flow is `encode`, `slide`, `capture`, and `decode`: the sender converts files into an image sequence, and the receiver restores them back into files.

The helper commands `identify`, `pack`, and `unpack` are intended for inspecting file contents and preparing packaging before moving `sender` into a target environment.

## Warning

Real-world use of this tool may raise policy, legal, or security issues depending on the environment. Users are responsible for checking applicable laws and internal rules before using it.

For a stronger warning and responsibility statement, see [`docs/warning.en.md`](docs/warning.en.md).

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

The version format is `0.9.yymmdd.hh24mi`.

The default runtime entrypoint for both `sender` and `receiver` is the fat jar. For `receiver`, extra JVM memory options are recommended for large `decode` or `capture` runs.

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

- `warning`: `docs/warning.en.md`
- `sender`: `docs/deploy-sender.md`
- `receiver`: `docs/deploy-receiver.md`

## Detailed Usage

### Commands

### 1. encode

Reads target files and converts them into QR PNG files.

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT
```

Main outputs:

- QR PNG files
- `_manifest.txt`

### 2. decode

Reads QR PNG files and restores the original files.

```bash
java -jar build/libs/receiver-<version>.jar decode --in /path/qr-images --out /path/restore
```

Main outputs:

- restored original files
- `_restore_result.txt`

Example `_restore_result.txt`:

```text
O src/main/java/App.java - OK
X src/main/java/App.java - INCOMPLETE (missing: [2, 5])
X src/main/java/App.java - HASH_MISMATCH
X src/main/java/App.java - DECODE_ERROR
```

### 3. capture

Captures QR frames from a UVC camera source and saves `captured-images/` plus `capture-manifest.json`.

```bash
java -jar build/libs/receiver-<version>.jar capture --out /path/capture-out
```

### 4. identify / pack / unpack

Helper commands for package transport.

```bash
java -jar build/libs/receiver-<version>.jar identify --in /path/to/sender.jar
java -jar build/libs/receiver-<version>.jar pack --in /path/to/sender.jar
java -jar build/libs/sender-<version>.jar unpack --in /path/to/sender.zip
```

Main outputs:

- `target-ext.txt`
- packed `.zip`

### 5. slide

The Swing slide player is bundled into `sender`.

```bash
java -jar build/libs/sender-<version>.jar slide
java -jar build/libs/sender-<version>.jar slide --help
```

### 6. reencode

`reencode` is a hidden maintenance command. It is not shown in public help, but it can regenerate failed files or missing chunks from `_restore_result.txt`.

```bash
java -jar build/libs/sender-<version>.jar reencode --in /path/in --out /path/reencoded --project-name PROJECT --restore-dir /path/restore
```

## Main Options

- `sender` public commands: `encode`, `slide`, `unpack`
- `receiver` public commands: `decode`, `capture`, `identify`, `pack`
- `--in`: input directory
- `--out`: output directory
- `--project-name`: project name
- `--encode-root`: base path used for relative paths stored in QR payloads
- `--restore-dir`: default location of `_restore_result.txt` for `reencode`
- `--reencode-result-path`: explicit restore result file for `reencode`
- `--chunk-data-size`: payload chunk size per QR
- `--qr-image-size`: QR image size
- `--qr-error-level`: `L`, `M`, `Q`, `H`
- `--label-height`: height of the label area below the QR
- `--convert-xlsx-to-csv`: convert `.xlsx` to CSV before encoding
- `--convert-office-to-text`: convert `.docx` and `.pptx` to text before encoding
- `--[no-]folder-structure`: keep or flatten the output folder structure
- `--files-per-folder`: files per folder in flat output mode
- `--print-html`: generate print-friendly HTML
- `--target-extensions`: file extensions to include in encoding
- `--skip-dirs`: directory names to skip while scanning
- `--exclude-paths`: absolute or relative paths to exclude

## Examples

Store QR files in numbered folders instead of mirroring the source structure:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --no-folder-structure --files-per-folder 500
```

Convert XLSX to CSV and DOCX/PPTX to text before encoding:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --convert-xlsx-to-csv --convert-office-to-text
```

Limit encoding to specific file extensions:

```bash
java -jar build/libs/sender-<version>.jar encode --in /path/in --out /path/out --project-name PROJECT --target-extensions .java,.xml,.properties,.sql
```

## Notes

- `decode` and `reencode` assume the QR payload format produced by `encode`.
- `reencode` reads `INCOMPLETE`, `DECODE_ERROR`, and `HASH_MISMATCH` entries from `_restore_result.txt`.
- Small smoke-test inputs live in [`fixtures/samples`](fixtures/samples).
- Larger manual verification assets live in [`fixtures`](fixtures).
- On headless servers, `java -Djava.awt.headless=true` can be a safer way to run the application.
