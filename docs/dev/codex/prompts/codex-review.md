# Codex Code Review Task

Read first:

- `AGENTS.md`
- `docs/dev/codex/docs/ARCHITECTURE.md`
- `docs/dev/codex/docs/FEATURE_CONTRACT.md`
- `docs/dev/codex/docs/BINARY_FORMAT.md`
- `docs/dev/codex/docs/TEST_STRATEGY.md`
- the live repo docs that match the review target

## Review Target

```text
<FILES_OR_BRANCH_OR_DIFF_HERE>
```

## Review Focus

Check for:

- air-gap violations
- mandatory network dependency
- sender/receiver boundary leak
- binary format compatibility issue
- QR payload compatibility issue
- path traversal or unsafe restore path
- weak error handling or missing restore result classification
- untestable UI-coupled core logic
- missing tests
- large unrelated diff
- hidden global state
- packager rewrite mismatch
- platform-specific behavior not isolated

## Output Format

```text
Critical:
- ...

Major:
- ...

Minor:
- ...

Recommended tests:
- ...

Suggested patches:
- ...
```
