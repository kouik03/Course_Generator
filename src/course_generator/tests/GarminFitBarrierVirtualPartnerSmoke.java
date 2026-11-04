/*
 * Regression smoke tests for the optional barrier-based FIT Virtual Partner,
 * and independent FIT export metadata flags.
 */
package course_generator.tests;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.garmin.fit.Decode;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Mesg;
import com.garmin.fit.MesgNum;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.CoursePoint;
import course_generator.CgData;
import course_generator.GarminBarrierVirtualPartner;
import course_generator.GarminCourseFitWriter;
import course_generator.GarminCoursePlannerFitReader;
import course_generator.GarminFitExportOptions;
import course_generator.TrackData;
import course_generator.settings.CgSettings;
import course_generator.utils.CgConst;

public final class GarminFitBarrierVirtualPartnerSmoke {
    private GarminFitBarrierVirtualPartnerSmoke() { }

    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }

    private static TrackData fixture() {
        TrackData track = new TrackData(new CgSettings());
        track.CourseName = "FIT options / VP smoke";
        track.StartTime = new org.joda.time.DateTime(2026, 11, 7, 7, 30, 0);
        int[] times = {0, 500, 1300, 1750, 2250};
        for (int i = 0; i < 5; i++) {
            CgData p = new CgData();
            p.setLatitude(43.8 + i * 0.001);
            p.setLongitude(4.3 + i * 0.001);
            p.setElevation(50 + i * 10);
            p.setTotal(1000.0 * i);
            p.setTime(times[i]);
            p.setName(i == 2 ? "R1" : i == 4 ? "Finish" : "");
            if (i == 2) {
                p.setTag(CgConst.TAG_ROADBOOK | CgConst.TAG_EAT_PT | CgConst.TAG_WATER_PT);
                p.setStation(300); // The 5min station rest is INCLUDED in Time[2].
                p.setTimeLimit(1800); // virtual runner exactly reaches R1 at 30min.
            }
            if (i == 4) {
                p.setTag(CgConst.TAG_ROADBOOK);
                p.setTimeLimit(3000); // virtual runner exactly finishes at 50min.
            }
            track.data.add(p);
        }
        track.TotalTime = times[4];
        return track;
    }

    private static final class Capture {
        final List<RecordMesg> records = new ArrayList<RecordMesg>();
        final List<LapMesg> laps = new ArrayList<LapMesg>();
    }

    private static Capture readAll(Path file) throws Exception {
        Capture capture = new Capture();
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            new Decode().read(input, (Mesg message) -> {
                if (message.getNum() == MesgNum.RECORD)
                    capture.records.add(new RecordMesg(message));
                else if (message.getNum() == MesgNum.LAP)
                    capture.laps.add(new LapMesg(message));
            });
        }
        return capture;
    }

    private static GarminCoursePlannerFitReader.CoursePlannerPoint point(String file, String name)
            throws Exception {
        for (GarminCoursePlannerFitReader.CoursePlannerPoint cp : GarminCoursePlannerFitReader.read(file))
            if (name.equals(cp.name)) return cp;
        throw new AssertionError("Missing course point: " + name);
    }

    private static void runCase(TrackData track, GarminFitExportOptions options,
            boolean expectVirtual, boolean expectCutoffs, boolean expectRests, boolean expectCustomIcon)
            throws Exception {
        Path file = Files.createTempFile("fit-vp-barriers-", ".fit");
        try {
            GarminCourseFitWriter.write(track, file.toString(), options);
            Capture capture = readAll(file);
            check(capture.records.size() == 5, "Incorrect FIT Record count");
            check(capture.laps.size() == 1, "Incorrect FIT Lap count");
            long first = capture.records.get(0).getTimestamp().getTimestamp();
            long last = capture.records.get(4).getTimestamp().getTimestamp();
            long elapsed = last - first;
            check(elapsed == (expectVirtual ? 3000 : 2250),
                    "FIT finish time does not match target chronology: " + elapsed);
            check(Math.round(capture.laps.get(0).getTotalElapsedTime())
                    == (expectVirtual ? 3000 : 2250), "FIT Lap elapsed mismatch");
            for (int i = 0; i < capture.records.size(); i++)
                check(expectVirtual == (capture.records.get(i).getSpeed() != null),
                        "Target speed appears in wrong FIT mode at record " + i);
            if (expectVirtual) {
                check(capture.records.get(2).getTimestamp().getTimestamp() - first == 1800,
                        "Virtual Partner does not hit R1 at the cutoff");
                check(capture.records.get(3).getTimestamp().getTimestamp() > first + 1800,
                        "Virtual Partner paused or moved backward");
                check(capture.records.get(4).getTimestamp().getTimestamp() - first == 3000,
                        "Virtual Partner does not hit final cutoff");
            }

            GarminCoursePlannerFitReader.CoursePlannerPoint r1 = point(file.toString(), "R1");
            check((r1.cutoffSeconds == 1800) == expectCutoffs, "Cutoff option ignored");
            check((r1.restSeconds == 300) == expectRests, "Rest option ignored");
            check((r1.type == CoursePoint.FOOD) == expectCustomIcon, "Icon option ignored");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    public static void main(String[] args) throws Exception {
        TrackData track = fixture();
        GarminBarrierVirtualPartner.Timeline timeline = GarminBarrierVirtualPartner.build(track);
        check(timeline.anchors.size() == 2, "Missing virtual cutoffs");
        check(timeline.elapsedSeconds[2] == 1800, "Virtual R1 not at 30min");
        check(timeline.elapsedSeconds[4] == 3000, "Virtual finish not at 50min");
        check(timeline.elapsedSeconds[1] == 900,
                "Pause was not subtracted before scaling the first segment");
        for (int i = 1; i < timeline.elapsedSeconds.length; i++) {
            check(timeline.elapsedSeconds[i] > timeline.elapsedSeconds[i - 1],
                    "A moving Virtual Partner stopped at trackpoint " + i);
            check(timeline.targetSpeedMps[i] > 0, "Missing moving target speed");
        }
        runCase(track, GarminFitExportOptions.defaults(), false, true, true, true);
        runCase(track, new GarminFitExportOptions(false, false, false, false),
                false, false, false, false);
        runCase(track, new GarminFitExportOptions(true, true, true, true),
                true, true, true, true);
        runCase(track, new GarminFitExportOptions(false, false, false, true),
                true, false, false, false);

        track.data.get(4).setTimeLimit(1700); // Earlier than R1 => impossible.
        boolean refused = false;
        try {
            GarminBarrierVirtualPartner.build(track);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "Decreasing official deadlines were accepted");
        System.out.println("FIT Virtual Partner cutoff timeline and four export combinations: OK");
    }
}
