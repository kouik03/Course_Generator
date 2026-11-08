# Changelog — Course Generator

This file tracks source-level changes to the development version. Update it alongside every future code change; user-facing updates should also appear in the corresponding French and English `src/course_generator/releaseNote/releaseNote_*.html` files.

## 5.0.0 — development (November 2026)

### 2026-11-08 — Configuration XML and release notes
- Fixed the SAX configuration parser: empty XML elements, especially `RELEASEVERSION`, now load as empty strings without inheriting indentation whitespace.
- Accumulate successive SAX `characters()` fragments instead of discarding earlier text. The V5 release-note preference smoke test now checks empty tags and split CDATA.
- Updated the startup release notes in French and English to cover fixes merged since the initial 5.0.0 notes.

### 2026-11-08 — Swing UI freeze after sleep/wake (PR #12)
- Moved periodic HTTP reachability probes from the Swing Event Dispatch Thread (EDT) to a dedicated daemon worker.
- Added a 3-second connect timeout and 3-second read timeout and prevent overlapping requests.
- Deliver results back to the EDT and stop the worker on application exit.
- Added a headless test proving that the EDT stays responsive while a probe is stalled.

### 2026-11 — Roadbook Editor settings (PR #11)
- Fixed persistence of the five saved label-format presets; commits unsaved format text on row changes and window close.
- Persist Simple, Road/Track and Slope fill/border colors in `config.xml` as defaults; preserve per-project styling and formats in CGX.
- Save preference changes independently from the editable project; test XML and CGX round trips.

### 2026-11 — Garmin FIT course identity (PR #10)
- Export separate `FileId.serial_number` values for standard and Virtual Partner FIT courses so both appear on a Garmin watch, even with the same track.
- Added FIT identity regression tests.

### Initial 5.0.0 features
- Spatially debiased slope analysis, continuous speed/terrain interpolation and per-kilometer moving pace/ascent/descent statistics.
- Mini-roadbook fields for segment distance, ascent/descent, cutoff time, margin, planned departure and breaks; arrival/after-stop cutoff choice.
- More faithful GPX/FIT import/export, original waypoint positions, food/water markers and optional experimental barrier-driven Virtual Partner pacing.
- TrailRace+ PRO TR1 payload export and QR codes.

See the application's localized release notes for the user-facing overview. The Virtual Partner course-speed behavior still depends on the receiving Garmin firmware.
