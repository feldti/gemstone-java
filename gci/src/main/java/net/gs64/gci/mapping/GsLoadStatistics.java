package net.gs64.gci.mapping;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Where the time of a load went ({@link GsObjectContext#lastLoad()}). The phases follow each other,
 * except retain and convert, which run during build; traverse is part of discover.
 */
public final class GsLoadStatistics {
    long total, prepare, prepareResolve, discover, traverse, traverseWait, convert, convertWait, build, retain, retainWait;
    int objects;
    long reports, duplicateReports, emptyCollections;
    Map<String, Long> reportsByClass;

    GsLoadStatistics() {
    }

    /** The whole load as the caller sees it: prepare, the phases, and for a load from Smalltalk source its execution. */
    public Duration total() {
        return Duration.ofNanos(total);
    }

    /** Shapes of the Java types and their validation against the image (see {@link GsMappingCache} to share it between contexts). */
    public Duration prepare() {
        return Duration.ofNanos(prepare);
    }

    /** Of {@link #prepare()}: resolving the mapped classes on the server (one round trip). */
    public Duration prepareResolve() {
        return Duration.ofNanos(prepareResolve);
    }

    /** Finding everything the mapping needs: traversals plus walking the reports on the client. */
    public Duration discover() {
        return Duration.ofNanos(discover);
    }

    /** Of {@link #discover()}: the traversals (GCI calls: network and server). */
    public Duration traverse() {
        return Duration.ofNanos(traverse);
    }

    /**
     * Of {@link #traverse()}: time the client only waited for the gem and the network (the first
     * buffer of each traversal, and of each further buffer what copying the previous one did not hide).
     */
    public Duration traverseWait() {
        return Duration.ofNanos(traverseWait);
    }

    /**
     * Values converted on the server in one batch (LargeInteger, ScaledDecimal, date/time …). Runs on
     * another thread while the objects are built ({@link GsLoadOptions#convertInParallel()}); only
     * {@link #convertWait()} adds to the load.
     */
    public Duration convert() {
        return Duration.ofNanos(convert);
    }

    /** How long building waited for {@link #convert()}. */
    public Duration convertWait() {
        return Duration.ofNanos(convertWait);
    }

    /** Creating and filling the Java objects (includes the application's constructors). */
    public Duration build() {
        return Duration.ofNanos(build);
    }

    /**
     * Adding the OOPs of tracked objects to the export set (GciSaveObjs). Runs on another thread
     * while the objects are built; only {@link #retainWait()} adds to the load.
     */
    public Duration retain() {
        return Duration.ofNanos(retain);
    }

    /** How long the load waited for {@link #retain()} after building. */
    public Duration retainWait() {
        return Duration.ofNanos(retainWait);
    }

    /** Java instances created or filled. */
    public int objects() {
        return objects;
    }

    /** Object reports the traversals delivered. */
    public long reports() {
        return reports;
    }

    /** Of {@link #reports()}: objects a later traversal round reported again. Transferred and decoded for nothing. */
    public long duplicateReports() {
        return duplicateReports;
    }

    /** Collections reached that are empty (each one is a report of its own and a Java collection). */
    public long emptyCollections() {
        return emptyCollections;
    }

    /** Reports per GemStone class name; only with {@link GsLoadOptions#countReportsByClass()}, otherwise null. */
    public Map<String, Long> reportsByClass() {
        return reportsByClass;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    @Override
    public String toString() {
        return String.format("%,d objects, %,d reports (%,d again) in %d ms: prepare %d ms (resolve %d ms), discover %d ms (traverse %d ms, waiting %d ms), "
                        + "convert %d ms (waited %d ms), build %d ms, retain %d ms (waited %d ms), %,d empty collections",
                objects, reports, duplicateReports, ms(total), ms(prepare), ms(prepareResolve), ms(discover), ms(traverse), ms(traverseWait),
                ms(convert), ms(convertWait), ms(build), ms(retain), ms(retainWait), emptyCollections)
                + (reportsByClass == null ? "" : "; reports by class: " + reportsByClass.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> e.getKey() + " " + String.format("%,d", e.getValue())).collect(Collectors.joining(", ")));
    }
}
