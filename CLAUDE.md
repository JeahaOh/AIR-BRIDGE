# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`air-bridge` moves files across air-gapped environments by converting them into a
sequence of QR-code PNGs and restoring them on the receiving side. It ships as two
paired fat-jar apps, `sender` and `receiver`, built from one Gradle multi-module project.

Two distinct flows — do not conflate them:

- **QR transfer pipeline:** `encode` → `slide` → `capture` → `decode`. Sender turns files
  into QR PNGs, displays them, receiver captures and rebuilds them.
- **Package helper flow:** `identify` → `pack` → `unpack`. A helper for `jar`/`zip`
  artifacts (used to prepare `sender` for moving into a target). `pack`/`unpack` are
  **not** generic archive stages of the QR pipeline.

Read `AGENTS.md` first — it holds the authoritative behavior contract and constraints.
This file is the quick-start layer on top of it.

## Build & test

The repo pins a project-local Gradle home; always prefix Gradle with it:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test       # full test suite
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew clean build # build both fat jars
```

Per-module tests (project names differ from directory names — see `settings.gradle`):

```bash
./gradlew :sender:test :receiver:test :common:test
./gradlew :capture:test :slide:test :packager:test
```

Run a single test class / method:

```bash
./gradlew :common:test --tests 'airbridge.common.QrPayloadSupportTest'
./gradlew :receiver:test --tests 'airbridge.receiver.DecodeServiceTest.someMethod'
```

Lightweight encode/decode round-trip benchmark (no JMH; reuses the test classpath):

```bash
./gradlew :receiver:benchmark -Pbench.fileCount=20 -Pbench.fileSizeKb=4096 \
    -Pbench.chunkSize=2000 -Pbench.compressible=false -Pbench.maxHeapMb=256
```

Build artifacts land in `build/libs/sender-<version>.jar` and `receiver-<version>.jar`.
Version is a build-time timestamp: `0.9.<yyMMdd.HHmm>`.

## Running

Both jars dispatch on args: **no command → GUI opens; a command or CLI option → CLI**.

```bash
java -jar build/libs/sender-<version>.jar --help     # sender: encode, gui, slide, unpack, reencode (hidden)
java -jar build/libs/receiver-<version>.jar --help   # receiver: decode, capture, gui, identify, pack
```

When `--in`/`--out` are omitted, jar-relative default directories are used
(`source`/`encoded`/`captured`/`decoded`), overridable via an `airbridge-paths.properties`
file next to the jar. The GUI fills the same defaults.

## Module layout & dependency direction

```
apps/sender    -> common, packager, slide   (Picocli; encode/slide/unpack/hidden reencode)
apps/receiver  -> common, capture, packager  (Picocli; decode/capture/identify/pack)
libs/common    -> shared QR payload + QR image decoder, path-safety, codec, CLI, banner, version (+zxing)
libs/slide     -> common  (Swing slideshow UI for QR playback)
libs/capture   -> common + zxing + javacv/opencv  (camera/UVC capture pipeline, QR dedupe)
libs/packager  -> picocli  (identify/pack/unpack archive-rewrite helpers)
```

- Sender code must not depend on capture runtime; receiver must not depend on sender-only
  UI. Shared code goes in `libs/common`.
- **Test-only exception:** `apps/receiver` tests depend on `:sender` for round-trip coverage.
- Entrypoints are `airbridge.sender.Sender` and `airbridge.receiver.Receiver`. CLI commands
  are Picocli subcommands; messages are localized via the `Messages` resource bundle
  (`--lang ko|en`).

## Constraints that must hold (from AGENTS.md)

- **Air-gap first:** no network calls, telemetry, cloud sync, update checks, or remote
  logging. Core behavior works fully offline.
- **Path safety:** decode/unpack must never write outside the selected output dir —
  preserve `RelativePathSupport`-style checks when touching restoration logic.
- **Payload compatibility:** the QR carries gzip bytes directly in QR 8-bit byte mode (no
  Base64). Each frame is one **LT fountain symbol** (see `libs/common/.../fountain`), framed as
  a binary record in `QrPayloadSupport`: `magic 'A','B'` + u16-len `relPath` + 8-byte `hash`
  (first 16 hex chars of the SHA-256) + u32 `k` (source-symbol count) + u32 `gzipLen` + u32
  `esi` (encoding symbol id) + raw `symbol`. `esi 0..k-1` are systematic (the source symbols
  verbatim), `esi >= k` are repair symbols (XOR of a deterministic source subset). Encoder/
  decoder use the `ISO-8859-1` charset so bytes survive 1:1 with no ECI. There is **no** version
  field, transfer id, or frame checksum — do not invent one. Fountain neighbor generation must
  stay cross-platform deterministic (Ideal Soliton; no `Math.log`/`sqrt`). Any frame change
  touches sender, receiver, tests, and docs together.
- **Decode semantics:** symbols group by `relPath` (with `k`/`gzipLen`/`hash16` agreeing), not
  PNG filename; a file rebuilds once the fountain decoder peels enough distinct symbols (~`k`,
  a bit more under loss — any frames work, no specific one is required). Successful decode moves
  source PNGs into sibling `*-success` dirs; not-enough-symbols (INCOMPLETE) / QR-read failures
  / hash mismatches / invalid paths are explicit first-class outcomes. `reencode` re-emits a
  failed file's whole symbol stream (no per-chunk granularity).

## When changing behavior

Update the matching tests (see AGENTS.md "Testing Expectations" for the exact list per
area) and the live repo docs (`README.ko.md`, `docs/dev/encode-decode.md`,
`docs/dev/slide-capture.md`, `docs/dev/packager.md`) — not only AGENTS.md. Swing `slide`
playback and real capture-device probing still need manual verification; say so explicitly
when a hardware-dependent area could not be exercised.
