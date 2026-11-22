package net.gs64.gci.mapping;

import java.time.Duration;

/**
 * Where a running load stands, passed to {@link GsLoadOptions#progress()} on the loading thread (at
 * most about every 200 ms, plus once at the start of each phase and at the end). The validation of
 * the mapping before (see {@link GsLoadStatistics#prepare()}) is not reported.
 *
 * @param phase the current phase
 * @param reports object reports received so far (grows during {@link Phase#DISCOVER})
 * @param objects Java objects created or filled so far (grows during {@link Phase#BUILD})
 * @param objectsTotal mapped objects the load reaches, known from the end of discover (0 before)
 * @param elapsed since the start of the load
 */
public record GsLoadProgress(Phase phase, long reports, int objects, int objectsTotal, Duration elapsed) {
    /** Phase of a load. */
    public enum Phase {
        /** Traversals: {@link #reports()} counts the object reports received so far. */
        DISCOVER,
        /** Creating and filling the Java objects: {@link #objects()} of {@link #objectsTotal()}. */
        BUILD,
        /** The load has finished (reported once, at the end). */
        DONE
    }

    /** Share of the build phase done, 0 to 1 (0 before build, 1 when done). */
    public double buildFraction() {
        return phase == Phase.DONE ? 1 : objectsTotal == 0 ? 0 : Math.min(1, (double) objects / objectsTotal);
    }
}
