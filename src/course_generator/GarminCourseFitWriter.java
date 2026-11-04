/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package course_generator;

import com.garmin.fit.CourseMesg;
import com.garmin.fit.CoursePlannerCoursePointMesg;
import com.garmin.fit.CoursePoint;
import com.garmin.fit.DateTime;
import com.garmin.fit.Event;
import com.garmin.fit.EventMesg;
import com.garmin.fit.EventType;
import com.garmin.fit.File;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.LapMesg;
import com.garmin.fit.Manufacturer;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.Sport;

import course_generator.utils.CgConst;
import course_generator.utils.CgLog;

/**
 * Writes a Garmin FIT Course directly from Course Generator data.
 *
 * <p>The current track elevation is written verbatim to each FIT record, so
 * exporting a course never routes the elevation profile through Garmin
 * Connect's Course Planner elevation model.</p>
 */
public final class GarminCourseFitWriter {

    private static final float FALLBACK_COURSE_SPEED_MPS = 3.3333333f;
    private static final int PRODUCT_ID = 0;

    private GarminCourseFitWriter() {
    }

    public static void write(TrackData track, String filename) {
        write(track, filename, GarminFitExportOptions.defaults());
    }

    /** Export an ordinary or barrier-paced Garmin FIT Course. */
    public static void write(TrackData track, String filename, GarminFitExportOptions options) {
        if (track == null || track.data == null || track.data.isEmpty())
            throw new IllegalArgumentException("Cannot export an empty track");
        if (options == null)
            throw new IllegalArgumentException("Missing FIT export options");

        // Compute/validate the optional timeline before opening the output,
        // so a malformed barrier schedule cannot delete an existing FIT.
        GarminBarrierVirtualPartner.Timeline virtual =
                options.virtualPartner ? GarminBarrierVirtualPartner.build(track) : null;

        java.io.File output = new java.io.File(filename);
        FileEncoder encoder = null;
        boolean success = false;

        try {
            encoder = new FileEncoder(output, Fit.ProtocolVersion.V2_0);

            CgData first = track.data.get(0);
            CgData last = track.data.get(track.data.size() - 1);
            DateTime startTime = new DateTime(track.StartTime.toDate());
            long startTimestamp = startTime.getTimestamp().longValue();

            boolean hasCalculatedTimeline = track.TotalTime > 0 || last.getTime() > 0;
            long lastElapsed = virtual == null
                    ? timelineEndSeconds(track, hasCalculatedTimeline)
                    : virtual.elapsedSeconds[track.data.size() - 1];
            DateTime endTime = new DateTime(startTimestamp + lastElapsed);

            FileIdMesg fileId = new FileIdMesg();
            fileId.setType(File.COURSE);
            fileId.setManufacturer(Manufacturer.DEVELOPMENT);
            fileId.setProduct(PRODUCT_ID);
            fileId.setTimeCreated(startTime);
            fileId.setSerialNumber(1L);
            encoder.write(fileId);

            CourseMesg course = new CourseMesg();
            // FIT has its own CourseMesg.name, distinct from the .fit filename.
            // Differentiate the VP version on the watch without renaming the CGX project.
            String fitCourseName = courseName(track);
            if (options.virtualPartner && !fitCourseName.endsWith(" VP"))
                fitCourseName += " VP";
            course.setName(fitCourseName);
            course.setSport(Sport.RUNNING);
            encoder.write(course);

            LapMesg lap = new LapMesg();
            lap.setStartTime(startTime);
            // Garmin Course Planner course files use the course start for lap.timestamp.
            lap.setTimestamp(startTime);
            lap.setTotalElapsedTime(Float.valueOf((float) lastElapsed));
            lap.setTotalTimerTime(Float.valueOf((float) lastElapsed));
            lap.setStartPositionLat(Integer.valueOf(degreesToSemicircles(first.getLatitude())));
            lap.setStartPositionLong(Integer.valueOf(degreesToSemicircles(first.getLongitude())));
            lap.setEndPositionLat(Integer.valueOf(degreesToSemicircles(last.getLatitude())));
            lap.setEndPositionLong(Integer.valueOf(degreesToSemicircles(last.getLongitude())));
            lap.setTotalDistance(Float.valueOf((float) last.getTotal(CgConst.UNIT_METER)));
            lap.setTotalAscent(Integer.valueOf(toFitUint16(track.getClimbP(CgConst.UNIT_METER))));
            lap.setTotalDescent(Integer.valueOf(toFitUint16(track.getClimbM(CgConst.UNIT_METER))));
            encoder.write(lap);

            EventMesg startEvent = new EventMesg();
            startEvent.setTimestamp(startTime);
            startEvent.setEvent(Event.TIMER);
            startEvent.setEventType(EventType.START);
            encoder.write(startEvent);

            long previousElapsed = 0L;
            int coursePointIndex = 0;
            int finishCoursePointIndex = findFinishCoursePointIndex(track);

            for (int i = 0; i < track.data.size(); i++) {
                CgData point = track.data.get(i);
                long elapsed = virtual == null
                        ? elapsedSeconds(track, point, i, hasCalculatedTimeline)
                        : virtual.elapsedSeconds[i];
                if (elapsed < previousElapsed)
                    elapsed = previousElapsed;
                previousElapsed = elapsed;

                DateTime timestamp = new DateTime(startTimestamp + elapsed);
                int latitude = degreesToSemicircles(point.getLatitude());
                int longitude = degreesToSemicircles(point.getLongitude());
                float distance = (float) point.getTotal(CgConst.UNIT_METER);

                RecordMesg record = new RecordMesg();
                record.setTimestamp(timestamp);
                record.setPositionLat(Integer.valueOf(latitude));
                record.setPositionLong(Integer.valueOf(longitude));
                record.setDistance(Float.valueOf(distance));
                record.setAltitude(Float.valueOf((float) point.getElevation(CgConst.UNIT_METER)));
                if (virtual != null)
                    record.setSpeed(Float.valueOf(virtual.targetSpeedMps[i]));
                encoder.write(record);

                // Export every point that is visible in either roadbook view.
                // The Resume/Roadbook view includes MARK points and points carrying
                // a cutoff or rest time; the Mini Roadbook uses TAG_ROADBOOK.
                //
                // If an explicit arrival point exists slightly before the final GPX
                // record, export that course point at the true route endpoint. Garmin
                // Course Planner derives its native finish/trophy from the route end,
                // not from CoursePoint.SEGMENT_END.
                boolean finish = i == finishCoursePointIndex;
                boolean roadbookPoint = ((point.getTag() & CgConst.TAG_ROADBOOK) != 0)
                        || ((point.getTag() & CgConst.TAG_MARK) != 0)
                        || point.getTimeLimit() > 0
                        || point.getStation() > 0
                        || finish;

                if (roadbookPoint) {
                    CgData coursePointLocation = finish ? last : point;
                    DateTime coursePointTimestamp = finish ? endTime : timestamp;
                    int coursePointLatitude = degreesToSemicircles(coursePointLocation.getLatitude());
                    int coursePointLongitude = degreesToSemicircles(coursePointLocation.getLongitude());
                    float coursePointDistance = (float) coursePointLocation.getTotal(CgConst.UNIT_METER);

                    CoursePlannerCoursePointMesg coursePoint = new CoursePlannerCoursePointMesg();
                    coursePoint.setMessageIndex(Integer.valueOf(coursePointIndex++));
                    coursePoint.setTimestamp(coursePointTimestamp);
                    coursePoint.setPositionLat(Integer.valueOf(coursePointLatitude));
                    coursePoint.setPositionLong(Integer.valueOf(coursePointLongitude));
                    coursePoint.setDistance(Float.valueOf(coursePointDistance));

                    // Preserve the exact roadbook name entered by the user.
                    String pointName = point.getName();
                    if ((pointName == null || pointName.isEmpty()) && finish)
                        pointName = "Finish";
                    coursePoint.setName(pointName == null ? "" : pointName);
                    coursePoint.setType(options.customIcons
                            ? coursePointType(point, finish) : CoursePoint.GENERIC);

                    if (options.includeCutoffs && point.getTimeLimit() > 0)
                        coursePoint.setCutoffMilliseconds(secondsToMilliseconds(point.getTimeLimit()));
                    if (options.includeRestTimes && point.getStation() > 0)
                        coursePoint.setRestMilliseconds(secondsToMilliseconds(point.getStation()));

                    encoder.write(coursePoint);
                    CgLog.info("FIT course point '" + coursePoint.getName() + "' type="
                            + coursePoint.getType() + ", distance=" + coursePointDistance
                            + "m, finish=" + finish);
                }
            }

            EventMesg stopEvent = new EventMesg();
            stopEvent.setTimestamp(endTime);
            stopEvent.setEvent(Event.TIMER);
            stopEvent.setEventType(EventType.STOP_ALL);
            encoder.write(stopEvent);

            encoder.close();
            encoder = null;
            success = true;

            CgLog.info("Garmin FIT course exported: '" + filename + "', "
                    + (virtual == null ? "standard" : "virtual partner cutoff") + ", "
                    + (virtual == null ? "" : virtual.anchors.size() + " cutoff anchors, ")
                    + track.data.size() + " route points, D+="
                    + Math.round(track.getClimbP(CgConst.UNIT_METER)) + "m, D-="
                    + Math.round(track.getClimbM(CgConst.UNIT_METER)) + "m");
        } finally {
            if (!success) {
                if (encoder != null) {
                    try {
                        encoder.close();
                    } catch (RuntimeException ignored) {
                        // The partial file is deleted below.
                    }
                }
                if (output.exists())
                    output.delete();
            }
        }
    }

    private static CoursePoint coursePointType(CgData point, boolean finish) {
        if (finish)
            return CoursePoint.GENERIC;

        // FOOD deliberately wins over WATER: a food station is expected to
        // provide drink as well, and the food icon carries more information.
        if ((point.getTag() & CgConst.TAG_EAT_PT) != 0)
            return CoursePoint.FOOD;
        if ((point.getTag() & CgConst.TAG_WATER_PT) != 0) {
            /*
             * Compatibility for CGX files saved before GPX waypoint symbols were
             * preserved: those imports marked every aid station as WATER. Recover
             * obvious full "Ravito" stations from their names while keeping
             * explicitly liquid/water-only points as WATER.
             */
            if (looksLikeLegacyFoodStation(point.getName()))
                return CoursePoint.FOOD;
            return CoursePoint.WATER;
        }
        return CoursePoint.GENERIC;
    }

    private static boolean looksLikeLegacyFoodStation(String name) {
        if (name == null)
            return false;

        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean aidStation = lower.contains("ravito")
                || lower.contains("aid station")
                || lower.contains("refreshment");
        if (!aidStation)
            return false;

        boolean waterOnly = lower.contains("liquide")
                || lower.contains("liquid")
                || lower.contains("water")
                || lower.contains("eau")
                || lower.contains("drink")
                || lower.contains("hydrat");
        return !waterOnly;
    }

    private static int findFinishCoursePointIndex(TrackData track) {
        int lastIndex = track.data.size() - 1;
        CgData last = track.data.get(lastIndex);

        // Prefer an explicit roadbook name such as "Ravito 7 (arrivée)".
        for (int i = lastIndex; i >= 0; i--) {
            String name = track.data.get(i).getName();
            if (isFinishPointName(name))
                return i;
        }

        // If the arrival was created manually without a name, accept the last
        // timed roadbook point only when it is very close to the true endpoint.
        double totalDistance = last.getTotal(CgConst.UNIT_METER);
        for (int i = lastIndex; i >= 0; i--) {
            CgData point = track.data.get(i);
            double remaining = totalDistance - point.getTotal(CgConst.UNIT_METER);
            if (remaining > 250.0)
                break;
            if (point.getTimeLimit() > 0
                    && (((point.getTag() & CgConst.TAG_ROADBOOK) != 0)
                            || ((point.getTag() & CgConst.TAG_MARK) != 0)))
                return i;
        }

        return lastIndex;
    }

    private static boolean isFinishPointName(String name) {
        if (name == null)
            return false;
        String lower = name.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.contains("arriv") || lower.contains("finish");
    }

    private static String courseName(TrackData track) {
        if (track.CourseName != null && !track.CourseName.isEmpty())
            return track.CourseName;

        // A blank Track Name is legal in Course Generator but a useful FIT
        // course should still have a display name.
        if (track.Name != null && !track.Name.isEmpty()) {
            int dot = track.Name.lastIndexOf('.');
            return dot > 0 ? track.Name.substring(0, dot) : track.Name;
        }
        return "Course Generator";
    }

    private static long timelineEndSeconds(TrackData track, boolean hasCalculatedTimeline) {
        long end = 0L;
        for (int i = 0; i < track.data.size(); i++)
            end = Math.max(end, elapsedSeconds(track, track.data.get(i), i, hasCalculatedTimeline));
        return end;
    }

    private static long elapsedSeconds(TrackData track, CgData point, int index, boolean hasCalculatedTimeline) {
        if (hasCalculatedTimeline)
            return Math.max(0L, (long) point.getTime());

        // FIT Course records require a usable relative timeline. If the user
        // has not run the time calculation yet, synthesize only the FIT record
        // timestamps from distance. Cutoff/rest fields remain untouched.
        double distance = point.getTotal(CgConst.UNIT_METER);
        if (distance > 0.0)
            return Math.max(0L, Math.round(distance / FALLBACK_COURSE_SPEED_MPS));
        return Math.max(0, index);
    }

    private static long secondsToMilliseconds(int seconds) {
        long milliseconds = ((long) seconds) * 1000L;
        if (milliseconds > 0xFFFFFFFFL)
            throw new IllegalArgumentException("Course Planner time exceeds FIT uint32 range: " + seconds + "s");
        return milliseconds;
    }

    private static int degreesToSemicircles(double degrees) {
        return (int) Math.round(degrees * (2147483648.0 / 180.0));
    }

    private static int toFitUint16(double value) {
        long rounded = Math.round(Math.max(0.0, value));
        return (int) Math.min(65534L, rounded);
    }
}
