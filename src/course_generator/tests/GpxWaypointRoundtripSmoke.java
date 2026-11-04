package course_generator.tests;

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
        try {
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

            track.SaveCGX(cgx.toString(), 0, 1, false);
            // The CGX reader prompts for a missing, unrelated .par curve file.
            // Remove this optional setting so the metadata test is headless.
            String cgxXML = new String(Files.readAllBytes(cgx), StandardCharsets.UTF_8);
            cgxXML = cgxXML.replaceAll("(?s)<CURVE>.*?</CURVE>", "");
            Files.write(cgx, cgxXML.getBytes(StandardCharsets.UTF_8));
            TrackData reopened = new TrackData(new CgSettings());
            int error = new SaxCGXHandler().readDataFromCGX(null, cgx.toString(), reopened, 0);
            if (error != 0 || reopened.data.size() != 2)
                throw new AssertionError("CGX reimport error: " + error + " / " + reopened.data.size());
            CgData importedWaypoint = reopened.data.get(0);
            if (!importedWaypoint.hasOriginalWaypointPosition()
                    || Math.abs(importedWaypoint.getWaypointLatitude() - 43.843349) > 1e-7
                    || Math.abs(importedWaypoint.getWaypointLongitude() - 4.356912) > 1e-7)
                throw new AssertionError("CGX save/reload lost original waypoint location");
            System.out.println("GPX XML, raw waypoint, CGX round-trip, selection and state OK");
        } finally {
            Files.deleteIfExists(exported);
            Files.deleteIfExists(partial);
            Files.deleteIfExists(cgx);
        }
    }
}
