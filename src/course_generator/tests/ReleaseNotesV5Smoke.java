package course_generator.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import course_generator.releaseNote.ReleaseNotePreference;
import course_generator.settings.CgSettings;

/** Headless release-note preference regression using actual config.xml I/O. */
public final class ReleaseNotesV5Smoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String resource(String locale) throws Exception {
        try (InputStream in = ReleaseNotesV5Smoke.class.getResourceAsStream(
                "/course_generator/releaseNote/releaseNote_" + locale + ".html")) {
            check(in != null, "Missing release-note resource: " + locale);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] bytes = new byte[2048];
            int count;
            while ((count = in.read(bytes)) >= 0)
                output.write(bytes, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("cg-release-version-");
        try {
            CgSettings original = new CgSettings();
            original.ReleaseVersion = "4.6.0";
            original.Save(dir.toString());

            CgSettings state = new CgSettings();
            state.Load(dir.toString());
            check(ReleaseNotePreference.shouldShowAtStartup(state, "5.0.0"),
                    "Old 4.6.0 acknowledgment incorrectly suppresses 5.0.0");

            // Checking the box and closing writes immediately, with no app shutdown.
            check(ReleaseNotePreference.saveOnClose(state, "5.0.0", true, dir.toString()),
                    "Acknowledgment change not written");
            CgSettings restarted = new CgSettings();
            restarted.Load(dir.toString());
            check("5.0.0".equals(restarted.ReleaseVersion),
                    "Version acknowledgment not round-tripped to config.xml");
            check(!ReleaseNotePreference.shouldShowAtStartup(restarted, "5.0.0"),
                    "Acknowledged 5.0.0 notes show on next launch");
            check(ReleaseNotePreference.shouldShowAtStartup(restarted, "5.0.1"),
                    "An acknowledgment should not hide later versions");
            check(!ReleaseNotePreference.saveOnClose(restarted, "5.0.0", true, dir.toString()),
                    "Reclosing unchanged notes should not write the config unnecessarily");

            // If the user reopens Help > Release notes and unticks the box,
            // the next startup should show the notes again.
            check(ReleaseNotePreference.saveOnClose(restarted, "5.0.0", false, dir.toString()),
                    "Manual opt-in was not saved");
            CgSettings optedIn = new CgSettings();
            optedIn.Load(dir.toString());
            check(ReleaseNotePreference.shouldShowAtStartup(optedIn, "5.0.0"),
                    "Manual opt-in should re-enable the next startup dialog");
            check("".equals(optedIn.ReleaseVersion), "Opt-in did not clear stored version");

            String fr = resource("fr_FR");
            String en = resource("en_US");
            check(fr.contains("5.0.0") && fr.contains("Virtual Partner")
                    && fr.contains("TrailRace+ PRO") && fr.contains("Ne plus afficher"),
                    "French release notes are incomplete");
            check(en.contains("5.0.0") && en.contains("Virtual Partner")
                    && en.contains("TrailRace+ PRO") && en.contains("Stop displaying"),
                    "English release notes are incomplete");
            check(!fr.contains("version 4.6.0") && !en.contains("version 4.6.0"),
                    "Obsolete release-note copy remains");
            System.out.println("5.0.0 release notes, config.xml persistence and re-enable: OK");
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                });
            }
        }
    }
}
