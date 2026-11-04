/*
 * Course Generator - barrier-driven Virtual Partner timeline for Garmin FIT.
 * This is an experimental FIT Course pacing overlay, not a guarantee that
 * a particular Garmin device will use the speed records as its Virtual Partner.
 */
package course_generator;

import java.util.ArrayList;
import java.util.List;

import course_generator.utils.CgConst;

/**
 * Constructs a no-rest virtual opponent by preserving the relative moving-time
 * weights from Course Generator's speed/slope model and scaling each interval
 * between official cut-offs to reach its destination exactly at the cut-off.
 *
 * Station pauses are subtracted from planned times. Each anchor is the recorded
 * cut-off at that roadbook point (seconds since course start). This timeline
 * never pauses; consecutive points with genuine movement always advance.
 */
public final class GarminBarrierVirtualPartner {

    private static final double FALLBACK_SPEED_MPS = 3.333333333;
    private static final double MIN_MOVING_SPEED_MPS = 0.10;
    private static final double MAX_REASONABLE_SPEED_MPS = 30.0;

    private GarminBarrierVirtualPartner() {
    }

    public static final class Anchor {
        public final int pointIndex;
        public final double distanceMeters;
        public final long cutoffSeconds;
        public final String name;

        private Anchor(int index, CgData point) {
            pointIndex = index;
            distanceMeters = point.getTotal(CgConst.UNIT_METER);
            cutoffSeconds = point.getTimeLimit();
            name = point.getName() == null ? "" : point.getName();
        }
    }

    public static final class Timeline {
        /** Relative virtual-clock time at each trackpoint, in seconds. */
        public final long[] elapsedSeconds;
        /** Instantaneous course-target speed (m/s) of each record. */
        public final float[] targetSpeedMps;
        /** Official cut-offs the virtual runner hits exactly, in order. */
        public final List<Anchor> anchors;
        /** Continuous floating-point timeline before FIT-second quantization. */
        public final double[] preciseSeconds;

        private Timeline(long[] elapsed, float[] speed, List<Anchor> cutoffs, double[] precise) {
            elapsedSeconds = elapsed;
            targetSpeedMps = speed;
            anchors = java.util.Collections.unmodifiableList(cutoffs);
            preciseSeconds = precise;
        }
    }

    public static Timeline build(TrackData track) {
        if (track == null || track.data == null || track.data.size() < 2)
            throw new IllegalArgumentException("Le Virtual Partner nécessite au moins deux points de trace.");

        int n = track.data.size();
        boolean hasCalculatedTimeline = track.TotalTime > 0 || track.data.get(n - 1).getTime() > 0;
        double[] moving = new double[n];
        double[] cumulativeDistance = new double[n];
        for (int i = 1; i < n; i++) {
            CgData previous = track.data.get(i - 1);
            CgData point = track.data.get(i);
            double d = point.getTotal(CgConst.UNIT_METER);
            double prior = previous.getTotal(CgConst.UNIT_METER);
            if (!Double.isFinite(d) || d < prior || !Double.isFinite(prior))
                throw new IllegalArgumentException(
                        "Distances non croissantes ou non valides au point " + i + ".");
            double distanceStep = d - prior;
            cumulativeDistance[i] = cumulativeDistance[i - 1] + distanceStep;

            double seconds = 0.0;
            // Course Generator's planned Time at the point INCLUDES its rest.
            // A virtual runner never rests, so subtract that station duration.
            if (hasCalculatedTimeline)
                seconds = (double) point.getTime() - previous.getTime() - Math.max(0, point.getStation());

            if (distanceStep > 0.0) {
                if (!Double.isFinite(seconds) || seconds <= 0.0) {
                    double speed = point.getSpeed(CgConst.UNIT_METER) / 3.6;
                    if (!Double.isFinite(speed) || speed < MIN_MOVING_SPEED_MPS)
                        speed = FALLBACK_SPEED_MPS;
                    seconds = distanceStep / speed;
                }
                moving[i] = moving[i - 1] + Math.max(seconds, distanceStep / MAX_REASONABLE_SPEED_MPS);
            } else {
                // Duplicate positions are not rests for the virtual opponent.
                moving[i] = moving[i - 1];
            }
        }
        if (moving[n - 1] <= 0.0)
            throw new IllegalArgumentException("Distance de parcours nulle : impossible de calculer un partenaire.");

        List<Anchor> anchors = new ArrayList<Anchor>();
        int prevIndex = 0;
        long prevDeadline = 0L;
        for (int i = 1; i < n; i++) {
            CgData point = track.data.get(i);
            if (point.getTimeLimit() <= 0)
                continue;
            if (moving[i] <= moving[prevIndex]) {
                throw new IllegalArgumentException(
                        "Barrière sans déplacement depuis la précédente au point " + i + ".");
            }
            if (point.getTimeLimit() <= prevDeadline) {
                throw new IllegalArgumentException(
                        "Les heures des barrières doivent être strictement croissantes (point " + i + ").");
            }
            anchors.add(new Anchor(i, point));
            prevIndex = i;
            prevDeadline = point.getTimeLimit();
        }
        if (anchors.isEmpty())
            throw new IllegalArgumentException(
                    "Aucune barrière horaire valide. Définis au moins une barrière dans le roadbook.");

        double[] virtual = new double[n];
        int previousAnchorIndex = 0;
        long previousDeadline = 0L;
        double finalScale = 1.0;

        for (Anchor anchor : anchors) {
            int endIndex = anchor.pointIndex;
            double intervalMoving = moving[endIndex] - moving[previousAnchorIndex];
            long available = anchor.cutoffSeconds - previousDeadline;
            if (intervalMoving <= 0.0 || available <= 0)
                throw new IllegalArgumentException("Tronçon de barrière sans durée exploitable.");

            finalScale = available / intervalMoving;
            for (int i = previousAnchorIndex + 1; i <= endIndex; i++) {
                virtual[i] = previousDeadline
                        + (moving[i] - moving[previousAnchorIndex]) * finalScale;
            }
            // Preserve the official cut-off exactly (no floating point drift).
            virtual[endIndex] = anchor.cutoffSeconds;
            previousAnchorIndex = endIndex;
            previousDeadline = anchor.cutoffSeconds;
        }

        // Beyond the last known cutoff, continue at the last segment's
        // terrain-sensitive pacing scale. Never invent a finish cutoff.
        for (int i = previousAnchorIndex + 1; i < n; i++)
            virtual[i] = previousDeadline + (moving[i] - moving[previousAnchorIndex]) * finalScale;

        long[] rounded = new long[n];
        float[] speeds = new float[n];
        for (int i = 1; i < n; i++) {
            rounded[i] = Math.max(rounded[i - 1], Math.round(virtual[i]));
            double dt = virtual[i] - virtual[i - 1];
            double dd = cumulativeDistance[i] - cumulativeDistance[i - 1];
            // Speed is derived from the continuous time values rather than FIT
            // integer timestamps, avoiding artificial spikes at small intervals.
            speeds[i] = (float) (dd > 0.0 && dt > 0.0 ? dd / dt : 0.0);
            if (!Float.isFinite(speeds[i]) || speeds[i] < 0)
                throw new IllegalArgumentException("Vitesse virtuelle non valide au point " + i);
        }
        speeds[0] = n > 1 ? speeds[1] : 0.0f;
        for (Anchor anchor : anchors)
            if (rounded[anchor.pointIndex] != anchor.cutoffSeconds)
                throw new IllegalStateException("Le concurrent virtuel n'atteint pas la barrière prévue.");
        return new Timeline(rounded, speeds, anchors, virtual);
    }
}
