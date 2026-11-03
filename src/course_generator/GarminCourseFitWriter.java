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
        if (track == null || track.data == null || track.data.isEmpty())
            throw new IllegalArgumentException("Cannot export an empty track");

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
            long lastElapsed = timelineEndSeconds(track, hasCalculatedTimeline);
            DateTime endTime = new DateTime(startTimestamp + lastElapsed);

            FileIdMesg fileId = new FileIdMesg();
            fileId.setType(File.COURSE);
            fileId.setManufacturer(Manufacturer.DEVELOPMENT);
            fileId.setProduct(PRODUCT_ID);
            fileId.setTimeCreated(startTime);
            fileId.setSerialNumber(1L);
            encoder.write(fileId);

            CourseMesg course = new CourseMesg();
            course.setName(courseName(track));
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

            for (int i = 0; i < track.data.size(); i++) {
                CgData point = track.data.get(i);
                long elapsed = elapsedSeconds(track, point, i, hasCalculatedTimeline);
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
                encoder.write(record);

                // The roadbook itself is defined by TAG_ROADBOOK. MRBOPT_SEL only
                // controls the optional mini-roadbook label and must not hide an
                // aid station from the FIT export.
                if ((point.getTag() & CgConst.TAG_ROADBOOK) != 0) {
                    CoursePlannerCoursePointMesg coursePoint = new CoursePlannerCoursePointMesg();
                    coursePoint.setMessageIndex(Integer.valueOf(coursePointIndex++));
                    coursePoint.setTimestamp(timestamp);
                    coursePoint.setPositionLat(Integer.valueOf(latitude));
                    coursePoint.setPositionLong(Integer.valueOf(longitude));
                    coursePoint.setDistance(Float.valueOf(distance));

                    // Preserve the exact roadbook name entered by the user.
                    String pointName = point.getName();
                    coursePoint.setName(pointName == null ? "" : pointName);
                    coursePoint.setType(coursePointType(point, i == track.data.size() - 1));

                    if (point.getTimeLimit() > 0)
                        coursePoint.setCutoffMilliseconds(secondsToMilliseconds(point.getTimeLimit()));
                    if (point.getStation() > 0)
                        coursePoint.setRestMilliseconds(secondsToMilliseconds(point.getStation()));

                    encoder.write(coursePoint);
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
            return CoursePoint.SEGMENT_END;

        // FOOD deliberately wins over WATER: a food station is expected to
        // provide drink as well, and the food icon carries more information.
        if ((point.getTag() & CgConst.TAG_EAT_PT) != 0)
            return CoursePoint.FOOD;
        if ((point.getTag() & CgConst.TAG_WATER_PT) != 0)
            return CoursePoint.WATER;
        return CoursePoint.GENERIC;
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
