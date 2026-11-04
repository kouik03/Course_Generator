# Course Generator 5.0.0 — technical change inventory

**Scope:** changes delivered on `experiment/terrain-debiased-curve-v1` compared with legacy 4.6.0 on `master`, plus the 5.0.0 release-note/persistence maintenance. This document is for maintainers; the startup notes `src/course_generator/releaseNote/releaseNote_{fr_FR,en_US}.html` are **user-facing**.

## 1. Versioning and release note dialog

- Runtime version: `frmMain.Version = "5.0.0"`, used by application title, splash/about window, startup release-note selection, and version checks.
- Maven artifact version: `pom.xml` `5.0.0`. This affects the resulting Maven jar filename but not `mvn exec:java` or the recommended classpath launch.
- Startup check: `ReleaseNotePreference.shouldShowAtStartup(Settings, Version)` compares `5.0.0` to `CgSettings.ReleaseVersion` loaded from `~/.course_generator/config.xml`. A 4.6 acknowledgment must not suppress the first 5.0 appearance.
- Historically the checkbox only set `Settings.ReleaseVersion` in RAM when the **Close** button was pressed; ESC and window X bypassed that code, and `Settings.Save` ran only when the main window exited normally. Terminating a Maven development run from the terminal therefore lost the preference.
- `frmReleaseNote.closeDialog()` is now the **single close path** for button, Escape and window decoration. The preference is saved **immediately** to the usual config directory, independently of the main application's shutdown sequence. The method verifies the saved version by reopening `config.xml`, and warns if the settings cannot be persisted.
- The checkbox reflects the current saved state when reopening notes manually via Help. Unchecking it manually clears the acknowledgment, causing the notes to appear on next startup; checking it suppresses the **same** version only. A future version is still presented.
- `ReleaseNotesV5Smoke` tests persistence and the FR/EN HTML resources in headless CI. No Swing window is created by this test.

## 2. Core terrain, route and speed modeling

Related code: `TrackData.java`, `SaxGPXHandler.java`, `analysis/SpeedSlopeCurveEstimator.java`, `analysis/JPanelAnalysisSpeedSlope.java`, `dialogs/frmCoeffCurve.java`, `param/frmEditCurve.java`.

- Speed/slope analysis uses a spatial slope window (40 m global baseline) and continuous interpolation of target speeds across curve points.
- Weighted percentiles and local Gaussian-weighted slope neighborhoods support terrain debiasing. Bins with insufficient samples/distance support return no local estimate rather than forcing a dubious value; see `SpeedSlopeCurveEstimator.localWeightedPercentile`.
- Elevation/route calculation was revised to be more robust against uneven point spacing.
- Stats in `CgResume.java`, `ResumeData.java`, `statistics/JPanelStatistics.java` include moving pace and ascent/descent by kilometer. Validate manually with varied GPS point spacings, noisy altitude and sparse steep-gradient intervals.

## 3. Roadbook, cutoff semantics and formatting

Related code: `CgData.java`, `TrackData.java`, `resume/JPanelResume.java`, `mrb/FrmEditMrbFormat.java`, `mrb/FrmMiniroadbook.java`, `utils/Utils.java`.

- `CgData.Station`: planned stop duration in **seconds**.
- `CgData.Time`: cumulative planned elapsed seconds, **including** stops in the current time model.
- `CgData.TimeLimit`: official cutoff, elapsed seconds since course start.
- `CgData.TimeLimitAfterStation`: determines whether the official cutoff is checked against the planned time **after** the station or on arrival (before its stop). The read/write CGX schema stores this choice.
- The mini-roadbook gained cutoff margin, moving pace, delta distance from the previous roadbook position, additional planned-hour/departure fields, stop time and segment height differences. Consult `Utils.GenLabel` and its format editor for the current exact token spellings.

## 4. GPX import/export and CGX preservation

Related code: `TrackData.java`, `CgData.java`, `SaxCGXHandler.java`, `SaxGPXHandler.java`.

- Aid station GPX waypoints (e.g. OpenRunner symbols) are imported as food/water tags; named FIT Course Planner points are also mapped to track anchors. The points remain available in roadbooks.
- Original GPX waypoint coordinates are stored separately in `CgData.waypointLatitude` / `waypointLongitude` because the nearest route anchor can be tens of meters away (the Veni Vici finish was about 88 m from the route endpoint).
- Optional CGX tags `WAYPOINTLATITUDEDEGREES` and `WAYPOINTLONGITUDEDEGREES` persist that independent original location, with backward-compatible fallback to route coordinates for older CGX files. Already lost positions cannot be recovered from an old CGX without the original GPX.
- Compact GPX export avoids sequential names for ordinary `trkpt` elements, unused namespace declarations and pretty-print whitespace. Timestamps retain their instant to second precision; named route/aid points and elevation remain. Aid points are emitted as `wpt` for complete or selected exports, within the selected range.
- GPX export **does not** mark the editable CGX project as saved.
- `GpxWaypointRoundtripSmoke` covers export XML, selected waypoints, preservation of the independent location, and save/reload CGX.

## 5. Garmin FIT Course and experimental Virtual Partner

Related code: `GarminCourseFitWriter.java`, `GarminCoursePlannerFitReader.java`, `GarminFitExportOptions.java`, `GarminBarrierVirtualPartner.java`, `src/com/garmin/fit/CoursePlannerCoursePointMesg.java`.

- Import FIT Course Planner timing: native field **13** = cutoff milliseconds and field **15** = planned station rest milliseconds. The small local message subclass retains compatibility with the Garmin Java SDK.
- FIT Course export includes route positions/elevation and roadbook CoursePoints with names, station icons, optional planned cutoff and rest metadata. Export options are shown before the destination picker.
- Defaults: cutoff metadata **on**, rest metadata **on**, custom food/water icons **on**, Virtual Partner **off**. Checkbox values are independent. `write(track,path)` calls the default-option overload.
- Experimental barrier Virtual Partner: derive per-record moving time from the calculated plan, subtract station rest, adjust each contiguous interval between official cutoff anchors so the competitor hits each deadline at exactly the official time **without stopping**. Exports updated Record timestamps and per-record `speed` and continues the final segment's scale beyond the last known cutoff.
- Cutoff times are used by the virtual pacing algorithm even if exporting cutoff metadata is disabled. Validation rejects absent, non-increasing or non-traversable deadlines *before* opening a FIT output.
- The FIT-internal `CourseMesg.name` receives ` VP` only in VP mode. The standard internal name and the project Track Name remain unchanged.
- **Compatibility caveat:** the FIT fields are written and round-trip tests pass, but actual watch behavior for dynamic Virtual Partner on imported FIT Courses is not established. Garmin Connect may not preserve all pacing metadata.
- `GarminFitBarrierVirtualPartnerSmoke` writes and decodes real FITs to validate options, pace and barrier anchors.

## 6. TrailRace+ PRO TR1, embedded JavaScript and QR transfer

Related code: `TrailRaceTr1Dialog.java`, `tr1/routecode-adaptive.js`, `tr1/routecode-site-reference.js`, `tr1/base64-shim.js`, `TrailRaceTr1Qr.java`, `TrailRaceTr1QrViewer.java`.

- Export generates TR1 from current route and roadbook in at most three 2,000-character fields.
- The adaptive JS strategy searches alternatives for compact geometry and elevation, favoring distance and climbing fidelity; the user-supplied original reference JS is also bundled.
- GraalJS runs JS locally. Providers: bundled adaptive, editable user-local adaptive, or a local copy of the author's script. Optional HTTPS download/import from petrrezek.net is explicit, creates a backup, and does **not** auto-execute fetched code.
- Browser `atob/btoa` compatibility is provided by a local JS shim for GraalJS.
- Offline ZXing QR generation: exactly one code per field, raw field text as payload, no URL/identifier prefix, error correction M, 1,800-pixel bitmap, exact scan-back check and PNG export.
- Tests: `tools/test_trailrace_tr1.js`, `TrailRaceTr1EngineSmoke`, `TrailRaceQrSmoke`; docs: `doc/TR1_JS_EXPORT.md`.
- **Compatibility caveat:** TR1 decoder/CRC and QR content are validated; device-side interpretation of its D+ is not guaranteed to match Course Generator's own methods.

## 7. Recommended development/build/release checks

```bash
git pull --ff-only
mvn clean package
java -cp "target/classes:target/lib/*" course_generator.frmMain
```

The GitHub Actions workflow `.github/workflows/tr1-compile.yml` runs:
- Maven package under Java 21
- JS encode/decode smoke
- embedded GraalJS runtime
- GPX/CGX round-trip and waypoint tests
- dense QR decode and PNG write/read
- FIT VP pacing + option combinations
- **V5 release notes and immediate on-disk preference persistence**

Manual checks on macOS: run 5.0 from a saved 4.6 setting, check **Don't show again**, close notes with the button/ESC/window X, terminate the app externally, relaunch, confirm no modal; reopen via Help, uncheck, relaunch, confirm dialog returns. Also ensure the French and English HTML resources render and scroll correctly.

**Explicit non-goals:** this release-note update does not fix the longstanding overnight macOS sleep/EDT freeze, confirm watch-native Virtual Partner features, or redesign the application. These need separate investigations.
