package course_generator.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import course_generator.CgData;
import course_generator.SaxCGXHandler;
import course_generator.TrackData;
import course_generator.settings.CgSettings;
import course_generator.utils.CgConst;

/**
 * Regression for new Roadbook Editor defaults. Old editable CGX projects must
 * retain their own format, font size and profile type.
 */
public final class RoadbookDefaultsSmoke {
    private static final String EXPECTED = "%N%L%Dd%LD+:%+d / D-:%-d%L%M / %b%L%P";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path configDir = Files.createTempDirectory("cg-roadbook-default-config-");
        Path gpx = Files.createTempFile("cg-roadbook-default-", ".gpx");
        Path cgx = Files.createTempFile("cg-roadbook-preserve-", ".cgx");
        try {
            CgSettings defaults = new CgSettings();
            check(EXPECTED.equals(CgConst.DEFAULTMRBFORMAT), "Built-in roadbook format changed");
            check(CgConst.DEFAULTMRBFONTSIZE == 16, "Built-in roadbook font is not 16");
            check(EXPECTED.equals(defaults.DefaultFormat), "Config dialog's default format differs");

            TrackData fresh = new TrackData(defaults);
            check(fresh.MRBType == 2, "New projects must default to With Slope profile");
            CgData newPoint = new CgData();
            check(EXPECTED.equals(newPoint.FmtLbMiniRoadbook), "New point format incorrect");
            check(newPoint.FontSizeMiniRoadbook == 16, "New point label font incorrect");

            // The GPX parser must assign the new defaults to imported points.
            String gpxXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<gpx version=\"1.1\" creator=\"CG smoke\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
                    + "<trk><name>Test</name><trkseg>"
                    + "<trkpt lat=\"43.8000\" lon=\"4.3500\"><ele>100</ele></trkpt>"
                    + "<trkpt lat=\"43.8010\" lon=\"4.3510\"><ele>105</ele></trkpt>"
                    + "</trkseg></trk></gpx>";
            Files.write(gpx, gpxXml.getBytes(StandardCharsets.UTF_8));
            // A full GPX import must reset the profile even if an earlier
            // project had selected another profile type.
            fresh.MRBType = 0;
            fresh.OpenGPX(gpx.toString(), 0, 5);
            check(fresh.data.size() == 2, "GPX smoke import failed");
            check(fresh.MRBType == 2, "New GPX kept a previous project's profile");
            for (CgData p : fresh.data) {
                check(EXPECTED.equals(p.FmtLbMiniRoadbook), "GPX imported old roadbook format");
                check(p.FontSizeMiniRoadbook == 16, "GPX imported old font size");
            }

            // Existing config.xml with the former built-in template is migrated
            // automatically, without replacing user-customized formats.
            defaults.DefaultFormat = "%N%L%A%L%D%L%H";
            defaults.Save(configDir.toString());
            CgSettings migrated = new CgSettings();
            migrated.Load(configDir.toString());
            check(EXPECTED.equals(migrated.DefaultFormat),
                    "Existing user config still exposes old built-in roadbook template");
            migrated.DefaultFormat = "%N%L%H";
            migrated.Save(configDir.toString());
            CgSettings customized = new CgSettings();
            customized.Load(configDir.toString());
            check("%N%L%H".equals(customized.DefaultFormat), "Custom format was overwritten");

            // CGX content must win over new defaults for an existing project.
            fresh.MRBType = 0;
            fresh.data.get(0).FmtLbMiniRoadbook = "%N%L%A";
            fresh.data.get(0).FontSizeMiniRoadbook = 11;
            fresh.SaveCGX(cgx.toString(), 0, fresh.data.size() - 1, false);
            // Avoid the unrelated .par missing-curve dialog in a headless test.
            String xml = new String(Files.readAllBytes(cgx), StandardCharsets.UTF_8);
            xml = xml.replaceAll("(?s)<CURVE>.*?</CURVE>", "");
            Files.write(cgx, xml.getBytes(StandardCharsets.UTF_8));

            TrackData reloaded = new TrackData(new CgSettings());
            int cgxError = new SaxCGXHandler().readDataFromCGX(null, cgx.toString(), reloaded, 0);
            check(cgxError == 0 && !reloaded.data.isEmpty(), "CGX smoke reload failed");
            check(reloaded.MRBType == 0, "Old CGX profile type overwritten");
            check("%N%L%A".equals(reloaded.data.get(0).FmtLbMiniRoadbook),
                    "Old CGX label format overwritten");
            check(reloaded.data.get(0).FontSizeMiniRoadbook == 11,
                    "Old CGX label font size overwritten");

            System.out.println("Roadbook defaults and CGX backward compatibility: OK");
        } finally {
            Files.deleteIfExists(cgx);
            Files.deleteIfExists(gpx);
            Files.deleteIfExists(configDir.resolve("config.xml"));
            Files.deleteIfExists(configDir);
        }
    }
}
