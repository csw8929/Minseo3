# Changelog

All notable changes to this project are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions use 4-digit `MAJOR.MINOR.PATCH.MICRO`.

## [0.1.0.0] - 2026-04-22

### Added
- Reader runs in fullscreen by default; system bars auto-reveal on swipe and re-hide.
- Top "리스트" tap returns to the book list while keeping the current book in the
  task stack — pressing back from the list returns to the book.
- NAS settings screen and reader top bar (status row + menu row).

### Fixed
- Status bar no longer overlaps the reader's custom top bar.

### Documented
- CLAUDE.md: Windows + Git Bash screencap workflow for the dual-display device
  (save-then-pull, `MSYS_NO_PATHCONV=1`, `;` chaining, multi-display IDs).
