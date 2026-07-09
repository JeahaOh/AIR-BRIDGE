# DATA_FLOW.md

## QR Transfer Flow

```text
source directory
  -> SourceCollector
  -> FileEncodingPlan per file
  -> GZIP bytes
  -> byte chunks by chunkDataSize
  -> binary payload frames
  -> QR PNG files (8-bit byte mode)
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
  -> GZIP bytes
  -> split into k source symbols of chunkDataSize bytes (last zero-padded)
  -> emit k systematic + ceil(k * repairOverhead) repair LT fountain symbols
  -> binary payload frame per symbol:
       magic 'A','B' + relPath + hash + k + gzipLen + esi + data
  -> ZXing QR render (8-bit byte mode, ISO-8859-1)
  -> PNG written to output directory
```

Additional encode outputs:

- `_manifest.txt`

Important current rules:

- symbol size is the gzip byte length per symbol (no Base64)
- repair symbols give the one-way channel loss tolerance (default repairOverhead 0.5)

## Decode Flow Details

Current `receiver decode` behavior:

```text
PNG files
  -> recursive PNG collection
  -> QR decode retries with rotations / binarizers / scales / crops
  -> parsed payload fields (one fountain symbol)
  -> grouped by relPath (k + gzipLen + hash16 + symbol size must agree)
  -> fountain decode: peel symbols until all k source symbols recovered
  -> reassemble gzip stream, trim to gzipLen
  -> GZIP inflate
  -> SHA-256 prefix compare
  -> RelativePathSupport safety check
  -> restored file write
  -> source PNG delete on success
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

## Optional Query Source Flow

`sender query` is a source-generation helper:

```text
DB SELECT/WITH results
  -> sender query
  -> CSV/report output directory
  -> sender encode --in <query output>
```

This flow is optional. The query output directory is one possible encode input,
not a required QR transfer stage.

## Ordering Rule

QR reconstruction must use payload metadata, not PNG filenames.

Current grouping fields:

- `relPath`
- `k`
- `gzipLen`
- `hash16`
- symbol size

Current symbol rule:

- `esi 0..k-1` systematic, `esi >= k` repair; symbols apply in any order, duplicates ignored

PNG filenames and label text are convenience only.

## Integrity Rule

Validate in roughly this order:

1. the PNG can be read as an image
2. QR payload bytes can be extracted (ISO-8859-1, 1:1)
3. the binary frame parses correctly
4. offer the symbol to the file's fountain decoder
5. all k source symbols are peeled (enough distinct symbols collected)
6. concatenated chunk data can be GZIP-inflated
7. restored bytes match the payload `hash16`
8. restored output path is safe under the output root
9. successful input PNGs are deleted from the input tree
