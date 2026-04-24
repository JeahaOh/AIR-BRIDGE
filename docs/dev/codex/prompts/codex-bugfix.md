# Codex Bugfix Task

Read first:

- `AGENTS.md`
- `docs/dev/codex/docs/FEATURE_CONTRACT.md`
- `docs/dev/codex/docs/BINARY_FORMAT.md`
- `docs/dev/codex/docs/TEST_STRATEGY.md`
- `docs/dev/codex/docs/DONE_DEFINITION.md`

Cross-check the live repo docs for the area you are touching:

- `docs/dev/encode-decode.md`
- `docs/dev/packager.md`
- `docs/dev/slide-capture.md`

## Bug

```text
<BUG_DESCRIPTION_HERE>
```

## Expected Investigation

1. Reproduce the bug if possible.
2. Identify the smallest failing scope.
3. Add a failing test first when practical.
4. Fix the root cause.
5. Run relevant tests.
6. Avoid unrelated refactoring.

## Bug Classes To Consider

- `QR_READ_ERROR`
- `INCOMPLETE`
- `DECODE_ERROR`
- `HASH_MISMATCH`
- `INVALID_PATH`
- packager suffix rewrite mismatch
- slide launch or playback regression
- capture resume or dedupe regression
- sender/receiver boundary violation

## Final Response

```text
Root cause:
- ...

Fix:
- ...

Files changed:
- ...

Tests:
- ...

Risks / Notes:
- ...
```
