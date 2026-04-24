# Codex Test Task

Read first:

- `AGENTS.md`
- `docs/dev/codex/docs/TEST_STRATEGY.md`
- `docs/dev/codex/docs/FEATURE_CONTRACT.md`
- `docs/dev/codex/docs/BINARY_FORMAT.md`
- relevant live repo docs for the area under test

## Test Target

```text
<TEST_TARGET_HERE>
```

## Required Test Categories

Add or improve tests for:

- success case
- invalid input
- corrupted input
- boundary case
- compatibility-sensitive case if QR payload behavior is involved

## For The QR Transfer Pipeline

Prefer round-trip tests:

```text
encode -> decode
```

Validate:

- restored paths
- restored file count
- restored file bytes
- `_restore_result.txt` contents
- QR count or chunk expectations when relevant
- success-folder side effects when relevant

## For The Package Helper Flow

Prefer:

```text
identify -> pack -> unpack
```

Validate:

- `target-ext.txt` handling
- packed archive existence
- unpacked entry-name restoration
- jar reconstruction when relevant

## Final Response

```text
Tests added:
- ...

Files changed:
- ...

Verification:
- ...

Coverage gaps:
- ...
```
