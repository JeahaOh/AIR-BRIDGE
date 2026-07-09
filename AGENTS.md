# AGENTS.md

## Project Identity

This repository contains `air-bridge`, a Java 21 codebase for moving data
across air-gapped environments by converting files into QR PNG sequences and
restoring them on the receiving side.

The real command surface today is:

- `sender`: `encode`, `gui`, `query`, `slide`, `unpack`, hidden `reencode`
- `receiver`: `decode`, `capture`, `gui`, `identify`, `pack`

Running either jar with no command opens the GUI; any command or CLI option
keeps CLI behavior.

Important distinction:

- `encode -> slide -> capture -> decode` is the QR transfer pipeline.
- `query` is a sender-side source generation helper: DB SELECT/WITH results
  become CSV files that can later be included in `encode` inputs.
- `identify -> pack -> unpack` is a helper flow for `jar` or `zip` artifacts.

Do not treat `pack` and `unpack` as generic archive stages inside the QR
payload pipeline. That is not how the current product works.

## Real Repository Layout

```text
apps/
  sender/
  receiver/
libs/
  common/
  slide/
  capture/
  query/
  packager/
```

Current responsibilities:

- `apps/sender`
  - Picocli entrypoint for `encode`, `query`, `unpack`, `gui`, hidden `reencode`
  - direct `slide` launcher (early token dispatch; also registered as a subcommand)
  - Swing GUI (`SenderGui`: Encode/Slide tabs)
- `apps/receiver`
  - Picocli entrypoint for `decode`, `capture`, `identify`, `pack`, `gui`
  - Swing GUI (`ReceiverGui`: Capture/Decode tabs)
- `libs/common`
  - QR payload helpers
  - shared CLI, banner, version, codec, and path helpers
- `libs/slide`
  - Swing slideshow UI for QR image playback
- `libs/capture`
  - camera/UVC capture pipeline and QR dedupe
- `libs/query`
  - sender-side `query` command for running SELECT/WITH SQL and writing CSV/report files
- `libs/packager`
  - `identify`, `pack`, `unpack` helpers for `jar` or `zip` artifacts

## Non-Negotiable Constraints

1. Air-gap first
   - Do not add mandatory network calls.
   - Do not add telemetry, cloud sync, update checks, or remote logging.
   - Core behavior must work fully offline.

2. Path safety
   - Do not allow decode or unpack logic to write outside the selected output
     directory.
   - Preserve `RelativePathSupport`-style safety checks when touching file
     restoration logic.

3. Sender/receiver separation
   - Sender code must not depend on capture-specific runtime logic.
   - Receiver code must not depend on sender-only UI behavior.
   - Shared helpers belong in `libs/common` or another clearly shared module.

4. Payload compatibility
   - Each QR frame is one LT fountain symbol of the file's gzip stream:
     `magic 'A','B' + relPath + hash + k + gzipLen + esi + symbol` (binary,
     big-endian). See `libs/common/.../fountain` and `docs/dev/encode-decode.md`.
   - There is no explicit payload version field today.
   - Do not invent or imply a transfer id, frame checksum, package manifest, or
     version field as if it already exists.
   - Fountain neighbor generation must stay cross-platform deterministic (Ideal
     Soliton; java.util.Random + IEEE division/ceil only, no Math.log/sqrt), or
     sender and receiver compute different neighbor sets and decoding breaks.
   - Any payload field or fountain-scheme change is a compatibility change and
     must update sender, receiver, tests, and docs together.

5. Existing decode behavior matters
   - Decode groups symbols by payload metadata (relPath), not by PNG filename.
   - A file rebuilds once the fountain decoder peels enough distinct symbols to
     recover all k source symbols; no specific frame is required, so dropped
     frames are tolerated. Order does not matter; duplicate esi is ignored.
   - Successful decode moves source PNGs into sibling `*-success` directories.
   - Not-enough-symbols (INCOMPLETE), QR read failures, hash mismatches, and
     invalid paths are first-class outcomes and must stay explicit.
   - `reencode` re-emits a failed file's whole symbol stream (no per-chunk index).

6. Package helper semantics matter
   - `identify` writes `target-ext.txt` in the input archive directory.
   - `pack` appends `.txt` suffixes to selected archive entries and embeds
     metadata into a new `.zip`.
   - `unpack` reverses that rewrite using embedded metadata.

7. Java-native pragmatism
   - Prefer Java standard library and the libraries already in the repo.
   - `slide` is Swing-based.
   - `capture` already relies on JavaCV/OpenCV; keep additional native
     assumptions isolated and justified.

## Architecture Rules

- Keep command orchestration close to the owning app module.
- Keep QR payload, path, codec, and the shared QR image decoder in `libs/common`.
- Keep archive rewrite logic in `libs/packager`.
- Keep slideshow UI behavior in `libs/slide`.
- Keep live capture logic in `libs/capture`.
- Keep DB query extraction logic in `libs/query`.
- Avoid introducing imaginary generic layers when the existing module split is
  already sufficient.

Preferred dependency direction:

```text
sender   -> common, packager, query, slide
receiver -> common, capture, packager
slide    -> common
capture  -> common, zxing, javacv/opencv
packager -> picocli, zip utilities
query    -> picocli, HikariCP, commons-csv, JDBC drivers (MySQL, PostgreSQL,
            Oracle, SQL Server, MariaDB, IBM DB2, H2)
common   -> shared helpers + zxing (QR decode/payload)
```

The QR image decode machine (rotations/binarizers/scales/crops) lives in
`airbridge.common.qr.QrImageDecoder`; both `apps/receiver` (clean PNG re-read)
and `libs/capture` (noisy camera frames) call it with their own tuning
`Strategy`, so there is one decode implementation and one charset (ISO-8859-1).

Test-only exception:

- `apps/receiver` may depend on `apps/sender` in tests for round-trip coverage.

## Real Transfer Pipeline

QR transfer:

```text
source files
  -> sender encode
  -> QR PNG set
  -> sender slide or external display
  -> receiver capture or imported PNG set
  -> receiver decode
  -> restored files
```

Package helper flow:

```text
jar/zip
  -> receiver identify
  -> target-ext.txt
  -> receiver pack
  -> packed zip
  -> sender unpack
  -> restored zip/jar
```

Optional query source-generation flow:

```text
database
  -> sender query
  -> CSV/report files
  -> sender encode
```

## Testing Expectations

When changing encode/decode/common payload logic, update or inspect at least:

- `apps/receiver/src/test/java/airbridge/receiver/ReceiverRoundTripTest.java`
- `apps/receiver/src/test/java/airbridge/receiver/DecodeServiceTest.java`
- `apps/sender/src/test/java/airbridge/sender/EncodeServiceTest.java`
- `libs/common/src/test/java/airbridge/common/QrPayloadSupportTest.java`

When changing package helper logic, update or inspect:

- `libs/packager/src/test/java/airbridge/packager/PackagerAppTest.java`

When changing query extraction logic, update or inspect:

- `libs/query/src/test/java/airbridge/query/QueryConfigTest.java`
- `libs/query/src/test/java/airbridge/query/QueryParserTest.java`
- `libs/query/src/test/java/airbridge/query/QueryExecutorIntegrationTest.java`
- `libs/query/src/test/java/airbridge/query/QueryCommandTest.java`

When changing slide behavior, update or inspect:

- `libs/slide/src/test/java/airbridge/slide/SlideImageCatalogTest.java`
- `libs/slide/src/test/java/airbridge/slide/SlideDirectoryChooserTest.java`
- `libs/slide/src/test/java/airbridge/slide/SlideSpinnerBehaviorTest.java`

When changing capture behavior, update or inspect:

- `libs/capture/src/test/java/airbridge/receiver/capture/CaptureOptionsTest.java`
- `libs/capture/src/test/java/airbridge/receiver/capture/CaptureServiceInternalTest.java`

Manual verification is often still needed for:

- Swing `slide` playback behavior
- real capture-device probing and live capture timing

## Build and Verification

Before finishing, run the strongest relevant verification command.

Preferred root command:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test
```

Useful narrower commands:

```bash
./gradlew :sender:test
./gradlew :receiver:test
./gradlew :common:test
./gradlew :capture:test
./gradlew :slide:test
./gradlew :packager:test
```

Use `clean` only when it is actually helpful. Do not claim completion if tests
were not run. If a hardware-dependent area could not be exercised, state that
explicitly.

## Working Guidance

- Check the live repo docs before editing behavior:
  - `README.ko.md`
  - `docs/dev/encode-decode.md`
  - `docs/dev/slide-capture.md`
  - `docs/dev/packager.md`
- Keep diffs scoped.
- Do not rewrite unrelated modules just to match a cleaner generic design.
- If behavior changes, update the matching repo docs, not only `AGENTS.md` or `docs/dev/codex/`.
