# BINARY_FORMAT.md

## Scope

This document covers the current QR payload contract used by:

- `sender encode`
- `receiver decode`
- helpers in `libs/common`

It does not describe the `identify`, `pack`, or `unpack` archive metadata
format.

## Current Compatibility State

There is no explicit payload version field today.

That means:

- field order changes are breaking
- separator changes are breaking
- header growth is breaking unless sender and receiver are updated together
- tests and docs must change in the same patch as payload changes

Do not write new code as if version negotiation already exists.

## Payload Layout

Current payload shape:

```text
"HDR" + HEADER_SEP + header + HEADER_SEP + chunkData
```

Constants from `QrPayloadSupport`:

```text
HEADER_SEP = U+001E
FIELD_SEP  = U+001F
```

## Header Fields

Current header field order:

1. `project`
2. `relPath`
3. `chunkIdx`
4. `totalChunks`
5. `hash16`

Encoded form:

```text
project FIELD_SEP relPath FIELD_SEP chunkIdx FIELD_SEP totalChunks FIELD_SEP hash16
```

Important current facts:

- `chunkIdx` is one-based
- `hash16` is the first 16 hex characters of SHA-256
- `project` is a grouping label, not a guaranteed-unique transfer id

## Chunk Data Rule

`chunkData` is not raw file bytes.

Current pipeline:

```text
file bytes
  -> optional office conversion
  -> GZIP
  -> Base64 string
  -> substring slices of length chunkDataSize
```

Important current fact:

- `chunkDataSize` counts characters in the encoded string, not bytes in the
  original file

## Decode Grouping Rule

Current decode logic treats chunks as belonging to the same file when these
fields match:

- `project`
- `relPath`
- `totalChunks`
- `hash16`

Duplicate chunk indexes currently overwrite earlier chunk data for that slot.

## Integrity Rule

Current integrity check is:

1. read QR payload string from PNG
2. parse the five header fields
3. ensure `chunkIdx` is in range
4. collect all required chunks
5. concatenate `chunkData`
6. Base64-decode and GZIP-inflate
7. compute SHA-256 and compare the first 16 hex chars with `hash16`
8. validate restore path under the output root

There is currently no explicit frame checksum field beyond what QR generation
and payload hashing already provide.

## Non-Authoritative Metadata

These items are useful but are not the source of truth for reconstruction:

- PNG filename such as `sample_txt_001of010.png`
- label text rendered under the QR image
- output directory ordering

Decode must keep relying on payload metadata, not filenames.

## Image-Level Notes

Current QR images are produced with:

- ZXing QR generation
- configurable QR image size
- configurable QR error correction level
- configurable label height

Rotation handling exists on the decode side through retry strategies, not
through an explicit orientation marker in the payload header.

## Compatibility Checklist

Before changing the QR payload contract:

- [ ] sender encode updated
- [ ] receiver decode updated
- [ ] `libs/common` helpers updated
- [ ] `docs/dev/encode-decode.md` updated
- [ ] `AGENTS.md` and `docs/dev/codex/` updated if they describe the changed behavior
- [ ] round-trip and payload tests updated
- [ ] any old fixture or compatibility assumption re-checked
