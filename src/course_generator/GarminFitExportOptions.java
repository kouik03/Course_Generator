/*
 * Course Generator - selected FIT export features.
 * Default values preserve the historic FIT Course export behavior.
 */
package course_generator;

public final class GarminFitExportOptions {
    public final boolean includeCutoffs;
    public final boolean includeRestTimes;
    public final boolean customIcons;
    public final boolean virtualPartner;

    public GarminFitExportOptions(boolean includeCutoffs, boolean includeRestTimes,
            boolean customIcons, boolean virtualPartner) {
        this.includeCutoffs = includeCutoffs;
        this.includeRestTimes = includeRestTimes;
        this.customIcons = customIcons;
        this.virtualPartner = virtualPartner;
    }

    public static GarminFitExportOptions defaults() {
        return new GarminFitExportOptions(true, true, true, false);
    }
}
