# DONE_DEFINITION.md

## Done Definition

A task is complete only when the relevant repository behavior, tests, and docs
all line up.

## Functional Completion

- [ ] Requested behavior implemented.
- [ ] Change lives in the right module or modules.
- [ ] Sender/receiver boundary preserved.
- [ ] Air-gap behavior preserved.
- [ ] No mandatory network dependency added.

## Integrity Completion

- [ ] Payload compatibility reviewed if `encode`, `decode`, or `libs/common`
      changed.
- [ ] Path safety preserved for any restored or unpacked file writes.
- [ ] Failure outcomes stay explicit where relevant:
      `INCOMPLETE`, `DECODE_ERROR`, `HASH_MISMATCH`, `INVALID_PATH`,
      `QR_READ_ERROR`, and similar.
- [ ] `*-success` move behavior reviewed if decode touched file-finalization
      logic.
- [ ] Packager metadata safety reviewed if `identify`, `pack`, or `unpack`
      changed.

## Test Completion

- [ ] Relevant module tests added or updated.
- [ ] Relevant automated verification commands run.
- [ ] Manual verification note included for `slide` or `capture` changes when
      hardware or GUI behavior is involved.
- [ ] Exact reason documented if any intended test could not be run.

## Documentation Completion

- [ ] `README.ko.md` or other top-level docs updated if the CLI surface changed.
- [ ] `docs/dev/encode-decode.md` updated if payload, encode, decode, or
      reencode behavior changed.
- [ ] `docs/dev/slide-capture.md` updated if slide or capture behavior changed.
- [ ] `docs/dev/packager.md` and `docs/user/packager.md` updated if identify, pack, or unpack behavior
      changed.
- [ ] `AGENTS.md` and `docs/dev/codex/` updated if they are meant to remain current.

## Final Response Format

Report completion in a compact format such as:

```text
Summary:
- ...

Files changed:
- ...

Verification:
- ...

Risks / Notes:
- ...
```
