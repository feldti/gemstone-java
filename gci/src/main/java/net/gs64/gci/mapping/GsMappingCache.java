package net.gs64.gci.mapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.gs64.gci.GciSession;
import net.gs64.gci.Oop;
import net.gs64.gci.mapping.GsObjectContext.CollectionClass;

/**
 * Mapping metadata of one session, shared by several {@link GsObjectContext}s: the shapes of the
 * Java types, the GemStone classes they were validated against (class OOPs, instance variable
 * indexes) and facts about server classes (superclasses, collection kinds, class names). Without
 * it, every new context builds and validates all of that again on its first load.
 *
 * <p>Keep one per session for an application that creates a context per load:
 * {@code new GsObjectContext(cache)}. The cache trusts that classes do not change while it lives:
 * after redefining mapped classes (new class versions, other instance variables) call
 * {@link #clear()}. Not thread-safe; the contexts that share it must not load at the same time.
 */
public final class GsMappingCache {
    private final GciSession session;
    private final GsMappingProvider mappings;

    GsShapeFactory shapes;
    /** Resolved type maps (class OOP and instVar indexes known): the source of the ClampSpecification. */
    final List<GsTypeMap> resolvedMaps = new ArrayList<>();
    /** Class → all its superclasses: subclass checks without a round trip. */
    final Map<Oop, Set<Oop>> superclasses = new HashMap<>();
    final Map<Oop, CollectionClass> collections = new HashMap<>();
    final Map<String, Oop> kernelClasses = new HashMap<>();
    final Map<Oop, String> classNames = new HashMap<>();

    /** A cache for contexts on {@code session} with the metadata of {@code mappings}. */
    public GsMappingCache(GciSession session, GsMappingProvider mappings) {
        this.session = Objects.requireNonNull(session, "session");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.shapes = new GsShapeFactory(mappings);
    }

    /** The session the cached class facts belong to. */
    public GciSession session() {
        return session;
    }

    /** The metadata provider. */
    public GsMappingProvider mappings() {
        return mappings;
    }

    /** Forgets everything, e.g. after mapped classes were redefined on the server. */
    public void clear() {
        shapes = new GsShapeFactory(mappings);
        resolvedMaps.clear();
        superclasses.clear();
        collections.clear();
        kernelClasses.clear();
        classNames.clear();
    }
}
