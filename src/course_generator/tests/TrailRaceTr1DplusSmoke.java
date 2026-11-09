package course_generator.tests;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

import course_generator.CgData;
import course_generator.TrackData;
import course_generator.settings.CgSettings;
import course_generator.utils.CgConst;

/**
 * End-to-end regression for TR1 checkpoint dp values. Each dp must describe
 * ascent on that leg, not cumulative ascent since the route start.
 */
public final class TrailRaceTr1DplusSmoke {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = TrailRaceTr1DplusSmoke.class.getResourceAsStream(name)) {
            if (in == null) throw new AssertionError("Missing JS: " + name);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] block = new byte[4096];
            int read;
            while ((read = in.read(block)) != -1) b.write(block, 0, read);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String inputProperty(Object input, String key) throws Exception {
        Field field = input.getClass().getDeclaredField(key);
        field.setAccessible(true);
        return (String) field.get(input);
    }

    public static void main(String[] args) throws Exception {
        TrackData track = new TrackData(new CgSettings());
        track.CourseName = "TR1 Dplus checkpoint test";
        // Raw climbing = 350 m: Start->R1 = 100, R1->R2 = 100,
        // R2->passage = 10, passage->Finish = 140.
        int[] elevation = {100, 200, 150, 250, 220, 230, 370};
        for (int i = 0; i < elevation.length; i++) {
            CgData point = new CgData();
            point.setLatitude(43.1 + i * 0.0002);
            point.setLongitude(4.1 + i * 0.0001);
            point.setElevation(elevation[i]);
            point.setTotal(i * 100.0);
            if (i == 0) {
                point.setTag(CgConst.TAG_ROADBOOK);
                point.setName("Start");
            } else if (i == 2) {
                point.setTag(CgConst.TAG_ROADBOOK | CgConst.TAG_EAT_PT);
                point.setName("R1");
            } else if (i == 4) {
                point.setStation(10); // Station-only checkpoint
                point.setName("R2");
            } else if (i == 5) {
                point.setTag(CgConst.TAG_MARK); // Pass-through checkpoint
                point.setName("Passage");
            }
            track.data.add(point);
        }
        // Exercise the same global smoothing/reconciliation factor as the
        // production exporter: the four expected legs are 0,200,200,20,280.
        track.setClimbP(700.0);

        // Exercise the real private input builder to catch regression in the
        // exporter itself, not just in the JS codec.
        Class<?> cls = Class.forName("course_generator.TrailRaceTr1Dialog$Input");
        Method from = cls.getDeclaredMethod("from", TrackData.class);
        from.setAccessible(true);
        Object input = from.invoke(null, track);
        String planJson = inputProperty(input, "planJson");
        String trackJson = inputProperty(input, "trackJson");

        try (Context context = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(s -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .build()) {
            context.eval("js", resource("/course_generator/tr1/base64-shim.js"));
            context.eval("js", resource("/course_generator/tr1/routecode-adaptive.js"));
            context.getBindings("js").putMember("__cg_plan_json", planJson);
            context.getBindings("js").putMember("__cg_track_json", trackJson);
            Value plan = context.eval("js", "JSON.parse(__cg_plan_json)");
            Value points = context.eval("js", "JSON.parse(__cg_track_json)");

            check(plan.getArraySize() == 5, "Expected start, two ravitos, passage and finish");
            int[] expected = {0, 200, 200, 20, 280};
            String[] names = {"Start", "R1", "R2", "Passage", "Finish"};
            long total = 0;
            for (int i = 0; i < expected.length; i++) {
                Value stop = plan.getArrayElement(i);
                check(names[i].equals(stop.getMember("name").asString()), "Checkpoint order changed");
                check(stop.getMember("dp").asInt() == expected[i],
                        "Incorrect segment D+ before encoding at " + names[i] + ": "
                                + stop.getMember("dp").asInt());
                total += stop.getMember("dp").asInt();
            }
            check(total == 700, "TR1 segment D+ sum does not match Course Generator D+");

            Value route = context.getBindings("js").getMember("RouteCode");
            Value result = route.getMember("encodeAdaptive").execute(points, "Dplus regression", plan);
            String code = result.getMember("code").asString();
            check(code.startsWith("TR1") && code.length() <= 6000, "Invalid TR1 payload");
            Value decoded = route.getMember("decode").execute(code);
            check(decoded != null && !decoded.isNull(), "TR1 decode or CRC failed");
            Value decodedPlan = decoded.getMember("plan");
            check(decodedPlan.getArraySize() == expected.length, "Decoded plan lost checkpoints");
            for (int i = 0; i < expected.length; i++) {
                Value stop = decodedPlan.getArrayElement(i);
                check(stop.getMember("dp").asInt() == expected[i],
                        "Segment D+ changed after TR1 encoding at " + names[i]);
            }
        }

        System.out.println("TR1 per-leg checkpoint ascent, cumulative scaling and encode/decode: OK");
    }
}
