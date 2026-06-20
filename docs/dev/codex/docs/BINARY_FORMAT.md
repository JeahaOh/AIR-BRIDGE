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

Each frame is one LT fountain symbol of the file's gzip stream.

```text
magic    : 2 bytes  'A','B'
relPath  : u16 length + UTF-8 bytes
hash     : 8 bytes  (first 8 bytes / 16 hex chars of the file SHA-256)
k        : u32  (number of source symbols the gzip stream was split into)
gzipLen  : u32  (gzip stream length; trims the padded last source symbol)
esi      : u32  (encoding symbol id; 0..k-1 = systematic source, >=k = repair)
data     : remaining bytes (one symbol; symbolSize == data.length, constant per file)
```

Built and parsed by `QrPayloadSupport.buildPayload(...)` /
`QrPayloadSupport.parsePayload(byte[])`.

## Header Fields

Important current facts:

- `esi 0..k-1` are systematic (the source symbols verbatim); `esi >= k` are repair
  symbols (XOR of a deterministic source subset derived from `esi`+`k`)
- `hash` is the first 8 bytes (16 hex chars) of SHA-256; `parsePayload` exposes it
  as the 16-char lowercase hex `hash16`
- `relPath` length ≤ 65535 bytes (UTF-8)

## Symbol Data Rule

`data` is not raw file bytes.

Current pipeline:

```text
file bytes
  -> optional office conversion
  -> GZIP
  -> split into k source symbols of chunkDataSize bytes (last zero-padded)
  -> emit symbol esi: systematic (esi<k) or XOR of source neighbors (esi>=k)
```

Important current facts:

- `chunkDataSize` is the symbol size, counting gzip-stream bytes (no Base64 inflation)
- fountain neighbor generation is cross-platform deterministic (Ideal Soliton; only
  java.util.Random + IEEE division/ceil, no Math.log/sqrt)

## Decode Grouping Rule

Current decode logic treats symbols as belonging to the same file when these
fields match:

- `relPath`
- `k`
- `gzipLen`
- `hash16`
- symbol size

A file decodes once the fountain decoder peels all `k` source symbols (any distinct
symbols work; ~`k`, slightly more under loss). Duplicate `esi` values are ignored.

## Integrity Rule

Current integrity check is:

1. read QR payload bytes from PNG (ISO-8859-1 recovers bytes 1:1)
2. parse the binary frame (magic + header fields)
3. offer the symbol to the file's fountain decoder (keyed by relPath)
4. once all `k` source symbols are peeled, reassemble the gzip stream and trim to `gzipLen`
5. GZIP-inflate
6. compute SHA-256 and compare the first 16 hex chars with `hash16`
7. validate restore path under the output root

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
