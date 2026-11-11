# Course IQ — prototype (CGIQ v1)

This is an **offline-first Connect IQ data field prototype** co-located with
Course Generator until the application has its own repository.

## Export

Course Generator > Export > **Exporter Course IQ (3 blocs)...** produces three
Base64 strings (at most 2,000 characters each), displays independently
verified QR codes and can write the blocks to a UTF-8 text file.

The three strings must be concatenated **in order** to decode CGIQ.
The encoding is unrelated to TrailRace TR1.

## CGIQ v1 binary layout (big-endian)

- `CGIQ` ASCII magic (4 bytes), version (1), flags (1)
- UTF-8 name length (1), name (up to 80 bytes)
- vertex count (u16), checkpoint count (u8)
- original total ascent (u32), original total descent (u32), length in m (u32)
- each vertex: lat/lon signed degrees × 100,000 (i32 each), cumulative
  distance (u32), elevation (i16), cumulative climb (u32), cumulative descent
  (u32), cumulative planned time (u32)
- each checkpoint: cumulative distance (u32), cumulative planned time (u32),
  relative cutoff seconds (i32), pause seconds (u32), service bitmask (u8),
  cutoff kind (u8: 0 none, 1 elimination, 2 reroute), name length (u8),
  UTF-8 name (up to 48 bytes)
- CRC32 of all preceding bytes (u32)

Checkpoints and cumulative elevation counters survive simplification.
A reroute checkpoint does not contain an alternative route.

## Current watch prototype

`connectiq/course-iq` includes the first Monkey C decoder, CRC validation,
a basic data-field screen, settings for three blobs, manual next-checkpoint
selection, start delay and per-checkpoint cutoff corrections.

`cutoffAdjustments` is a comma-separated list of signed **minutes** indexed
by checkpoint (index 0 = start). For example `0,0,-15,0,20` advances
checkpoint 2 by 15 minutes and postpones checkpoint 4 by 20 minutes.
This is a provisional settings UI, **not** the final per-checkpoint editor.

**Not yet verified**: Maven/JUnit compilation, Connect IQ SDK compilation,
Fēnix device IDs, QR scanning on phone, exact settings text limits, real
device memory, or integration of the existing TR1-specific altitude
compression algorithm. The Connect IQ launcher bitmap is also not yet
provided. The Monkey C display does not yet GPS-match the route or implement
the full dynamic prediction engine, rest alerts or an activity-state machine.

Do not use this prototype to make race cutoff decisions.

## Validation plan

1. Run GitHub Actions Java workflow and resolve compiler/test failures.
2. Round-trip a CGX with water+food+reroute tags, reopen it, then export CGIQ.
3. Compare decoded per-checkpoint distances, climb/descent and predicted moving times with the original CGX.
4. Compile the Monkey C data field using the installed Connect IQ SDK, replacing provisional device IDs and supplying the launcher bitmap.
5. Scan all three QR codes with a phone and verify the exact Base64 text pasted into Connect IQ settings.
6. Run the data field in the simulator before any real-world cutoff use.

**Warning:** Current Monkey C source is a first draft and has not passed a Garmin SDK build. Current Java tests have not yet been executed in this session. Neither the dynamic pace predictor nor automatic GPS checkpoint detection is implemented.
