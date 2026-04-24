# ARCHITECTURE.md

## Current Module Layout

```text
root
├─ apps/
│  ├─ sender/
│  └─ receiver/
└─ libs/
   ├─ common/
   ├─ slide/
   ├─ capture/
   └─ packager/
```

This repository already has a concrete Gradle module split. Prefer using it as
the main architectural boundary instead of introducing generic imaginary layers
that do not exist in the codebase.

## Module Responsibilities

### `apps/sender`

- Picocli entrypoint for sender behavior
- `encode` command orchestration
- hidden `reencode` command orchestration
- direct `slide` launch handoff
- sender-specific defaults and source collection

Key classes:

- `airbridge.sender.Sender`
- `airbridge.sender.EncodeService`
- `airbridge.sender.SourceCollector`
- `airbridge.sender.FileEncodingPlan`
- `airbridge.sender.QrImageWriter`

### `apps/receiver`

- Picocli entrypoint for receiver behavior
- `decode` command orchestration
- `capture` command orchestration
- receiver-specific decode summaries and file assembly

Key classes:

- `airbridge.receiver.Receiver`
- `airbridge.receiver.DecodeService`
- `airbridge.receiver.FileChunks`
- `airbridge.receiver.QrDecodeSupport`

### `libs/common`

- shared CLI/banner/version helpers
- relative path safety helpers
- QR payload helpers
- compression/Base64 helpers

Key classes:

- `airbridge.common.QrPayloadSupport`
- `airbridge.common.CodecSupport`
- `airbridge.common.RelativePathSupport`
- `airbridge.common.CliSupport`

### `libs/slide`

- Swing slideshow UI
- image catalog loading and ordering
- directory chooser behavior

Key classes:

- `airbridge.slide.SlideApp`
- `airbridge.slide.SlideImageCatalog`
- `airbridge.slide.SlideDirectoryChooser`

### `libs/capture`

- live frame capture pipeline
- QR decode retries for capture flow
- dedupe, resume, and manifest writing

Key classes:

- `airbridge.receiver.capture.CaptureService`
- `airbridge.receiver.capture.CaptureOptions`
- `airbridge.receiver.capture.CaptureQrDecodeSupport`
- `airbridge.receiver.capture.CaptureSupport`

### `libs/packager`

- archive extension inspection
- packed zip rewrite
- unpack rewrite back to archive form

Key classes:

- `airbridge.packager.IdentifyCommand`
- `airbridge.packager.PackCommand`
- `airbridge.packager.UnpackCommand`
- `airbridge.packager.PackagerRewriter`

## Dependency Direction

Current build-level dependencies:

```text
sender   -> common, packager, slide
receiver -> common, capture, packager
slide    -> common
capture  -> zxing, javacv/opencv
packager -> picocli
common   -> picocli
```

Test-only dependency:

```text
receiver(test) -> sender
```

## Architectural Rules For New Work

- Keep sender-only command logic in `apps/sender`.
- Keep receiver-only command logic in `apps/receiver`.
- Keep reusable payload/path/codec helpers in `libs/common`.
- Keep archive rewrite behavior in `libs/packager`.
- Keep Swing-specific slideshow code in `libs/slide`.
- Keep live capture runtime concerns in `libs/capture`.

Avoid:

- moving capture logic into sender modules
- moving Swing UI code into `libs/common`
- inventing a generic transfer-archive layer unless the product actually gains
  one
- modeling fields such as transfer id or payload version as if they already
  exist in the current QR format

## Layering Inside Modules

Within a module, prefer:

- thin CLI entrypoint classes
- service classes for the real work
- small helper/value types close to the service that owns them

Examples:

- `Sender` should remain mostly orchestration.
- `EncodeService` and `DecodeService` should own transfer behavior.
- `SlideApp` can remain UI-heavy, but shared non-UI rules should move out only
  when there is a real reuse or testability need.
