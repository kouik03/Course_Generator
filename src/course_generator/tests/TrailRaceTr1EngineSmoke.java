package course_generator.tests;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;

/** End-to-end runtime smoke test of the bundled GraalJS TR1 implementation. */
public final class TrailRaceTr1EngineSmoke {
    public static void main(String[] args) throws Exception {
        String script;
        try (InputStream stream = TrailRaceTr1EngineSmoke.class.getResourceAsStream(
                "/course_generator/tr1/routecode-adaptive.js")) {
            if (stream == null) throw new IllegalStateException("RouteCode JS is absent from jar resources");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] data = new byte[4096];
            int len;
            while ((len = stream.read(data)) != -1) output.write(data, 0, len);
            script = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        try (Context ctx = Context.newBuilder("js")
                .allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(c -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .build()) {
            try (InputStream shim = TrailRaceTr1EngineSmoke.class.getResourceAsStream(
                    "/course_generator/tr1/base64-shim.js")) {
                if (shim == null) throw new AssertionError("Base64 shim missing");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                int n;
                while ((n = shim.read(tmp)) != -1) b.write(tmp, 0, n);
                ctx.eval("js", new String(b.toByteArray(), StandardCharsets.UTF_8));
            }
            ctx.eval("js", script);
            Value route = ctx.getBindings("js").getMember("RouteCode");
            if (route == null || route.isNull()) throw new AssertionError("RouteCode not global");
            Value track = ctx.eval("js", "[[43.1,4.1,20],[43.1002,4.1003,30],[43.1003,4.1004,25]]");
            Value plan = ctx.eval("js", "[{km:0.04,name:'Finish',cut:-1,rel:4,srv:'WF',dp:10}]");
            Value out = route.getMember("encodeAdaptive").execute(track, "Smoke route", plan);
            String code = out.getMember("code").asString();
            if (code.length() < 5 || code.length() > 6000 || !code.startsWith("TR1"))
                throw new AssertionError("invalid TR1 output length");
            Value decoded = route.getMember("decode").execute(code);
            if (decoded == null || decoded.isNull()
                    || decoded.getMember("plan").getArraySize() != 1)
                throw new AssertionError("TR1 decode/CRC failed");
            System.out.println("GraalJS end-to-end TR1 OK (" + code.length() + " characters)");
        }
    }
}
