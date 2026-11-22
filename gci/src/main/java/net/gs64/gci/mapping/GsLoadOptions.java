package net.gs64.gci.mapping;

import java.util.function.Consumer;

import net.gs64.gci.GciSession;
import net.gs64.gci.GciTraversalOptions;

/**
 * Options for {@link GsObjectContext#load}.
 *
 * @param depth traversal depth per round trip; only a performance setting: whatever the mapping
 *     needs below it is fetched in further round trips
 * @param bufferSize traversal buffer size, see {@link GciTraversalOptions#bufferSize()}; 0 (the
 *     default) = {@link GciSession#preferredTraversalBufferSize()}: 64 KB for a stone on the same
 *     machine, 1 MB across a network
 * @param trackChanges remember the loaded state for saveChanges and keep the loaded objects in the
 *     export set until the context is closed (one extra round trip per load); off for pure reading
 * @param discardLocalChanges overwrite unsaved changes of instances the load refreshes instead of
 *     failing with {@link GsUnsavedChangesException}
 * @param clamp traverse with a ClampSpecification built from the mapping: instance variables that
 *     are not mapped (or mapped as Oop) and the internals of RcIdentityBags are not followed
 * @param clampLevelsSelector the ClampSpecification's instVarLevelsSelector; must answer nil for
 *     loaded objects and must not be nil (GemStone 3.6.5–3.7.6 crash the gem then)
 * @param stopAtLoadedObjects objects the context already holds are linked, not loaded again: only
 *     the roots of the load (the objects passed to {@link GsObjectContext#refresh}) and objects new
 *     to the context are filled. Without it a refresh loads everything the mapping reaches, which
 *     with bidirectional associations (every object points back to the root) is the whole graph.
 *     Meant for refreshes driven by a change feed, where every changed object is published itself
 *     ({@link GsChangeSync} uses it)
 * @param retainInParallel tracked loads: add the loaded OOPs to the export set (GciSaveObjs) on
 *     another thread while the Java objects are built, instead of afterwards; on by default
 * @param countReportsByClass count the object reports per GemStone class into
 *     {@link GsLoadStatistics#reportsByClass()} (one hash operation per report plus a round trip
 *     per class name), to find out what a large load transfers
 * @param progress called on the loading thread with where the load stands, see
 *     {@link GsLoadProgress}; null = none
 * @param walkDuringTraversal walk the object reports while the traversal is still running (between
 *     two buffers, while the gem fills the next one) instead of after each traversal round; what
 *     needs the server (a class not seen before) waits until the round is over. On by default
 * @param convertInParallel convert the values only the server can convert (LargeInteger,
 *     ScaledDecimal, date/time …) on another thread while the objects are built; objects that need
 *     one are filled when the batch is back. On by default
 */
public record GsLoadOptions(int depth, int bufferSize, boolean trackChanges, boolean discardLocalChanges, boolean clamp, String clampLevelsSelector, boolean stopAtLoadedObjects, boolean retainInParallel, boolean countReportsByClass, Consumer<GsLoadProgress> progress, boolean walkDuringTraversal, boolean convertInParallel) {
    public static final GsLoadOptions DEFAULT = new GsLoadOptions(4, 0, true, false, true, "myDependents", false, true, false, null, true, true);

    public GsLoadOptions withDepth(int value) {
        return new GsLoadOptions(value, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withBufferSize(int value) {
        return new GsLoadOptions(depth, value, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withTrackChanges(boolean value) {
        return new GsLoadOptions(depth, bufferSize, value, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withDiscardLocalChanges(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, value, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withClamp(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, value, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withClampLevelsSelector(String value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, value, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withStopAtLoadedObjects(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, value, retainInParallel, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withRetainInParallel(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, value, countReportsByClass, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withCountReportsByClass(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, value, progress, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withProgress(Consumer<GsLoadProgress> value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, value, walkDuringTraversal, convertInParallel);
    }

    public GsLoadOptions withWalkDuringTraversal(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, value, convertInParallel);
    }

    public GsLoadOptions withConvertInParallel(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects, retainInParallel, countReportsByClass, progress, walkDuringTraversal, value);
    }
}
