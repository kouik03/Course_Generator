# FIT course export options and experimental Virtual Partner

Course Generator **File > Export > Export Garmin FIT course** now opens one configuration dialog before choosing the destination file.

| Option | Default | Effect |
|---|---|---|
| Official cutoffs | Enabled | Writes Garmin Course Planner field 13 (milliseconds from course start) to each course point that has a time limit |
| Station rest time | Enabled | Writes Garmin Course Planner field 15 (planned rest milliseconds) |
| Custom Garmin symbols | Enabled | Maps FOOD / WATER roadbook tags to FIT course point types; disabling uses GENERIC points, retaining names/locations |
| Barrier Virtual Partner | **Disabled** | Rewrites the FIT Course Record timestamps and adds a **variable** Record.speed matching the terrain-sensitive moving-time model, adjusted between consecutive cutoffs, with **no station stops** |

For Virtual Partner exports, the **FIT-internal `CourseMesg.name`** is suffixed with ` VP` (for example `Venvici 2026 VP`). Normal FIT exports retain the exact project course name. This allows the watch to distinguish the two routes even when the filenames differ. The CGX project name is never renamed.

The original `GarminCourseFitWriter.write(track, file)` overload still uses the historical defaults. The regular export has the same timeline and no speed fields as before.

## Virtual Partner timeline

- Uses the **current Course Generator plan** and distance for every track point. When planned times are available, the movement weighting for each segment subtracts the current point's station rest; missing/invalid movement timing falls back to distance/speed.
- Starting at elapsed time 0, each official cutoff at a track/roadbook point anchors the virtual runner to exactly `point.getTimeLimit()` seconds since the start. For each interval, elapsed **moving** durations are scaled by `(cutoffB - cutoffA) / baselineMovingTime`.
- Course `Record.timestamp` and `Record.speed` describe the resulting virtual runner; `Lap.total_elapsed_time` and `Lap.total_timer_time` equal the no-stop virtual elapsed duration.
- Between the **last** official cutoff and the end of the track, its most recent speed-scaling coefficient is continued. No artificial finish barrier is invented.
- Invalid cutoffs (none, non-increasing, same distance) are refused before choosing the output file.
- The cutoff checkbox **does not** control the constraint calculation: a user can hide cutoff metadata while retaining Virtual Partner's target speed. Rest metadata can likewise remain enabled even though the virtual opponent **does not rest**. These are separate things.
- This mode is **experimental**. Garmin's FIT Course profile defines target `Record.speed` as a Virtual Partner field, but it does *not* guarantee every Garmin watch uses downloaded Course files' per-record speed, or exposes a dynamic lead/lag. Garmin's support docs say downloaded Garmin Connect-created routes may use a single average speed.

## Validation

The CI job runs `GarminFitBarrierVirtualPartnerSmoke` in addition to existing Maven, GraalJS, GPX/CGX and QR tests. The test decodes real FIT files with Garmin's Java SDK and verifies four independent flag combinations, target speeds, cutoff anchors and a rejected invalid schedule.

For a device test, import the FIT Course directly onto a compatible Garmin watch, enable racing a course if the watch supports it, compare the actual Virtual Partner position against the expected cutoff times. Do not assume Garmin Connect preserves FIT Course pacing fields after reprocessing.

**Project CGX is not saved or marked saved by FIT export.**
