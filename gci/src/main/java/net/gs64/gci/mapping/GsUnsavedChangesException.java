package net.gs64.gci.mapping;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A load would overwrite changes that were not saved yet: it reaches objects the context already
 * holds, and their Java instances differ from the state loaded or saved last. Nothing was
 * changed. Call {@link GsObjectContext#saveChanges()} first, or load with
 * {@link GsLoadOptions#withDiscardLocalChanges(boolean)}.
 */
public class GsUnsavedChangesException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient List<Object> entities;

    public GsUnsavedChangesException(List<Object> entities) {
        super(describe(entities));
        this.entities = List.copyOf(entities);
    }

    /** The objects with unsaved changes that the load would have refreshed. */
    public List<Object> entities() {
        return entities;
    }

    private static String describe(List<Object> entities) {
        var names = entities.stream().limit(5).map(e -> e.getClass().getSimpleName()).collect(Collectors.joining(", "));
        return "The load would overwrite unsaved changes of " + entities.size() + " object(s) (" + names
                + (entities.size() > 5 ? ", …" : "") + "). Call saveChanges first, or load with discardLocalChanges.";
    }
}
