/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * Garmin Course Planner course-point extensions.
 */
package com.garmin.fit;

/**
 * Course point message with the two Garmin Course Planner native extensions
 * currently used for cutoff and planned rest times.
 *
 * <p>The public FIT profile does not expose these two fields yet, but Garmin
 * Course Planner writes them as native uint32 values in milliseconds:</p>
 * <ul>
 *   <li>field 13: cutoff elapsed time from course start</li>
 *   <li>field 15: planned rest duration</li>
 * </ul>
 */
public class CoursePlannerCoursePointMesg extends CoursePointMesg {

    public static final int CUTOFF_MS_FIELD_NUM = 13;
    public static final int REST_MS_FIELD_NUM = 15;
    private static final int UINT32_BASE_TYPE = 134;

    public CoursePlannerCoursePointMesg() {
        super();
    }

    public void setCutoffMilliseconds(long milliseconds) {
        setPlannerUint32(CUTOFF_MS_FIELD_NUM, "course_planner_cutoff_ms", milliseconds);
    }

    public void setRestMilliseconds(long milliseconds) {
        setPlannerUint32(REST_MS_FIELD_NUM, "course_planner_rest_ms", milliseconds);
    }

    private void setPlannerUint32(int fieldNum, String fieldName, long value) {
        if (value < 0L || value > 0xFFFFFFFFL)
            throw new IllegalArgumentException(fieldName + " is outside FIT uint32 range: " + value);

        Field field = getField(fieldNum);
        if (field == null) {
            field = new Field(fieldName, fieldNum, UINT32_BASE_TYPE, 1, 0, "ms", false, Profile.Type.UINT32);
            addField(field);
        }

        setFieldValue(fieldNum, 0, Long.valueOf(value), Fit.SUBFIELD_INDEX_MAIN_FIELD);
    }
}
