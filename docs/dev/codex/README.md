# air-bridge Codex Guide

This directory contains repo-local Codex support docs for the current
`air-bridge` codebase. Repo-wide rules live in `AGENTS.md`, detailed support
docs and prompts live here, and verification scripts live in `scripts/`.

## Scope

- `apps/sender` and `apps/receiver`
- `libs/common`, `libs/slide`, `libs/capture`, and `libs/packager`
- the QR transfer flow: `encode -> slide -> capture -> decode`
- the package helper flow: `identify -> pack -> unpack`
- the current Gradle test workflow for this repository

## Included Files

```text
AGENTS.md
docs/dev/codex/
  README.md
  docs/
    CODEX_CONTEXT.md
    ARCHITECTURE.md
    DATA_FLOW.md
    FEATURE_CONTRACT.md
    BINARY_FORMAT.md
    TEST_STRATEGY.md
    DONE_DEFINITION.md
  prompts/
    codex-feature.md
    codex-bugfix.md
    codex-refactor.md
    codex-review.md
    codex-test.md
scripts/
  codex-verify.sh
  codex-verify.ps1
```

## Recommended Use

1. Read `AGENTS.md` first.
2. Pick one prompt from `docs/dev/codex/prompts/` that matches the task.
3. Cross-check the live repository docs for the area you are touching.
4. Run `scripts/codex-verify.sh` or `scripts/codex-verify.ps1`.

Useful live repo docs:

- `README.ko.md`
- `docs/dev/encode-decode.md`
- `docs/dev/packager.md`
- `docs/dev/slide-capture.md`

## Project-Specific Reminders

- `encode` and `decode` operate on source files and QR PNG sets directly. They
  do not currently go through a binary transfer archive.
- `identify`, `pack`, and `unpack` are helper commands for `jar` or `zip`
  artifacts and are separate from the QR round-trip.
- The current QR payload has no explicit format version field. Any payload
  change is a compatibility change and must be coordinated across sender,
  receiver, tests, and docs.
- `slide` is a Swing UI and `capture` depends on JavaCV/OpenCV, so those areas
  often need some manual verification in addition to unit tests.
