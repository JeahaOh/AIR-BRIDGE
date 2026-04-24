# Codex Feature Task

Read first:

- `AGENTS.md`
- `docs/dev/codex/docs/CODEX_CONTEXT.md`
- `docs/dev/codex/docs/ARCHITECTURE.md`
- `docs/dev/codex/docs/DATA_FLOW.md`
- `docs/dev/codex/docs/FEATURE_CONTRACT.md`
- `docs/dev/codex/docs/BINARY_FORMAT.md`
- `docs/dev/codex/docs/TEST_STRATEGY.md`
- `docs/dev/codex/docs/DONE_DEFINITION.md`

Also read the matching live repo docs for the affected area:

- `README.ko.md`
- `docs/dev/encode-decode.md`
- `docs/dev/packager.md`
- `docs/dev/slide-capture.md`

## Task

Implement the following feature:

```text
<FEATURE_DESCRIPTION_HERE>
```

## Required Behavior

- Keep the change scoped.
- Preserve air-gap constraints.
- Preserve Sender/Receiver separation.
- Do not change the QR payload layout or packager rewrite format unless it is
  required.
- Remember that the current QR payload has no explicit format version field.
- If payload behavior changes, update `docs/dev/encode-decode.md`,
  `AGENTS.md` or `docs/dev/codex/` if they describe it, and tests together.
- Add or update tests for the behavior.

## Verification

Run:

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew test
```

If a narrower module command is more appropriate, run that plus explain why.

## Final Response

Use this format:

```text
Summary:
- ...

Files changed:
- ...

Tests:
- ...

Risks / Notes:
- ...
```
