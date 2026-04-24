# Codex Refactor Task

Read first:

- `AGENTS.md`
- `docs/dev/codex/docs/ARCHITECTURE.md`
- `docs/dev/codex/docs/DONE_DEFINITION.md`
- the live repo docs for the area you are refactoring

## Refactor Goal

```text
<REFACTOR_GOAL_HERE>
```

## Constraints

- Do not change observable behavior.
- Do not change the QR payload layout.
- Do not change packager rewrite metadata or `target-ext.txt` behavior.
- Do not weaken validation.
- Do not merge Sender and Receiver responsibilities.
- Keep diffs scoped.
- Preserve or improve test coverage.
- Do not break the current direct `slide` launch path unless the refactor
  explicitly includes that behavior.

## Required Checks

Before editing, identify:

- current classes involved
- owning Gradle modules
- dependency direction
- public APIs or CLI behavior affected
- tests that should remain green

After editing, run tests.

## Final Response

```text
Summary:
- ...

Behavior changes:
- None / explain if any

Files changed:
- ...

Tests:
- ...

Risks / Notes:
- ...
```
