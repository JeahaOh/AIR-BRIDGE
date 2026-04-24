# CODEX_CONTEXT.md

## Purpose

`air-bridge` moves files across an air gap by converting them into QR PNG
sequences on the sender side and restoring them from QR PNG sequences on the
receiver side.

It assumes offline operation. Core behavior must not require network access,
shared storage, or any online service.

The intended transfer model is:

```text
Machine A
  sender encode
  -> QR PNG set
  -> sender slide or another display path

Machine B
  receiver capture or imported PNG set
  -> receiver decode
  -> restored files
```

## Applications

### Sender

Current responsibilities:

- collect source files from a directory
- optionally convert selected office formats before encoding
- compress and Base64-encode file content
- split encoded data into QR payload chunks
- render QR PNG files plus `_manifest.txt`
- optionally generate `_print.html`
- launch the Swing slideshow app for playback
- unpack a previously packed `zip` back into its original archive shape
- regenerate failed QR chunks with hidden `reencode`

### Receiver

Current responsibilities:

- collect PNG files for decode from a directory tree
- decode QR payloads from PNG images
- reconstruct files and write `_restore_result.txt`
- move successfully consumed PNGs into sibling `*-success` folders
- capture QR frames from a camera/UVC source into `captured-images/`
- write `capture-manifest.json`
- inspect archive extensions with `identify`
- rewrite archive entries with `pack`

## Current Command Map

### `sender encode`

Takes a source directory and produces:

- QR PNG files
- `_manifest.txt`
- optional `_print.html`

Important implementation fact:

- encode works directly from source files, not from a packaged transfer archive

### `receiver decode`

Takes a directory of QR PNG files and produces:

- restored files under the output root
- `_restore_result.txt`
- sibling `*-success` folders for successfully consumed PNGs

### `sender slide`

Launches the Swing slideshow app from `libs/slide`.

Important implementation fact:

- `Sender.main(...)` detects the `slide` token early and launches `SlideApp`
  directly instead of treating it like a normal Picocli-only command flow

### `receiver capture`

Captures QR images from a device source and writes:

- `captured-images/frame_000001.png` style files
- `capture-manifest.json`

### `receiver identify`

Reads a `jar` or `zip`, prints included extensions, and writes `target-ext.txt`
next to the input archive.

### `receiver pack`

Reads a `jar` or `zip`, selects target entries by extension, rewrites selected
entry names with a `.txt` suffix, embeds pack metadata, and writes a new
packed `.zip`.

### `sender unpack`

Reads a previously packed `.zip`, removes embedded `.txt` suffix rewrites using
embedded metadata, and converts back to `.jar` when appropriate.

## Critical Behaviors

- QR payload metadata is currently very small: project name, relative path,
  chunk index, total chunks, and a 16-hex SHA-256 prefix.
- The current payload has no explicit format version field.
- Decode ordering and grouping come from payload metadata, not PNG filenames.
- Path traversal and unsafe restore paths must be rejected.
- `identify`, `pack`, and `unpack` operate on archive entry names, not on QR
  payload contents.
