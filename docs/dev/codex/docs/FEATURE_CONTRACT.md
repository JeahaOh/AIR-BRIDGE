# FEATURE_CONTRACT.md

## `sender encode`

### Input

- source directory
- output directory
- project name
- target extension list
- skip-dir and exclude-path filters
- chunk size / QR size / label height options
- optional office conversion flags

### Output

- QR PNG files
- `_manifest.txt`
- optional `_print.html`
- console summary

### Must Validate

- source directory exists
- filtered source file list is not empty
- numeric options stay within minimum bounds
- encoded relative paths stay under the chosen encode root

---

## `receiver decode`

### Input

- input directory containing QR PNG files
- restore output directory
- decode worker count

### Output

- restored files
- `_restore_result.txt`
- sibling `*-success` directories for successfully consumed PNGs
- console summary

### Must Validate

- input directory exists
- at least one PNG is found
- QR payload parses correctly
- chunk indexes are valid
- chunk set completeness
- restored byte hash matches payload `hash16`
- output paths stay under the restore root

---

## `sender slide`

### Input

- image directory chosen through the Swing UI
- page timing
- black-frame timing
- loop count

### Output

- displayed image sequence
- UI state updates

### Must Support

- browse and reload
- play and pause
- previous and next navigation
- page jumps
- full-screen toggle
- tree-based image selection

### Notes

- current implementation is Swing-first, not a headless CLI service

---

## `receiver capture`

### Input

- output directory
- capture device index
- width / height / fps
- duration / max payloads
- decode worker count
- same-signal cutoff
- resume flag

### Output

- `captured-images/frame_000001.png` style files
- `capture-manifest.json`
- console status logs

### Must Validate

- option minimum values
- device availability when actually capturing
- duplicate payloads are not saved twice
- resume state is restored safely
- stop reason is recorded

---

## `receiver identify`

### Input

- input `jar` or `zip`

### Output

- extension list on stdout
- `target-ext.txt` written in the archive directory

### Must Validate

- input archive exists and is readable
- extension tokens are normalized and filtered

### Must Not

- mutate the input archive
- claim QR frame metadata

---

## `receiver pack`

### Input

- input `jar` or `zip`
- optional existing `target-ext.txt` in the same directory

### Output

- packed `.zip`
- embedded pack metadata
- console summary

### Must Validate

- input archive exists and is readable
- target extension list is normalized
- blocked extensions stay excluded
- selected archive entries are rewritten consistently

---

## `sender unpack`

### Input

- packed `.zip`

### Output

- unpacked archive with original names restored
- `.jar` output when the packed archive represented a jar
- console summary

### Must Validate

- embedded target-ext metadata exists
- archive rewrite succeeds before replacing outputs

---

## `sender reencode` (hidden)

### Input

- source directory
- output directory
- `_restore_result.txt`
- encode options compatible with the original source layout

### Output

- regenerated QR PNG files for failed files or missing chunks
- console summary

### Must Validate

- restore result lines parse correctly
- requested relative paths resolve safely under the source root
- converted-path mapping stays consistent with encode rules
