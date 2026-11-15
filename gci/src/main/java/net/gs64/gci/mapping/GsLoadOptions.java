package net.gs64.gci.mapping;

import net.gs64.gci.GciTraversalOptions;

/**
 * Options for {@link GsObjectContext#load}.
 *
 * @param depth traversal depth per round trip; only a performance setting: whatever the mapping
 *     needs below it is fetched in further round trips
 * @param bufferSize traversal buffer size, see {@link GciTraversalOptions#bufferSize()}
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
 */
public record GsLoadOptions(int depth, int bufferSize, boolean trackChanges, boolean discardLocalChanges, boolean clamp,
                            String clampLevelsSelector, boolean stopAtLoadedObjects) {
    public static final GsLoadOptions DEFAULT = new GsLoadOptions(4, GciTraversalOptions.DEFAULT_BUFFER_SIZE, true, false, true, "myDependents", false);

    public GsLoadOptions withDepth(int value) {
        return new GsLoadOptions(value, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects);
    }

    public GsLoadOptions withBufferSize(int value) {
        return new GsLoadOptions(depth, value, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects);
    }

    public GsLoadOptions withTrackChanges(boolean value) {
        return new GsLoadOptions(depth, bufferSize, value, discardLocalChanges, clamp, clampLevelsSelector, stopAtLoadedObjects);
    }

    public GsLoadOptions withDiscardLocalChanges(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, value, clamp, clampLevelsSelector, stopAtLoadedObjects);
    }

    public GsLoadOptions withClamp(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, value, clampLevelsSelector, stopAtLoadedObjects);
    }

    public GsLoadOptions withClampLevelsSelector(String value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, value, stopAtLoadedObjects);
    }

    public GsLoadOptions withStopAtLoadedObjects(boolean value) {
        return new GsLoadOptions(depth, bufferSize, trackChanges, discardLocalChanges, clamp, clampLevelsSelector, value);
    }
}
