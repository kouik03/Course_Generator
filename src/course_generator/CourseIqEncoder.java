package course_generator;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;
import course_generator.utils.CgConst;

/**
 * CGIQ version 1: self-contained offline roadbook with adaptive, altitude-aware
 * route simplification. The output is NOT TrailRace TR1.
 *
 * Each of the three Base64 strings has a maximum of 2000 characters.
 * All distances are metres, times are seconds, elevations are metres.
 * Cutoff kind: 0 none, 1 elimination, 2 reroute (not elimination).
 * Relative cutoff seconds are measured from the OFFICIAL start.
 */
public final class CourseIqEncoder {
    public static final int BLOCK_CHARS = 2000;
    public static final int BLOCK_COUNT = 3;
    private CourseIqEncoder() {}

    public static final class Result {
        public final String[] blocks;
        public final int routePoints;
        public final int checkpoints;
        public final double ascentError;
        public final double descentError;
        Result(String[] blocks, int routePoints, int checkpoints, double ascentError, double descentError) {
            this.blocks = blocks; this.routePoints = routePoints; this.checkpoints = checkpoints;
            this.ascentError = ascentError; this.descentError = descentError;
        }
    }

    private static final class Point {
        int lat, lon, elevation, distance, movingSeconds, climb, descent;
        Point(CgData p, int climb, int descent) {
            lat = (int)Math.round(p.getLatitude() * 100000.0);
            lon = (int)Math.round(p.getLongitude() * 100000.0);
            elevation = (int)Math.round(p.getElevation(CgConst.UNIT_METER));
            distance = (int)Math.round(p.getTotal(CgConst.UNIT_METER));
            movingSeconds = Math.max(0, p.getTime());
            this.climb = climb; this.descent = descent;
        }
    }

    public static Result encode(TrackData track) {
        if (track == null || track.data == null || track.data.size() < 2)
            throw new IllegalArgumentException("Parcours vide.");
        List<Point> all = new ArrayList<>();
        List<Integer> stops = new ArrayList<>();
        int climb = 0, descent = 0;
        CgData previous = null;
        for (int i = 0; i < track.data.size(); i++) {
            CgData p = track.data.get(i);
            if (previous != null) {
                double delta = p.getElevation(CgConst.UNIT_METER) - previous.getElevation(CgConst.UNIT_METER);
                if (delta > 0) climb += Math.round((float)delta);
                else descent += Math.round((float)-delta);
            }
            all.add(new Point(p, climb, descent));
            int tag = p.getTag();
            if (i == 0 || i == track.data.size()-1 ||
                (tag & (CgConst.TAG_ROADBOOK | CgConst.TAG_MARK)) != 0 ||
                p.getStation() > 0 || p.getTimeLimit() > 0 ||
                (tag & CgConst.TAG_REROUTE) != 0) stops.add(i);
            previous = p;
        }
        if (stops.size() > 255) throw new IllegalArgumentException("Plus de 255 points de passage.");
        String[] best = null;
        int bestCount = 0;
        // Increasing sampling precision until the three-block budget is filled.
        // Mandatory checkpoints are never removed. Cumulative D+/D- and predicted
        // moving time are stored at every retained vertex (not recomputed from
        // simplified elevations), preventing loss of small climbs and descents.
        for (int tolerance = 1000; tolerance >= 1; tolerance = tolerance > 20 ? tolerance / 2 : tolerance - 1) {
            List<Integer> selected = simplify(all, stops, tolerance);
            byte[] binary = serialize(track, all, selected, stops);
            String b64 = Base64.getEncoder().encodeToString(binary);
            if (b64.length() <= BLOCK_CHARS * BLOCK_COUNT) {
                best = split(b64);
                bestCount = selected.size();
            } else if (best != null) break;
        }
        if (best == null) throw new IllegalArgumentException(
            "Le roadbook dépasse 3 x 2000 caractères, même avec une géométrie minimale.");
        return new Result(best, bestCount, stops.size(), 0, 0);
    }

    private static List<Integer> simplify(List<Point> points, List<Integer> stops, int tolerance) {
        boolean[] keep = new boolean[points.size()];
        for (int i : stops) keep[i] = true;
        keep[0] = true; keep[points.size()-1] = true;
        for (int j=0; j<stops.size()-1; j++)
            refine(points, keep, stops.get(j), stops.get(j+1), tolerance);
        List<Integer> out = new ArrayList<>();
        for (int i=0; i<keep.length; i++) if (keep[i]) out.add(i);
        return out;
    }

    private static void refine(List<Point> p, boolean[] keep, int a, int b, double tolerance) {
        if (b <= a+1) return;
        Point start=p.get(a), end=p.get(b);
        double best=0; int index=-1;
        for (int i=a+1; i<b; i++) {
            Point x=p.get(i);
            double t = end.distance == start.distance ? (i-a)/(double)(b-a)
                : (x.distance-start.distance)/(double)(end.distance-start.distance);
            double lat0 = start.lat + t*(end.lat-start.lat);
            double lon0 = start.lon + t*(end.lon-start.lon);
            double latitudeScale = Math.cos(Math.toRadians(x.lat/100000.0));
            double geo = Math.hypot((x.lat-lat0)*1.11, (x.lon-lon0)*1.11*latitudeScale);
            double alt = Math.abs(x.elevation-(start.elevation+t*(end.elevation-start.elevation)));
            double climb = Math.abs(x.climb-(start.climb+t*(end.climb-start.climb)));
            double descent = Math.abs(x.descent-(start.descent+t*(end.descent-start.descent)));
            double time = Math.abs(x.movingSeconds-(start.movingSeconds+t*(end.movingSeconds-start.movingSeconds)));
            double score = Math.max(Math.max(geo,alt),Math.max(Math.max(climb,descent),time/6.0));
            if (score > best) {best=score; index=i;}
        }
        if (best > tolerance && index >= 0) {
            keep[index]=true;
            refine(p,keep,a,index,tolerance);
            refine(p,keep,index,b,tolerance);
        }
    }

    private static byte[] serialize(TrackData track, List<Point> points,
                                     List<Integer> selected, List<Integer> stops) {
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            DataOutputStream d=new DataOutputStream(out);
            d.write(new byte[]{'C','G','I','Q'});
            d.writeByte(1); d.writeByte(0);
            String name=track.CourseName != null && !track.CourseName.trim().isEmpty()
                ? track.CourseName : track.Name;
            byte[] title=(name == null ? "Course Generator" : name).getBytes(StandardCharsets.UTF_8);
            d.writeByte(Math.min(title.length, 80)); d.write(title,0,Math.min(title.length,80));
            d.writeShort(selected.size()); d.writeByte(stops.size());
            // Reference totals, in metres, independent of simplification.
            d.writeInt((int)Math.round(track.getClimbP(CgConst.UNIT_METER)));
            d.writeInt((int)Math.round(track.getClimbM(CgConst.UNIT_METER)));
            Point last=points.get(points.size()-1);
            d.writeInt(last.distance);
            for (int index : selected) {
                Point p=points.get(index);
                d.writeInt(p.lat); d.writeInt(p.lon); d.writeInt(p.distance);
                d.writeShort(Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,p.elevation)));
                d.writeInt(p.climb); d.writeInt(p.descent); d.writeInt(p.movingSeconds);
            }
            for (int index : stops) {
                CgData cp=track.data.get(index);
                Point p=points.get(index);
                d.writeInt(p.distance);
                d.writeInt(p.movingSeconds);
                d.writeInt(cp.getTimeLimit()); // relative seconds, -1/0 means unset
                d.writeInt(Math.max(0,cp.getStation())); // planned pause seconds
                int tag=cp.getTag();
                int services=0;
                if ((tag & CgConst.TAG_WATER_PT)!=0) services|=1;
                if ((tag & CgConst.TAG_EAT_PT)!=0) services|=3;
                if ((tag & CgConst.TAG_CREW)!=0) services|=4;
                if ((tag & CgConst.TAG_DROPBAG)!=0) services|=8;
                d.writeByte(services);
                // Conservative default: every positive cutoff is elimination.
                // A reroute must be explicitly configured later in the editor.
                d.writeByte(cp.getTimeLimit()>0 ?
                    ((tag & CgConst.TAG_REROUTE)!=0 ? 2 : 1) : 0);
                String label=cp.getName();
                byte[] bytes=(label==null?"":label).getBytes(StandardCharsets.UTF_8);
                d.writeByte(Math.min(bytes.length,48)); d.write(bytes,0,Math.min(bytes.length,48));
            }
            d.flush();
            byte[] payload=out.toByteArray();
            CRC32 crc=new CRC32(); crc.update(payload);
            d.writeInt((int)crc.getValue()); d.flush();
            return out.toByteArray();
        } catch(IOException e) { throw new IllegalStateException(e); }
    }

    private static String[] split(String b64) {
        String[] out=new String[BLOCK_COUNT];
        for(int i=0;i<BLOCK_COUNT;i++) {
            int a=i*BLOCK_CHARS;
            out[i]=a>=b64.length()?"":b64.substring(a,Math.min(a+BLOCK_CHARS,b64.length()));
        }
        return out;
    }
}
