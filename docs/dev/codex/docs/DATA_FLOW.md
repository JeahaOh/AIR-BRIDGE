# DATA_FLOW.md

## QR Transfer Flow

```text
source directory
  -> SourceCollector
  -> FileEncodingPlan per file
  -> GZIP + Base64 string
  -> string chunks by chunkDataSize
  -> HDR payload strings
  -> QR PNG files
  -> sender slide or another display path
  -> receiver capture or imported PNG files
  -> payload decode
  -> FileChunks grouping
  -> restored files
```

## Encode Flow Details

Current `sender encode` behavior:

```text
source file
  -> optional office conversion
  -> SHA-256 over converted bytes
  -> GZIP + Base64 string
  -> split by chunkDataSize characters
  -> payload:
       HDR + HEADER_SEP + header + HEADER_SEP + chunkData
  -> ZXing QR render
  -> PNG written to output directory
```

Additional encode outputs:

- `_manifest.txt`
- `_print.html` when `--print-html` is enabled

Important current rule:

- chunking is based on encoded string length, not on raw byte length

## Decode Flow Details

Current `receiver decode` behavior:

```text
PNG files
  -> recursive PNG collection
  -> QR decode retries with rotations / binarizers / scales / crops
  -> parsed payload fields
  -> grouped by relPath + project + totalChunks + hash16
  -> missing chunk check
  -> concatenate chunkData strings
  -> Base64 decode + GZIP inflate
  -> SHA-256 prefix compare
  -> RelativePathSupport safety check
  -> restored file write
  -> source PNG move to sibling *-success directory on success
```

Primary reports:

- `_restore_result.txt`
- console summary lines

## Capture Flow

Current `receiver capture` flow:

```text
camera/UVC input
  -> frame grab
  -> fingerprint analysis
  -> stable-signal decision
  -> QR decode worker pool
  -> payload dedupe
  -> save PNG into captured-images/
  -> write capture-manifest.json
```

Important current rule:

- duplicate payloads are intentionally skipped

## Package Helper Flow

`identify`, `pack`, and `unpack` are a separate flow:

```text
jar/zip
  -> identify
  -> target-ext.txt
  -> pack
  -> packed zip with rewritten entry names
  -> unpack
  -> restored zip/jar
```

These commands do not participate in QR payload generation or reconstruction.

## Ordering Rule

QR reconstruction must use payload metadata, not PNG filenames.

Current grouping fields:

- `project`
- `relPath`
- `totalChunks`
- `hash16`

Current chunk index rule:

- one-based chunk indexes

PNG filenames and label text are convenience only.

## Integrity Rule

Validate in roughly this order:

1. the PNG can be read as an image
2. a QR payload string can be extracted
3. payload header fields parse correctly
4. chunk indexes are in range
5. all required chunks are present
6. concatenated chunk data can be Base64-decoded and GZIP-inflated
7. restored bytes match the payload `hash16`
8. restored output path is safe under the output root
9. successful input PNGs are moved to the matching `*-success` directory
