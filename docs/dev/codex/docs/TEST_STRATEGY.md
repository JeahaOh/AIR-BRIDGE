# TEST_STRATEGY.md

## Test Priority

Highest priority in this repository:

1. QR payload and path-safety helpers in `libs/common`
2. `sender encode` behavior
3. `receiver decode` round-trip and failure classification
4. package helper behavior in `libs/packager`
5. slide file ordering and chooser behavior
6. capture option normalization and internal pipeline behavior

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
- QR payload parsing
- chunk index bounds
- missing chunks
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
- success-folder side effects when decode is expected to move source PNGs
