/*
 * Course Generator
 * Copyright (C) 2016 Pierre Delore
 *
 * Garmin FIT Course Planner metadata reader.
 */
package course_generator;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.garmin.fit.CourseMesg;
import com.garmin.fit.CoursePoint;
import com.garmin.fit.CoursePointMesg;
import com.garmin.fit.Decode;
import com.garmin.fit.Mesg;
import com.garmin.fit.MesgNum;
import com.garmin.fit.RecordMesg;

/**
 * Reads the Garmin Course Planner extensions currently written in FIT
 * course_point messages.
 *
 * FIT profile 21.219 stores:
 *   field 13: cutoff time, milliseconds from course start
 *   field 15: planned rest duration, milliseconds
 *
 * The public Java SDK 21.218 does not yet assign names to these fields, but
 * Decode preserves unknown native fields by field number.
 */
public final class GarminCoursePlannerFitReader {

	public static final int COURSE_POINT_CUTOFF_MS_FIELD = 13;
	public static final int COURSE_POINT_REST_MS_FIELD = 15;
	private static final long UINT32_INVALID = 0xFFFFFFFFL;

	public static class CoursePlannerPoint {
		public String name = "";
		public double latitude = Double.NaN;
		public double longitude = Double.NaN;
		public double distanceMeters = Double.NaN;
		public CoursePoint type = CoursePoint.GENERIC;
		public int cutoffSeconds = 0;
		public int restSeconds = 0;
	}

	public static class RoutePoint {
		public double latitude = Double.NaN;
		public double longitude = Double.NaN;
		public double elevationMeters = Double.NaN;
	}

	public static class FitCourse {
		public String name = "";
		public final List<RoutePoint> routePoints = new ArrayList<RoutePoint>();
		public final List<CoursePlannerPoint> coursePoints = new ArrayList<CoursePlannerPoint>();
	}

	private GarminCoursePlannerFitReader() {
	}

	public static List<CoursePlannerPoint> read(String filename) throws IOException {
		return readCourse(filename).coursePoints;
	}

	public static FitCourse readCourse(String filename) throws IOException {
		final FitCourse course = new FitCourse();
		Decode decode = new Decode();

		try (FileInputStream in = new FileInputStream(filename)) {
			decode.read(in, (Mesg mesg) -> {
				if (mesg.getNum() == MesgNum.COURSE) {
					CourseMesg courseMesg = new CourseMesg(mesg);
					String name = courseMesg.getName();
					if (name != null)
						course.name = name.trim();
					return;
				}

				if (mesg.getNum() == MesgNum.RECORD) {
					RecordMesg record = new RecordMesg(mesg);
					Integer lat = record.getPositionLat();
					Integer lon = record.getPositionLong();
					if (lat == null || lon == null)
						return;

					RoutePoint point = new RoutePoint();
					point.latitude = semicirclesToDegrees(lat.intValue());
					point.longitude = semicirclesToDegrees(lon.intValue());
					Float altitude = record.getAltitude();
					if (altitude != null)
						point.elevationMeters = altitude.doubleValue();
					course.routePoints.add(point);
					return;
				}

				if (mesg.getNum() != MesgNum.COURSE_POINT)
					return;

				CoursePointMesg cp = new CoursePointMesg(mesg);
				CoursePlannerPoint point = new CoursePlannerPoint();

				String name = cp.getName();
				if (name != null)
					point.name = name.trim();

				Integer lat = cp.getPositionLat();
				Integer lon = cp.getPositionLong();
				if (lat != null && lon != null) {
					point.latitude = semicirclesToDegrees(lat.intValue());
					point.longitude = semicirclesToDegrees(lon.intValue());
				}

				Float distance = cp.getDistance();
				if (distance != null)
					point.distanceMeters = distance.doubleValue();

				CoursePoint type = cp.getType();
				if (type != null)
					point.type = type;

				point.cutoffSeconds = millisecondsFieldToSeconds(mesg, COURSE_POINT_CUTOFF_MS_FIELD);
				point.restSeconds = millisecondsFieldToSeconds(mesg, COURSE_POINT_REST_MS_FIELD);

				course.coursePoints.add(point);
			});
		}

		return course;
	}

	private static int millisecondsFieldToSeconds(Mesg mesg, int fieldNum) {
		Long value = mesg.getFieldLongValue(fieldNum);
		if (value == null)
			return 0;

		long raw = value.longValue();
		if (raw < 0)
			raw &= UINT32_INVALID;
		if (raw == UINT32_INVALID)
			return 0;

		long seconds = (raw + 500L) / 1000L;
		if (seconds <= 0 || seconds > Integer.MAX_VALUE)
			return 0;
		return (int) seconds;
	}

	private static double semicirclesToDegrees(int semicircles) {
		return semicircles * (180.0 / 2147483648.0);
	}
}
