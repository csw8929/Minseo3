# TODOS

Items grouped by component, sorted by priority (P0 highest).
Completed items move to the bottom under `## Completed`.

---

## Reader

### Move NasSyncManager.push off the main thread
**Priority:** P1
**Why:** `ReaderActivity.flushSaveNow()` runs synchronously in `onPause()` and calls
`nasSyncManager.push(...)`. Today `push()` is a stub, so this is harmless. As soon as
`push()` does real network I/O it will ANR `onPause`. Fix in NasSyncManager so callers
don't have to think about threading.
**Acceptance:** `push()` returns immediately; the actual upload happens on a single
background thread inside NasSyncManager. `flushSaveNow()` continues to write the local
progress synchronously (BookList.onResume depends on that).

---

## Build / Workflow

### Decide whether to delete `origin/dev`
**Priority:** P3
**Why:** `main` was created from `dev` and is now the default branch on GitHub.
`origin/dev` still points at the old tip. Either delete it or keep it as a long-running
branch. No code impact; just housekeeping.

---

## Completed

(none yet)
