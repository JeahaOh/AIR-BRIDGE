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

- field order / framing changes are breaking
- header growth is breaking unless sender and receiver are updated together
- tests and docs must change in the same patch as payload changes

Do not write new code as if version negotiation already exists.

## Payload Layout

The payload carries gzip bytes directly in QR 8-bit byte mode (no Base64). It is
a binary frame, not a text-separator string. All integers are big-endian.

```text
magic    : 2 bytes  'A','B'
relPath  : u16 length + UTF-8 bytes
chunkIdx : u32
total    : u32
hash     : 8 bytes  (first 8 bytes / 16 hex chars of the file SHA-256)
data     : remaining bytes (current chunk's gzip window)
```

Built and parsed by `QrPayloadSupport.buildPayload(...)` /
`QrPayloadSupport.parsePayload(byte[])`.

## Header Fields

Important current facts:

- `chunkIdx` is one-based
- `hash` is the first 8 bytes (16 hex chars) of SHA-256; `parsePayload` exposes it
  as the 16-char lowercase hex `hash16`
- `relPath` length ≤ 65535 bytes (UTF-8)

## Chunk Data Rule

`data` is not raw file bytes.

Current pipeline:

```text
file bytes
  -> optional office conversion
  -> GZIP
  -> byte slices of length chunkDataSize
```

Important current fact:

- `chunkDataSize` counts bytes of the gzip stream (no Base64 inflation)

## Decode Grouping Rule

Current decode logic treats chunks as belonging to the same file when these
fields match:

- `relPath`
- `totalChunks`
- `hash16`

Duplicate chunk indexes currently overwrite earlier chunk data for that slot.

## Integrity Rule

Current integrity check is:

1. read QR payload bytes from PNG (ISO-8859-1 recovers bytes 1:1)
2. parse the binary frame (magic + header fields)
3. ensure `chunkIdx` is in range
4. collect all required chunks
5. concatenate the chunk `data` bytes
6. GZIP-inflate
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

- ZXing QR generation in 8-bit byte mode (ISO-8859-1 charset, no ECI segment)
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
