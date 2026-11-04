/*
 * Course Generator - Release note dismissal preference.
 *
 * Keep persistence independent from the main window's SaveConfig/exit path:
 * users often stop a development run from Maven or the terminal.
 */
package course_generator.releaseNote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import course_generator.settings.CgSettings;

/** Simple version-specific acknowledgment, persisted immediately on dialog close. */
public final class ReleaseNotePreference {

    private ReleaseNotePreference() {
    }

    public static boolean shouldShowAtStartup(CgSettings settings, String currentVersion) {
        return !currentVersion.equalsIgnoreCase(settings.ReleaseVersion == null
                ? "" : settings.ReleaseVersion.trim());
    }

    /**
     * Persist the current checkbox when closing the notes, including ESC or
     * window decorations. Unchecking on a manual reopen re-enables the notes
     * at next launch for the same version.
     *
     * @return true if the stored preference changed
     */
    public static boolean saveOnClose(CgSettings settings, String version,
            boolean doNotShowAgain, String configDir) {
        String stored = settings.ReleaseVersion == null ? "" : settings.ReleaseVersion.trim();
        String target = stored;
        if (doNotShowAgain) {
            target = version;
        } else if (version.equalsIgnoreCase(stored)) {
            target = "";
        }
        if (target.equals(stored))
            return false;

        settings.ReleaseVersion = target;
        settings.Save(configDir);

        // CgSettings.Save logs an IOException but does not throw one.
        // Verify the persisted XML value so a read-only/unwritable config does
        // not silently pretend the preference has been saved.
        Path config = Paths.get(configDir, "config.xml");
        if (!Files.isRegularFile(config)) {
            settings.ReleaseVersion = stored;
            throw new IllegalStateException("Impossible d'enregistrer la préférence : " + config);
        }
        CgSettings check = new CgSettings();
        check.Load(configDir);
        if (!target.equals(check.ReleaseVersion)) {
            settings.ReleaseVersion = stored;
            throw new IllegalStateException("La préférence n'a pas été enregistrée dans " + config);
        }
        return true;
    }
}
