package course_generator.tests;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import course_generator.CgData;
import course_generator.SaxCGXHandler;
import course_generator.TrackData;
import course_generator.settings.CgSettings;
import course_generator.utils.CgConst;

/**
 * Verifies that GPX export remains XML-valid, keeps original independent
 * waypoint coordinates, writes no numeric names for ordinary trackpoints, and
 * never marks the editable CGX project saved.
 */
public final class GpxWaypointRoundtripSmoke {
    public static void main(String[] args) throws Exception {
        TrackData track = new TrackData(new CgSettings());
        track.CourseName = "Veni Vici smoke";
        track.isModified = true;

        CgData a = new CgData();
        a.setLatitude(43.842560);
        a.setLongitude(4.356940);
        a.setElevation(100);
        a.setTag(CgConst.TAG_EAT_PT | CgConst.TAG_WATER_PT | CgConst.TAG_ROADBOOK);
        a.setName("Ravito arrivée");
        a.setOriginalWaypointPosition(43.843349, 4.356912);
        a.setTotal(0);
        track.data.add(a);

        CgData b = new CgData();
        b.setLatitude(43.842800);
        b.setLongitude(4.356800);
        b.setElevation(120);
        b.setTotal(30);
        track.data.add(b);

        Path exported = Files.createTempFile("cg-gpx-waypoint-", ".gpx");
        Path partial = Files.createTempFile("cg-gpx-selected-", ".gpx");
        Path cgx = Files.createTempFile("cg-original-waypoint-", ".cgx");
        Path configDir = Files.createTempDirectory("cg-mrb-settings-");
        try {
            // Format presets and roadbook colors are persistent user preferences.
            CgSettings preferences = new CgSettings();
            preferences.MemoFormat[0] = "%N%L%A%L%D";
            preferences.DefaultFormat = "%N%L%H";
            preferences.MrbColorSimpleFill = new Color(11, 12, 13);
            preferences.MrbColorSimpleBorder = new Color(14, 15, 16);
            preferences.MrbColorRSPath = new Color(21, 22, 23);
            preferences.MrbColorRSRoad = new Color(24, 25, 26);
            preferences.MrbColorRSBorder = new Color(27, 28, 29);
            preferences.MrbColorSlopeInf5 = new Color(31, 32, 33);
            preferences.MrbColorSlopeInf10 = new Color(34, 35, 36);
            preferences.MrbColorSlopeInf15 = new Color(37, 38, 39);
            preferences.MrbColorSlopeSup15 = new Color(40, 41, 42);
            preferences.MrbColorSlopeBorder = new Color(43, 44, 45);
            preferences.Save(configDir.toString());

            CgSettings reloadedSettings = new CgSettings();
            reloadedSettings.Load(configDir.toString());
            if (!"%N%L%A%L%D".equals(reloadedSettings.MemoFormat[0])
                    || !"%N%L%H".equals(reloadedSettings.DefaultFormat)
                    || !preferences.MrbColorSimpleFill.equals(reloadedSettings.MrbColorSimpleFill)
                    || !preferences.MrbColorSimpleBorder.equals(reloadedSettings.MrbColorSimpleBorder)
                    || !preferences.MrbColorRSPath.equals(reloadedSettings.MrbColorRSPath)
                    || !preferences.MrbColorRSRoad.equals(reloadedSettings.MrbColorRSRoad)
                    || !preferences.MrbColorRSBorder.equals(reloadedSettings.MrbColorRSBorder)
                    || !preferences.MrbColorSlopeInf5.equals(reloadedSettings.MrbColorSlopeInf5)
                    || !preferences.MrbColorSlopeInf10.equals(reloadedSettings.MrbColorSlopeInf10)
                    || !preferences.MrbColorSlopeInf15.equals(reloadedSettings.MrbColorSlopeInf15)
                    || !preferences.MrbColorSlopeSup15.equals(reloadedSettings.MrbColorSlopeSup15)
                    || !preferences.MrbColorSlopeBorder.equals(reloadedSettings.MrbColorSlopeBorder))
                throw new AssertionError("Roadbook user palette or preset lost in config.xml");

            TrackData newTrack = new TrackData(reloadedSettings);
            if (!newTrack.clProfil_SlopeInf5.equals(preferences.MrbColorSlopeInf5)
                    || !newTrack.clProfil_RS_Path.equals(preferences.MrbColorRSPath))
                throw new AssertionError("New GPX project did not use saved palette defaults");
            track.ExportGPX(exported.toString(), 0, 1);
            if (!track.isModified) throw new AssertionError("GPX export cleared CGX modified flag");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(exported.toFile());
            String ns = "http://www.topografix.com/GPX/1/1";
            NodeList waypoints = document.getElementsByTagNameNS(ns, "wpt");
            NodeList points = document.getElementsByTagNameNS(ns, "trkpt");
            if (waypoints.getLength() != 1 || points.getLength() != 2)
                throw new AssertionError("Incorrect number of trackpoints or waypoints");
            Element waypoint = (Element) waypoints.item(0);
            if (Math.abs(Double.parseDouble(waypoint.getAttribute("lat")) - 43.843349) > 1e-7
                    || Math.abs(Double.parseDouble(waypoint.getAttribute("lon")) - 4.356912) > 1e-7)
                throw new AssertionError("Raw GPX waypoint location was lost");

            String xml = new String(Files.readAllBytes(exported), StandardCharsets.UTF_8);
            if (xml.contains("<name>0</name>") || xml.contains("<name>1</name>"))
                throw new AssertionError("Spurious sequential trackpoint labels not removed");

            track.ExportGPX(partial.toString(), 1, 1);
            Document selected = factory.newDocumentBuilder().parse(partial.toFile());
            if (selected.getElementsByTagNameNS(ns, "wpt").getLength() != 0)
                throw new AssertionError("Partial export included a waypoint outside the selection");
            if (!track.isModified)
                throw new AssertionError("Partial GPX export cleared CGX modified flag");

            // A CGX stores its own colors and per-point format, independently
            // from defaults in config.xml.
            track.clProfil_SlopeInf5 = new Color(101, 102, 103);
            track.clProfil_SlopeInf10 = new Color(104, 105, 106);
            track.clProfil_SlopeInf15 = new Color(107, 108, 109);
            track.clProfil_SlopeSup15 = new Color(110, 111, 112);
            track.clProfil_SlopeBorder = new Color(113, 114, 115);
            track.data.get(0).FmtLbMiniRoadbook = "%N%L%H%L%D";
            track.SaveCGX(cgx.toString(), 0, 1, false);
            // The CGX reader prompts for a missing, unrelated .par curve file.
            // Remove this optional setting so the metadata test is headless.
            String cgxXML = new String(Files.readAllBytes(cgx), StandardCharsets.UTF_8);
            cgxXML = cgxXML.replaceAll("(?s)<CURVE>.*?</CURVE>", "");
            Files.write(cgx, cgxXML.getBytes(StandardCharsets.UTF_8));
            TrackData reopened = new TrackData(reloadedSettings);
            int error = new SaxCGXHandler().readDataFromCGX(null, cgx.toString(), reopened, 0);
            if (error != 0 || reopened.data.size() != 2)
                throw new AssertionError("CGX reimport error: " + error + " / " + reopened.data.size());
            CgData importedWaypoint = reopened.data.get(0);
            if (!new Color(101, 102, 103).equals(reopened.clProfil_SlopeInf5)
                    || !new Color(104, 105, 106).equals(reopened.clProfil_SlopeInf10)
                    || !new Color(107, 108, 109).equals(reopened.clProfil_SlopeInf15)
                    || !new Color(110, 111, 112).equals(reopened.clProfil_SlopeSup15)
                    || !new Color(113, 114, 115).equals(reopened.clProfil_SlopeBorder)
                    || !"%N%L%H%L%D".equals(importedWaypoint.FmtLbMiniRoadbook))
                throw new AssertionError("CGX project lost its roadbook colors or label format");
            if (!importedWaypoint.hasOriginalWaypointPosition()
                    || Math.abs(importedWaypoint.getWaypointLatitude() - 43.843349) > 1e-7
                    || Math.abs(importedWaypoint.getWaypointLongitude() - 4.356912) > 1e-7)
                throw new AssertionError("CGX save/reload lost original waypoint location");
            System.out.println("GPX, CGX, roadbook palette and format preset persistence OK");
        } finally {
            Files.deleteIfExists(exported);
            Files.deleteIfExists(partial);
            Files.deleteIfExists(cgx);
            Files.deleteIfExists(configDir.resolve("config.xml"));
            Files.deleteIfExists(configDir);
        }
    }
}
