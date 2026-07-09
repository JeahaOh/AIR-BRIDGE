# TEST_STRATEGY.md

## Test Priority

Highest priority in this repository:

1. QR payload and path-safety helpers in `libs/common`
2. `sender encode` behavior
3. `receiver decode` round-trip and failure classification
4. package helper behavior in `libs/packager`
5. query extraction behavior in `libs/query`
6. slide file ordering and chooser behavior
7. capture option normalization and internal pipeline behavior

## Current High-Value Tests

Encode/decode and shared helpers:

- `libs/common/src/test/java/airbridge/common/QrPayloadSupportTest.java`
- `libs/common/src/test/java/airbridge/common/CodecSupportTest.java`
- `libs/common/src/test/java/airbridge/common/RelativePathSupportTest.java`
- `apps/sender/src/test/java/airbridge/sender/EncodeServiceTest.java`
- `apps/sender/src/test/java/airbridge/sender/SenderCliTest.java`
- `apps/receiver/src/test/java/airbridge/receiver/DecodeServiceTest.java`
- `apps/receiver/src/test/java/airbridge/receiver/ReceiverRoundTripTest.java`
- `apps/receiver/src/test/java/airbridge/receiver/ReceiverCliTest.java`

Package helpers:

- `libs/packager/src/test/java/airbridge/packager/PackagerAppTest.java`

Query:

- `libs/query/src/test/java/airbridge/query/QueryConfigTest.java`
- `libs/query/src/test/java/airbridge/query/QueryParserTest.java`
- `libs/query/src/test/java/airbridge/query/QueryExecutorIntegrationTest.java`
- `libs/query/src/test/java/airbridge/query/QueryCommandTest.java`
- `libs/query/src/test/java/airbridge/query/QueryJdbcDriverTest.java`

Slide:

- `libs/slide/src/test/java/airbridge/slide/SlideImageCatalogTest.java`
- `libs/slide/src/test/java/airbridge/slide/SlideDirectoryChooserTest.java`
- `libs/slide/src/test/java/airbridge/slide/SlideSpinnerBehaviorTest.java`

Capture:

- `libs/capture/src/test/java/airbridge/receiver/capture/CaptureOptionsTest.java`
- `libs/capture/src/test/java/airbridge/receiver/capture/CaptureServiceInternalTest.java`
- `libs/capture/src/test/java/airbridge/receiver/capture/CaptureQrDecodeSupportTest.java`

## What To Test By Area

### Encode / Decode / Common

Add or update tests for:

- nested directory round-trip
- text file and binary file round-trip
- empty payload edge cases when relevant
- QR payload parsing (including implausible frame-field rejection)
- fountain peel behavior: duplicate esi ignored, repair symbols compensating
  for lost source symbols (`LtFountainTest`, `LtPeelTrackerTest`)
- insufficient distinct symbols -> INCOMPLETE
- hash mismatch
- QR read error handling
- invalid or unsafe relative paths
- reencode input parsing if `reencode` logic changes

### Packager

Add or update tests for:

- extension inference and filtering
- `target-ext.txt` handling
- packed zip rewrite
- unpack reversal
- jar reconstruction when manifest is present

### Query

Add or update tests for:

- config parsing and password source precedence
- SQL parsing and SELECT/WITH filtering
- CSV/report output
- command help/init/list modes
- bundled JDBC driver class availability

### Slide

Add or update tests for:

- supported image discovery
- ordering rules such as `session-start` / `session-end`
- chooser behavior
- spinner input validation

### Capture

Add or update tests for:

- option normalization
- resume-state restoration
- duplicate payload handling
- manifest writing helpers
- internal decode pipeline behavior that does not require hardware

## Manual Verification

Manual checks are still important for:

- Swing `slide` playback timing and focus behavior
- `capture --list-devices`
- live capture from a real device or board

If hardware is unavailable, say so explicitly and keep automated coverage as
strong as possible around the non-hardware logic.

## Existing Fixtures

Useful repo fixtures:

```text
fixtures/samples/
fixtures/test-image/
fixtures/test-image-encode/
```

Use small focused temporary fixtures in tests unless a checked-in fixture is
already the best fit.

## Round-Trip Assertion

For a full `encode -> decode` round-trip, compare:

- restored file count
- restored relative paths
- restored file bytes
- decode report contents in `_restore_result.txt`
- successful input PNG deletion side effects when decode restores files
