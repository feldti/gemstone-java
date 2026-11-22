package net.gs64.gci.mapping;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.gs64.gci.GciException;
import net.gs64.gci.GciSession;
import net.gs64.gci.GciTransactionConflictException;
import net.gs64.gci.GciTraversalOptions;
import net.gs64.gci.KnownClasses;
import net.gs64.gci.Oop;
import net.gs64.gci.internal.SessionAccess;

/**
 * Loads GemStone object graphs into Java objects mapped with {@link GsClass} and
 * {@link GsInstVar}, tracks changes and writes them back with {@link #saveChanges()}.
 *
 * <p>A load starts with one traversal from the root; the mapping then decides which objects are
 * needed. Missing parts are fetched in batches (one traversal per round), hashed collections (Set,
 * Bag, dictionaries) are flattened on the server in one extra round trip per round.
 *
 * <p>The context keeps an identity map: within a context every GemStone object becomes exactly
 * one Java object per mapped class (shared references and cycles are preserved); loading it again
 * refreshes that instance. If the instance has unsaved changes, the load throws a
 * {@link GsUnsavedChangesException} before changing anything (unless
 * {@link GsLoadOptions#discardLocalChanges()}). Mapping metadata is validated against the image on
 * first use of a type. Tracked objects are kept in the export set until {@link #close()} or
 * {@link #clear()}. Not thread-safe.
 */
public final class GsObjectContext implements AutoCloseable {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String CLAMP_SPECS = GsObjectContext.class.getName() + ".clampSpecs";

    final GciSession session;
    /** Shapes, resolved classes and class facts; shared with other contexts of the session (see {@link GsMappingCache}). */
    final GsMappingCache meta;

    /** OOP → the Java instance, or a {@link Several} when the object was loaded as more than one Java class. */
    final Map<Oop, Object> identity = new HashMap<>();

    static final class Several {
        final List<Object> items;

        Several(List<Object> items) {
            this.items = items;
        }
    }

    /** OOPs of untracked instances; tracked ones have theirs in {@link #entries}. */
    final Map<Object, Oop> oops = new IdentityHashMap<>();
    final Map<Object, EntityEntry> entries = new IdentityHashMap<>();
    /** OOPs this context holds in the export set (released by close / clear). */
    final Set<Oop> retained = new HashSet<>();
    private Oop clampSpec = Oop.ILLEGAL; // owned by the session
    private int clampSpecMaps = -1;
    private String clampSpecSelector;
    /** Object reports the traversals of the last load or refresh delivered (for tests and measurements). */
    long lastLoadReports;

    /** Object reports the last load or refresh read from the server (diagnostics). */
    public long lastLoadReports() {
        return lastLoadReports;
    }

    GsLoadStatistics lastLoad;

    /** Where the time of the last load or refresh went (null before the first). */
    public GsLoadStatistics lastLoad() {
        return lastLoad;
    }

    private boolean emptyCollectionsAsNil;

    /** See {@link #setEmptyCollectionsAsNil}. */
    public boolean emptyCollectionsAsNil() {
        return emptyCollectionsAsNil;
    }

    /**
     * Empty collections are nil on the server: nil in a collection field loads as an empty Java
     * collection, and an empty collection is not stored (a field that is nil on the server stays nil,
     * a new object gets nil instead of an empty collection). Saves a report and an object per empty
     * to-many association. Only fields, not collections inside collections, not with a converter.
     * Collections that exist on the server stay until the data is converted.
     */
    public void setEmptyCollectionsAsNil(boolean value) {
        emptyCollectionsAsNil = value;
    }
    /**
     * Collection OOP -> the object that holds it in a field (or a {@code List} of several), as
     * loaded: lets refresh find the owner of a reported collection without scanning every
     * snapshot (825,000 tracked objects: ~130 ms per refresh). Entries may be stale (a collection
     * replaced later): that only refreshes an owner needlessly. Nested and newly saved collections
     * are not in it; refresh scans the snapshots for those.
     */
    final Map<Oop, Object> collectionOwnerIndex = new HashMap<>();
    boolean ownRoot;

    /** A context that finds the mapping metadata by reflection. */
    public GsObjectContext(GciSession session) {
        this(session, new ReflectionMappingProvider());
    }

    /** A context with the given mapping metadata (a {@link ReflectionMappingProvider} with registered classes, or generated metadata). */
    public GsObjectContext(GciSession session, GsMappingProvider mappings) {
        this(new GsMappingCache(session, mappings));
    }

    /**
     * A context that shares its mapping metadata with other contexts of the same session, e.g. one
     * context per unit of work: the mapping is validated once per cache, not once per context.
     */
    public GsObjectContext(GsMappingCache cache) {
        this.meta = Objects.requireNonNull(cache, "cache");
        this.session = cache.session();
    }

    /** The session this context loads from and saves to. */
    public GciSession session() {
        return session;
    }

    // =========================================================================================
    // Loading
    // =========================================================================================

    /** {@link #load(Oop, Type, GsLoadOptions)} with default options. */
    public <T> T load(Oop root, Class<T> type) {
        return load(root, type, null);
    }

    /**
     * Loads the object graph below {@code root} as {@code type}: a {@link GsClass} type, a
     * collection of them or a scalar type. For a generic collection type pass a {@link Type}
     * (e.g. from a field or {@code new TypeRef<List<Item>>() {}.type()}).
     *
     * @throws GsMappingException the mapping does not fit the image or the data
     */
    public <T> T load(Oop root, Class<T> type, GsLoadOptions options) {
        return type.cast(load(root, (Type) type, options));
    }

    /** {@link #load(Oop, Class, GsLoadOptions)} for any (generic) type. */
    public Object load(Oop root, Type type, GsLoadOptions options) {
        var opts = options == null ? GsLoadOptions.DEFAULT : options;
        if (opts.depth() < 1) throw new IllegalArgumentException("depth must be at least 1.");
        var start = System.nanoTime();
        var shape = prepare(type);
        var run = new LoadRun(this, opts, !ownRoot, start, System.nanoTime() - start, takeResolveTime());
        var roots = List.of(new LoadRun.Root(root, shape, "root"));
        run.discover(roots);
        checkCancelled(); // the last point where the context is unchanged
        var result = run.build(roots)[0];
        if (lastLoad != null) lastLoad.total = System.nanoTime() - start;
        if (result == null && Types.raw(type).isPrimitive())
            throw GsMappingException.of("root: nil cannot be loaded as " + type.getTypeName() + ".");
        return result;
    }

    /** Executes Smalltalk and loads the result. */
    public <T> T load(String source, Class<T> type) {
        return type.cast(load(source, (Type) type, null));
    }

    /** Executes Smalltalk and loads the result. */
    public <T> T load(String source, Class<T> type, GsLoadOptions options) {
        return type.cast(load(source, (Type) type, options));
    }

    /** Loads a generic type, e.g. {@code load(root, new TypeRef<List<Account>>() {})}. */
    @SuppressWarnings("unchecked")
    public <T> T load(Oop root, TypeRef<T> type, GsLoadOptions options) {
        return (T) load(root, type.type(), options);
    }

    /** Executes Smalltalk and loads the result as a generic type. */
    @SuppressWarnings("unchecked")
    public <T> T load(String source, TypeRef<T> type, GsLoadOptions options) {
        return (T) load(source, type.type(), options);
    }

    /** Executes Smalltalk and loads the result as a generic type. */
    public <T> T load(String source, TypeRef<T> type) {
        return load(source, type, null);
    }

    /** Executes Smalltalk and loads the result as any (generic) type. */
    public Object load(String source, Type type, GsLoadOptions options) {
        var start = System.nanoTime();
        var root = session.execute(source);
        var keep = false;
        var loaded = false;
        try {
            ownRoot = true;
            var result = load(root, type, options);
            loaded = true;
            keep = retained.contains(root); // tracked: the context owns the result now
            return result;
        } finally {
            ownRoot = false;
            if (!keep) session.release(root);
            if (loaded && lastLoad != null) lastLoad.total = System.nanoTime() - start;
        }
    }

    // =========================================================================================
    // Asynchronous variants
    // =========================================================================================

    /**
     * {@link #load(Oop, Type, GsLoadOptions)} on another thread, for callers that must not block (a
     * UI thread). While the gem works, the GCI lock is free (other sessions of the process go on).
     *
     * <p>{@link CompletableFuture#cancel(boolean) cancel} requests a stop, honored until the objects
     * are built (between traversal buffers and rounds); the future then completes with a
     * {@link CancellationException} and the context is as it was. Once building has started, the load
     * completes. The context is not thread-safe: do not use it until the future has completed. The
     * Java objects are constructed on the other thread, and {@link GsLoadOptions#progress()} is called
     * there.
     */
    public CompletableFuture<Object> loadAsync(Oop root, Type type, GsLoadOptions options) {
        return runAsync(() -> load(root, type, options));
    }

    /** {@link #loadAsync(Oop, Type, GsLoadOptions)} for a class. */
    public <T> CompletableFuture<T> loadAsync(Oop root, Class<T> type, GsLoadOptions options) {
        return runAsync(() -> load(root, type, options));
    }

    /** {@link #load(String, Class, GsLoadOptions)} on another thread; see {@link #loadAsync(Oop, Type, GsLoadOptions)}. */
    public <T> CompletableFuture<T> loadAsync(String source, Class<T> type, GsLoadOptions options) {
        Objects.requireNonNull(source, "source");
        return runAsync(() -> load(source, type, options));
    }

    /** {@link #load(String, TypeRef, GsLoadOptions)} on another thread; see {@link #loadAsync(Oop, Type, GsLoadOptions)}. */
    public <T> CompletableFuture<T> loadAsync(String source, TypeRef<T> type, GsLoadOptions options) {
        Objects.requireNonNull(source, "source");
        return runAsync(() -> load(source, type, options));
    }

    /** {@link #refresh(Collection, GsLoadOptions)} on another thread; cancellation as for {@link #loadAsync(Oop, Type, GsLoadOptions)}. */
    public CompletableFuture<List<Object>> refreshAsync(Collection<Oop> oopsToRefresh, GsLoadOptions options) {
        Objects.requireNonNull(oopsToRefresh, "oopsToRefresh");
        return runAsync(() -> refresh(oopsToRefresh, options));
    }

    /**
     * {@link #saveChanges()} on another thread (comparing a large context takes a while, the batch
     * waits for the gem with the GCI lock free). Cancellation is honored until the batch is sent.
     */
    public CompletableFuture<Integer> saveChangesAsync() {
        return runAsync(this::saveChanges);
    }

    /** {@link #saveChanges(Iterable)} on another thread; cancellation as for {@link #saveChangesAsync()}. */
    public CompletableFuture<Integer> saveChangesAsync(Iterable<?> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return runAsync(() -> saveChanges(candidates));
    }

    /**
     * {@link #saveChangesAndCommit(int)} on another thread. Cancellation is honored before a batch is
     * sent, also before a repeated one after a conflict (the changes are then unsaved, as after a
     * failed commit).
     */
    public CompletableFuture<Integer> saveChangesAndCommitAsync(int maxAttempts) {
        return runAsync(() -> saveChangesAndCommit(maxAttempts));
    }

    /** {@link #saveChangesAndCommit(Iterable, int)} on another thread; cancellation as for {@link #saveChangesAndCommitAsync(int)}. */
    public CompletableFuture<Integer> saveChangesAndCommitAsync(Iterable<?> candidates, int maxAttempts) {
        Objects.requireNonNull(candidates, "candidates");
        return runAsync(() -> saveChangesAndCommit(candidates, maxAttempts));
    }

    /**
     * Platform threads for the asynchronous variants (the GCI's native waits would pin a virtual
     * thread's carrier); kept for a minute.
     */
    private static final ExecutorService ASYNC = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("gs-context-", 0).factory());

    /** Platform threads for work a load does beside building (retain, conversion batch). */
    static final ExecutorService BACKGROUND = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("gs-load-", 0).factory());

    /** The future of the running asynchronous operation; its cancellation is checked at the points that leave the context consistent. */
    private volatile OperationFuture<?> running;

    /** A future whose {@code cancel} only requests a stop; it completes when the operation has stopped. */
    private static final class OperationFuture<T> extends CompletableFuture<T> {
        volatile boolean cancelRequested;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) return false;
            cancelRequested = true;
            return true;
        }

        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new CompletableFuture<>();
        }
    }

    private <T> CompletableFuture<T> runAsync(java.util.function.Supplier<T> work) {
        var future = new OperationFuture<T>();
        synchronized (this) {
            if (running != null) throw new IllegalStateException("An asynchronous operation of this context is still running; wait for it first.");
            running = future;
        }
        ASYNC.execute(() -> {
            try {
                var result = work.get();
                finish();
                future.complete(result);
            } catch (CancellationException e) {
                finish();
                future.completeExceptionally(e);
            } catch (Throwable t) {
                finish();
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private synchronized void finish() {
        running = null;
    }

    /** Throws a {@link CancellationException} if the running asynchronous operation was cancelled. */
    void checkCancelled() {
        var f = running;
        if (f != null && f.cancelRequested) throw new CancellationException("The operation was cancelled; the context is unchanged.");
    }

    /** The instance of {@code type} (or a subclass) this context holds for {@code oop}, or null. */
    Object find(Oop oop, Class<?> type) {
        var found = identity.get(oop);
        if (found == null) return null;
        if (found instanceof Several several) {
            for (var e : several.items) if (type.isInstance(e)) return e;
            return null;
        }
        return type.isInstance(found) ? found : null;
    }

    void remember(Oop oop, Object entity) {
        var found = identity.get(oop);
        if (found == null) identity.put(oop, entity);
        else if (found instanceof Several several) several.items.add(entity);
        else identity.put(oop, new Several(new ArrayList<>(List.of(found, entity))));
    }

    private static List<Object> instances(Object found) {
        return found instanceof Several s ? s.items : List.of(found);
    }

    /**
     * Loads the objects with these OOPs again, in one load, into the same instances (e.g. the
     * changed objects a signal listener reports). Abort the session first to see what other
     * sessions committed. A collection that a loaded object holds in a field refreshes that
     * object; other OOPs are ignored.
     *
     * @return the refreshed objects (each once)
     * @throws GsUnsavedChangesException a refreshed object has unsaved changes; nothing was changed
     */
    public List<Object> refresh(Collection<Oop> oopsToRefresh, GsLoadOptions options) {
        var opts = options == null ? GsLoadOptions.DEFAULT : options;
        if (opts.depth() < 1) throw new IllegalArgumentException("depth must be at least 1.");
        var start = System.nanoTime();
        var result = refreshTargets(oopsToRefresh);
        if (result.isEmpty()) return List.of();
        var roots = new ArrayList<LoadRun.Root>();
        for (var e : result) roots.add(new LoadRun.Root(oopOf(e), prepare(e.getClass()), e.getClass().getSimpleName() + " " + oopOf(e)));
        var run = new LoadRun(this, opts, false, start, System.nanoTime() - start, takeResolveTime());
        run.discover(roots);
        checkCancelled();
        run.build(roots);
        if (lastLoad != null) lastLoad.total = System.nanoTime() - start;
        return result;
    }

    /** The loaded objects a refresh of these OOPs would load again (each once). */
    List<Object> refreshTargets(Collection<Oop> oopsToRefresh) {
        var result = new ArrayList<Object>();
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Map<Oop, List<Object>> owners = null;
        for (var oop : oopsToRefresh) {
            var found = identity.get(oop);
            if (found != null) {
                for (var e : instances(found)) if (seen.add(e)) result.add(e);
                continue;
            }
            var indexed = collectionOwnerIndex.get(oop);
            if (indexed != null) {
                for (var e : owners(indexed)) if (seen.add(e)) result.add(e);
                continue;
            }
            if (entries.isEmpty()) continue;
            if (owners == null) owners = collectionOwners();
            var collectionOwners = owners.get(oop);
            if (collectionOwners != null) for (var e : collectionOwners) if (seen.add(e)) result.add(e);
        }
        return result;
    }

    /**
     * The mapped objects the tracked owners hold in their collections (also nested ones), as of
     * their snapshots. Untracked owners contribute nothing.
     */
    java.util.Set<Object> collectionElements(Collection<Object> owners) {
        var elements = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (var owner : owners) {
            var entry = entries.get(owner);
            if (entry == null) continue;
            for (var snap : entry.snapshots()) if (snap.collection != null) addElements(snap.collection, elements);
        }
        return elements;
    }

    private static void addElements(CollectionSnapshot c, java.util.Set<Object> elements) {
        for (var e : c.elements) {
            if (e.nested() != null) addElements(e.nested(), elements);
            else if (e.value() != null && isEntity(e.value())) elements.add(e.value());
        }
    }

    /** {@link #refresh(Collection, GsLoadOptions)} with default options. */
    public List<Object> refresh(Collection<Oop> oopsToRefresh) {
        return refresh(oopsToRefresh, null);
    }

    /** Collections held in fields of tracked objects (also nested ones), with their owner. */
    private Map<Oop, List<Object>> collectionOwners() {
        var owners = new HashMap<Oop, List<Object>>();
        for (var entry : entries.values())
            for (var snap : entry.snapshots())
                if (snap.collection != null) addOwner(owners, snap.collection, entry.entity);
        return owners;
    }

    private static void addOwner(Map<Oop, List<Object>> owners, CollectionSnapshot c, Object owner) {
        var list = owners.computeIfAbsent(c.oop, k -> new ArrayList<>());
        if (list.stream().noneMatch(o -> o == owner)) list.add(owner);
        for (var e : c.elements) if (e.nested() != null) addOwner(owners, e.nested(), owner);
    }

    void rememberCollectionOwner(Oop collection, Object owner) {
        var known = collectionOwnerIndex.get(collection);
        if (known == null) collectionOwnerIndex.put(collection, owner); // usually one owner: no list
        else if (known instanceof OwnerList list) { if (list.stream().noneMatch(o -> o == owner)) list.add(owner); }
        else if (known != owner) {
            var list = new OwnerList();
            list.add(known);
            list.add(owner);
            collectionOwnerIndex.put(collection, list);
        }
    }

    /** Several owners of one collection (an own type: an owner could be a List itself). */
    private static final class OwnerList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
    }

    private static List<Object> owners(Object indexed) {
        return indexed instanceof OwnerList list ? list : List.of(indexed);
    }

    Oop oopOf(Object entity) {
        var oop = oopFor(entity);
        if (oop == null) throw new java.util.NoSuchElementException(entity + " is not known to this context.");
        return oop;
    }

    /** OOP of an object loaded (or added) by this context, or null. */
    public Oop oopFor(Object entity) {
        var entry = entries.get(entity);
        if (entry != null) return entry.oop;
        return oops.get(entity);
    }

    /**
     * The Java object this context holds for {@code oop}, or null. If the object was loaded as
     * several Java classes, this is the first one; {@link #entitiesFor} answers all.
     */
    public Object entityFor(Oop oop) {
        var found = identity.get(oop);
        return found instanceof Several s ? s.items.get(0) : found;
    }

    /** All Java objects this context holds for {@code oop}: one per Java class it was loaded as. */
    public List<Object> entitiesFor(Oop oop) {
        var found = identity.get(oop);
        return found == null ? List.of() : List.copyOf(instances(found));
    }

    /**
     * Validates the mapping for {@code type} and everything reachable from it against the image,
     * without loading anything.
     *
     * @throws GsMappingException all problems found, in one exception
     */
    public void validate(Type type) {
        prepare(type);
    }

    // =========================================================================================
    // Metadata
    // =========================================================================================

    GsShape prepare(Type type) {
        var errors = new ArrayList<String>();
        var shape = meta.shapes.shapeOf(type, errors, Types.raw(type).getSimpleName());
        var maps = new ArrayList<>(meta.shapes.newMaps);
        meta.shapes.newMaps.clear();
        var start = System.nanoTime();
        resolveAll(maps.stream().filter(m -> !m.polymorphic).toList(), errors);
        lastResolve += System.nanoTime() - start;
        if (!errors.isEmpty()) {
            meta.shapes = new GsShapeFactory(meta.mappings()); // not usable: start over next time (e.g. after the image was fixed)
            throw GsMappingException.of(errors);
        }
        meta.resolvedMaps.addAll(maps);
        return shape;
    }

    /** Time prepare spent resolving classes on the server, for the next load's statistics. */
    private long lastResolve;

    private long takeResolveTime() {
        var t = lastResolve;
        lastResolve = 0;
        return t;
    }

    /** Per session and selector: the specification for all mappings used in it. */
    static final class ClampState {
        Oop spec = Oop.ILLEGAL;
        /** GemStone class → instance variables some mapping follows. */
        final Map<Oop, Set<String>> followed = new HashMap<>();
    }

    /**
     * Traversal options for a load: with a ClampSpecification that stops at instance variables
     * the mapping does not need. Built once per session and selector, and again when a mapping adds
     * a class or a followed instance variable.
     */
    GciTraversalOptions traversalOptions(GsLoadOptions options) {
        var trav = GciTraversalOptions.DEFAULT.withBufferSize(options.bufferSize() > 0 ? options.bufferSize() : session.preferredTraversalBufferSize());
        if (!options.clamp() || meta.resolvedMaps.isEmpty()) return trav;
        var selector = options.clampLevelsSelector();
        if (selector == null || !IDENTIFIER.matcher(selector).matches())
            throw new IllegalArgumentException("'" + selector + "' is not a unary selector.");
        return SessionAccess.get().locked(() -> {
            @SuppressWarnings("unchecked")
            var specs = (Map<String, ClampState>) SessionAccess.get().attachments(session).computeIfAbsent(CLAMP_SPECS, k -> new HashMap<String, ClampState>());
            var state = specs.computeIfAbsent(selector, k -> new ClampState());
            if (clampSpecMaps != meta.resolvedMaps.size() || !selector.equals(clampSpecSelector) || !clampSpec.equals(state.spec)) {
                var changed = state.spec.equals(Oop.ILLEGAL);
                for (var map : meta.resolvedMaps) {
                    if (map.polymorphic) continue;
                    var followed = state.followed.get(map.classOop);
                    if (followed == null) {
                        followed = new TreeSet<>();
                        state.followed.put(map.classOop, followed);
                        changed = true;
                    }
                    for (var p : map.properties)
                        if (p.shape.kind != ShapeKind.OOP && followed.add(p.instVar())) changed = true;
                }
                if (changed) {
                    var entriesSource = new StringBuilder();
                    var sorted = new TreeMap<Long, Set<String>>(Comparator.comparingLong(Long::longValue));
                    state.followed.forEach((cls, names) -> sorted.put(cls.value(), names));
                    sorted.forEach((cls, names) -> entriesSource.append("{Object _objectForOop: ").append(Long.toUnsignedString(cls)).append(". #(")
                            .append(names.stream().map(n -> "#" + n).collect(Collectors.joining(" "))).append(")}. "));
                    // The previous specification is not released: a load of another context on this
                    // session may still use it. Only a new mapping makes a new one.
                    state.spec = session.execute("""
                            | levels spec |
                            levels := { {RcIdentityBag . #(#components 0)} }.
                            { %s } do: [:e | | cls followed pairs |
                                cls := e at: 1. followed := e at: 2. pairs := {}.
                                cls allInstVarNames do: [:n | (followed includes: n) ifFalse: [pairs := pairs, {n. 0}]].
                                pairs isEmpty ifFalse: [levels := levels, { {cls. pairs} }]].
                            spec := ClampSpecification newWithIdClamps: #() classClamps: #() instVarLevels: levels.
                            spec instVarLevelsSelector: #%s.
                            spec
                            """.formatted(entriesSource, selector));
                }
                clampSpec = state.spec;
                clampSpecMaps = meta.resolvedMaps.size();
                clampSpecSelector = selector;
            }
            return trav.withClampSpec(clampSpec);
        });
    }

    /**
     * A block answering the superclasses of a class. Not {@code allSuperclasses}: that costs about 2
     * ms per class on the server (3.7.6; 420 classes: 0.9 s), walking {@code superclass} nothing.
     */
    private static final String SUPERCLASS_CHAIN =
            "[:cls | | c a | a := Array new. c := cls superclass. [c notNil] whileTrue: [a add: c. c := c superclass]. a]";

    /**
     * Resolves the classes of several type maps in one round trip: class, instance variable names
     * and superclass chain of each (a large model has hundreds of mapped classes; one by one that
     * was several round trips per class plus one per subclass check). The usual collection classes
     * come along the first time (see {@link #primeCollectionClasses}).
     */
    private void resolveAll(List<GsTypeMap> maps, List<String> errors) {
        var batch = new ArrayList<GsTypeMap>();
        for (var map : maps) {
            var name = map.className;
            var dict = map.dictionary;
            if (!IDENTIFIER.matcher(name).matches() || (dict != null && !IDENTIFIER.matcher(dict).matches()))
                errors.add(map.type.getSimpleName() + ": invalid class or dictionary name '" + (dict == null ? "" : dict + ".") + name + "'.");
            else batch.add(map);
        }
        if (batch.isEmpty()) {
            primeCollectionClasses();
            return;
        }
        var entries = new StringBuilder();
        for (var map : batch)
            entries.append(map.dictionary != null ? "{#" + map.className + ". #" + map.dictionary + "}. " : "{#" + map.className + ". nil}. ");
        var prime = kernelCollectionsKnown() ? null : PRIMED_COLLECTION_NAMES;
        var t = session.executeTraverse("""
                | sl up |
                sl := GsCurrentSession currentSession symbolList.
                up := System myUserProfile symbolList.
                { %s. { %s } collect: [:e | | cls d |
                    (e at: 2) isNil
                        ifTrue: [cls := sl objectNamed: (e at: 1)]
                        ifFalse: [
                            d := up objectNamed: (e at: 2).
                            cls := (d isKindOf: AbstractDictionary) ifTrue: [d at: (e at: 1) otherwise: nil] ifFalse: [nil]].
                    cls isNil
                        ifTrue: [0]
                        ifFalse: [
                            cls isBehavior
                                ifFalse: [1]
                                ifTrue: [| s |
                                    s := WriteStream on: String new.
                                    cls allInstVarNames do: [:n | s nextPutAll: n] separatedBy: [s space].
                                    {cls. s contents. %s value: cls}]]] }
                """.formatted(prime == null ? "nil" : collectionClassesSource(prime), entries, SUPERCLASS_CHAIN), 4, null);
        var both = t.get(t.result()).indexedValues();
        if (prime != null) takeCollectionClasses(t, new Oop(both[0]), prime);
        var results = t.get(new Oop(both[1])).indexedValues();
        for (var i = 0; i < batch.size(); i++) {
            var map = batch.get(i);
            var r = new Oop(results[i]);
            if (r.isSpecial()) {
                errors.add(r.equals(Oop.fromSmallInteger(0))
                        ? map.dictionary != null
                        ? map.type.getSimpleName() + ": GemStone class '" + map.className + "' not found in " + map.dictionary + "."
                        : map.type.getSimpleName() + ": GemStone class '" + map.className + "' not found in the symbol list."
                        : map.type.getSimpleName() + ": '" + map.className + "' is not a class.");
                continue;
            }
            var slots = t.get(r).indexedValues();
            var cls = new Oop(slots[0]);
            var text = t.toValue(new Oop(slots[1])) instanceof String str ? str : "";
            var names = text.isEmpty() ? List.<String>of() : List.of(text.split(" "));
            meta.superclasses.put(cls, oopSet(t.get(new Oop(slots[2])).indexedValues()));
            bind(map, cls, names, errors);
        }
    }

    private static Set<Oop> oopSet(long[] values) {
        var set = new HashSet<Oop>(values.length * 2);
        for (var v : values) set.add(new Oop(v));
        return set;
    }

    private void bind(GsTypeMap map, Oop cls, List<String> names, List<String> errors) {
        for (var p : map.properties) {
            var index = names.indexOf(p.instVar());
            if (index < 0) errors.add(p.path + " → '" + p.instVar() + "': no such instance variable in " + map.className + suggest(p.instVar(), names) + ".");
            else p.index = index + 1;
        }
        map.classOop = cls;
        meta.classNames.put(cls, map.className);
    }

    private static String suggest(String name, List<String> candidates) {
        String best = null;
        var bestDistance = Integer.MAX_VALUE;
        for (var c : candidates) {
            var d = distance(name.toLowerCase(), c.toLowerCase());
            if (d <= Math.max(2, name.length() / 3) && d < bestDistance) {
                best = c;
                bestDistance = d;
            }
        }
        return best == null ? " (has: " + String.join(", ", candidates) + ")" : " (did you mean '" + best + "'?)";
    }

    private static int distance(String a, String b) {
        var prev = new int[b.length() + 1];
        for (var j = 0; j <= b.length(); j++) prev[j] = j;
        for (var i = 1; i <= a.length(); i++) {
            var cur = new int[b.length() + 1];
            cur[0] = i;
            for (var j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            prev = cur;
        }
        return prev[b.length()];
    }

    // =========================================================================================
    // Server-side class facts (cached)
    // =========================================================================================

    enum CollectionClass { NONE, SEQUENCE, NSC, UNORDERED, DICTIONARY }

    Oop kernel(String name) {
        var cls = meta.kernelClasses.get(name);
        if (cls == null) {
            if (name.equals("Array")) cls = KnownClasses.ARRAY;
            else {
                cls = session.resolveSymbol(name);
                session.release(cls); // kernel classes are persistent
            }
            meta.kernelClasses.put(name, cls);
        }
        return cls;
    }

    boolean isSubclass(Oop cls, Oop superclass) {
        if (cls.equals(superclass)) return true;
        var chain = meta.superclasses.get(cls);
        if (chain == null) {
            // One round trip per class, then every subclass question about it is answered locally.
            var t = session.executeTraverse(SUPERCLASS_CHAIN + " value: (Object _objectForOop: " + Long.toUnsignedString(cls.value()) + ")", 1, null);
            chain = oopSet(t.get(t.result()).indexedValues());
            meta.superclasses.put(cls, chain);
        }
        return chain.contains(superclass);
    }

    /** Whether the superclasses of {@code cls} are known (subclass questions need no round trip). */
    boolean classKnown(Oop cls) {
        return meta.superclasses.containsKey(cls);
    }

    /** {@link #classify} without a round trip, or null if that would need the server. */
    CollectionClass tryClassifyLocally(Oop cls) {
        var known = meta.collections.get(cls);
        if (known != null) return known;
        if (!classKnown(cls) || !kernelCollectionsKnown()) return null;
        return classify(cls);
    }

    private static final List<String> KERNEL_COLLECTION_NAMES = List.of("AbstractDictionary", "Array", "OrderedCollection", "IdentityBag", "Collection");

    private static final List<String> PRIMED_COLLECTION_NAMES;

    static {
        var names = new ArrayList<>(KERNEL_COLLECTION_NAMES);
        names.addAll(List.of("SortedCollection", "Set", "IdentitySet", "Bag", "Dictionary", "KeyValueDictionary", "IdentityKeyValueDictionary",
                "SymbolDictionary", "StringKeyValueDictionary", "RcIdentityBag", "RcIdentitySet", "RcQueue", "RcKeyValueDictionary",
                "RcLowMaintenanceIdentityBag"));
        PRIMED_COLLECTION_NAMES = List.copyOf(names);
    }

    private boolean kernelCollectionsKnown() {
        return meta.kernelClasses.keySet().containsAll(KERNEL_COLLECTION_NAMES);
    }

    /**
     * The classes collection kinds are told by, and the usual collection classes with their
     * superclasses, in one round trip: a walk during a traversal (no GCI calls) can then classify
     * the collections it meets. Classes missing in an older version are skipped.
     */
    private void primeCollectionClasses() {
        if (kernelCollectionsKnown()) return;
        var t = session.executeTraverse(collectionClassesSource(PRIMED_COLLECTION_NAMES), 3, null);
        takeCollectionClasses(t, t.result(), PRIMED_COLLECTION_NAMES);
    }

    /** Smalltalk answering {@code {class. superclasses}} (or nil) for each name. */
    private static String collectionClassesSource(List<String> names) {
        return "(#(" + names.stream().map(n -> "#" + n).collect(Collectors.joining(" ")) + ") collect: [:n | | c | c := Globals at: n otherwise: nil. "
                + "c isBehavior ifTrue: [{c. " + SUPERCLASS_CHAIN + " value: c}] ifFalse: [nil]])";
    }

    private void takeCollectionClasses(net.gs64.gci.GciTraversal t, Oop array, List<String> names) {
        var results = t.get(array).indexedValues();
        for (var i = 0; i < names.size(); i++) {
            var r = new Oop(results[i]);
            if (r.isNil()) continue;
            var pair = t.get(r).indexedValues();
            var cls = new Oop(pair[0]);
            meta.superclasses.put(cls, oopSet(t.get(new Oop(pair[1])).indexedValues()));
            meta.classNames.put(cls, names.get(i));
            if (KERNEL_COLLECTION_NAMES.contains(names.get(i))) meta.kernelClasses.put(names.get(i), cls);
        }
        for (var name : KERNEL_COLLECTION_NAMES) kernel(name); // missing ones (not expected) the usual way
    }

    CollectionClass classify(Oop cls) {
        var kind = meta.collections.get(cls);
        if (kind == null) {
            kind = isSubclass(cls, kernel("AbstractDictionary")) ? CollectionClass.DICTIONARY
                    : isSubclass(cls, kernel("Array")) || isSubclass(cls, kernel("OrderedCollection")) ? CollectionClass.SEQUENCE
                    : isSubclass(cls, kernel("IdentityBag")) ? CollectionClass.NSC
                    : isSubclass(cls, kernel("Collection")) ? CollectionClass.UNORDERED
                    : CollectionClass.NONE;
            meta.collections.put(cls, kind);
        }
        return kind;
    }

    boolean matchesKind(Oop cls, GsCollectionKind kind) {
        return switch (kind) {
            case ARRAY -> isSubclass(cls, kernel("Array"));
            case ORDERED -> isSubclass(cls, kernel("OrderedCollection"));
            case SET -> isSubclass(cls, kernel("Set"));
            case IDENTITY_SET -> isSubclass(cls, kernel("IdentitySet"));
            case BAG -> isSubclass(cls, kernel("Bag"));
            case IDENTITY_BAG -> isSubclass(cls, kernel("IdentityBag"));
            case DICTIONARY -> isSubclass(cls, kernel("AbstractDictionary"));
        };
    }

    String className(Oop cls) {
        var name = meta.classNames.get(cls);
        if (name == null) {
            name = session.send(cls, "name") instanceof String s ? s : cls.toString();
            meta.classNames.put(cls, name);
        }
        return name;
    }

    /** Picks the type map for an object of GemStone class {@code cls} where {@code expected} is required. */
    GsTypeMap selectMap(GsTypeMap expected, Oop cls) {
        for (var sub : expected.subtypes) if (sub.classOop.equals(cls)) return sub;
        if (!expected.polymorphic && expected.classOop.equals(cls)) return expected;
        for (var sub : expected.subtypes) if (isSubclass(cls, sub.classOop)) return sub;
        return !expected.polymorphic && isSubclass(cls, expected.classOop) ? expected : null;
    }

    // =========================================================================================
    // Saving (see SaveRun)
    // =========================================================================================

    /**
     * Writes all changes of tracked objects to the session: changed fields, changed collection
     * contents and new objects reachable from tracked ones. Does <b>not</b> commit; call
     * {@link GciSession#commit()} afterwards, or use {@link #saveChangesAndCommit(int)}.
     *
     * <p>Only objects loaded with tracking (the default) or added with {@link #add} are tracked;
     * {@link GsReadOnly} fields are skipped. A changed collection is changed in place: only
     * removed, added or replaced elements are written. All changes go to the server as one batch
     * (a store traversal plus one piece of Smalltalk, about six GCI calls). Snapshots change only
     * if the batch succeeds; if it fails, abort, clear the context and reload.
     *
     * <p>An abort ({@link GciSession#abort()}, also after a failed commit) takes back saved but
     * uncommitted changes: the objects count as changed again, and the next save writes them
     * again. A commit or abort in Smalltalk code is not noticed.
     *
     * @return number of instance variables stored plus collections changed
     */
    public int saveChanges() {
        var run = new SaveRun(this);
        for (var entry : new ArrayList<>(entries.values())) run.update(entry);
        run.execute();
        recheck.clear();
        return run.stores;
    }

    /**
     * Like {@link #saveChanges()}, but compares only the given tracked objects with their
     * snapshots instead of all of them, for large contexts whose application knows which objects
     * a transaction changed. New objects reachable from a candidate are saved as usual. Objects
     * whose saved changes an abort took back are compared too, so an abort between save and
     * commit loses nothing.
     *
     * @param candidates objects that may have changed; untracked objects and null are ignored
     */
    public int saveChanges(Iterable<?> candidates) {
        var run = new SaveRun(this);
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var all = new ArrayList<Object>();
        for (var c : candidates) all.add(c);
        all.addAll(recheck);
        for (var candidate : all) {
            if (candidate == null || !seen.add(candidate)) continue;
            var entry = entries.get(candidate);
            if (entry != null) run.update(entry);
        }
        run.execute();
        recheck.clear();
        return run.stores;
    }

    /**
     * {@link #saveChanges()} and commit in one step. On a transaction conflict the session aborts
     * and the changes are written again on top of the newly committed state, up to
     * {@code maxAttempts} times.
     *
     * @throws GciTransactionConflictException still a conflict after the last attempt
     */
    public int saveChangesAndCommit(int maxAttempts) {
        return session.runInTransaction(s -> saveChanges(), maxAttempts);
    }

    /** {@link #saveChangesAndCommit(int)} with 3 attempts. */
    public int saveChangesAndCommit() {
        return saveChangesAndCommit(3);
    }

    /** {@link #saveChanges(Iterable)} and commit, with the same retries (candidates enumerated once). */
    public int saveChangesAndCommit(Iterable<?> candidates, int maxAttempts) {
        var list = new ArrayList<Object>();
        for (var c : candidates) list.add(c);
        return session.runInTransaction(s -> saveChanges(list), maxAttempts);
    }

    /**
     * Starts tracking a new object: the save creates it on the server right away. It only becomes
     * persistent if something persistent references it when the transaction commits.
     *
     * @return the OOP of the new GemStone object
     */
    public Oop add(Object entity) {
        Objects.requireNonNull(entity, "entity");
        var known = oopFor(entity);
        if (known != null) return known;
        var run = new SaveRun(this);
        var oop = run.insert(entity);
        run.execute();
        return oop.resolved;
    }

    /** Forgets all loaded objects (identity map and tracked state) and releases their OOPs; metadata stays cached. */
    public void clear() {
        close();
    }

    /**
     * Releases the OOPs held by this context. Does not throw: if the session is broken, the OOPs
     * cannot be released any more, and an exception here would hide the error that broke it.
     */
    @Override
    public void close() {
        try {
            releaseRetained();
        } catch (GciException | IllegalStateException e) {
            retained.clear(); // the session is unusable; the gem drops its export set at logout
        }
        clampSpec = Oop.ILLEGAL;
        entries.clear();
        identity.clear();
        collectionOwnerIndex.clear();
        oops.clear();
        forgetJournal();
        recheck.clear();
    }

    // ---- Uncommitted saves -----------------------------------------------------------------

    /** How to take back the snapshot changes of saves since the last commit or abort, in order. */
    private final List<Runnable> undo = new ArrayList<>();
    private boolean listening;
    private final Consumer<Boolean> listener = this::onTransactionEnded;
    /** Tracked objects saved since the last commit or abort. */
    final Set<Object> savedSinceCommit = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** Tracked objects whose saved changes an abort took back: saveChanges(candidates) compares them too. */
    final Set<Object> recheck = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    void journalProperty(EntityEntry entry, GsPropertyMap p) {
        var entity = entry.entity;
        var previous = entry.snapshot(p);
        journal(() -> {
            // A reload since the save may have replaced the entry: restore into the current one.
            var current = entries.get(entity);
            if (current != null) current.setSnapshot(p, previous);
        });
    }

    void journalElements(CollectionSnapshot collection) {
        var previous = collection.elements;
        journal(() -> collection.elements = previous);
    }

    private void journal(Runnable action) {
        undo.add(action);
        listenForTransactionEnd();
    }

    void listenForTransactionEnd() {
        if (listening) return;
        SessionAccess.get().addTransactionListener(session, listener);
        listening = true;
    }

    // ---- Change feed ----------------------------------------------------------------------

    /** The feed object saveChanges publishes to, or null. */
    Oop feedObject;
    /** Rings the feed's doorbell after a commit. */
    Runnable feedRing;
    private net.gs64.gci.GciChangeFeed changeFeed;

    /**
     * Change feed that {@link #saveChanges()} publishes to (null: none). Publishing follows the
     * transaction: the changed objects go to the feed in the same batch, and so in the same
     * transaction, as the changes; an abort takes them back with the changes. After a commit
     * through this API the context rings the doorbell once. Published are the tracked objects a
     * save wrote to (the owner of a changed collection, not the collection); new objects are not
     * published, they become reachable through a changed tracked object.
     *
     * @throws IllegalArgumentException the feed uses another session
     */
    public void setChangeFeed(net.gs64.gci.GciChangeFeed feed) {
        if (feed != null && feed.session() != session) throw new IllegalArgumentException("The change feed must use the context's session.");
        changeFeed = feed;
        feedObject = feed == null ? null : feed.object();
        feedRing = feed == null ? null : feed::ring;
    }

    public net.gs64.gci.GciChangeFeed changeFeed() {
        return changeFeed;
    }
    /** A save published to the feed in this transaction: ring after the commit. */
    private boolean ringPending;

    void ringAfterCommit() {
        ringPending = true;
        listenForTransactionEnd();
    }

    private void onTransactionEnded(boolean committed) {
        if (!committed) {
            for (var i = undo.size() - 1; i >= 0; i--) undo.get(i).run();
            recheck.addAll(savedSinceCommit);
        }
        var ring = committed && ringPending ? feedRing : null;
        forgetJournal();
        if (ring == null) return;
        try {
            ring.run();
        } catch (GciException e) {
            // The commit succeeded; consumers take the objects at their next regular take.
        }
    }

    private void forgetJournal() {
        undo.clear();
        savedSinceCommit.clear();
        ringPending = false;
        if (!listening) return;
        SessionAccess.get().removeTransactionListener(session, listener);
        listening = false;
    }

    private void releaseRetained() {
        if (!retained.isEmpty() && session.isLoggedIn()) {
            var all = retained.toArray(Oop[]::new);
            for (var i = 0; i < all.length; i += 10_000) session.release(Arrays.copyOfRange(all, i, Math.min(all.length, i + 10_000)));
        }
        retained.clear();
    }

    /** Adds OOPs to the export set once, so that tracked objects survive until saved. */
    List<Oop> retainAll(Collection<Oop> candidates) {
        var fresh = new LinkedHashMap<Oop, Boolean>();
        for (var o : candidates) if (!o.isSpecial() && !o.equals(Oop.ILLEGAL) && !retained.contains(o)) fresh.put(o, true);
        if (fresh.isEmpty()) return List.of();
        var array = fresh.keySet().toArray(Oop[]::new);
        session.retain(array);
        retained.addAll(fresh.keySet());
        return List.of(array);
    }

    /** Gives back OOPs a failed load retained (see {@link #retainAll}). */
    void releaseRetained(List<Oop> fresh) {
        if (fresh.isEmpty()) return;
        fresh.forEach(retained::remove);
        if (session.isLoggedIn()) {
            var all = fresh.toArray(Oop[]::new);
            for (var i = 0; i < all.length; i += 10_000) session.release(Arrays.copyOfRange(all, i, Math.min(all.length, i + 10_000)));
        }
    }

    // ---- Unsaved changes -------------------------------------------------------------------

    /** Throws if a load would refresh tracked instances that have unsaved changes. */
    void throwIfUnsavedChanges(List<LoadRun.Reached> reached) {
        if (entries.isEmpty()) return;
        List<Object> dirty = null;
        var seen = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (var r : reached) {
            var entity = find(r.oop(), r.type());
            if (entity == null || !seen.add(entity)) continue;
            var entry = entries.get(entity);
            if (entry != null && hasChanges(entry)) {
                if (dirty == null) dirty = new ArrayList<>();
                dirty.add(entity);
            }
        }
        if (dirty != null) throw new GsUnsavedChangesException(dirty);
    }

    /** Whether saveChanges would write anything for this object (the same comparison, without sending). */
    private boolean hasChanges(EntityEntry entry) {
        var run = new SaveRun(this);
        try {
            run.update(entry);
        } catch (UnsupportedOperationException | GsMappingException e) {
            return true; // a changed value that cannot even be written (e.g. a one-way converter)
        }
        return run.hasWork();
    }

    // ---- Comparison ------------------------------------------------------------------------

    private static final ClassValue<Boolean> ENTITY_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getAnnotation(GsClass.class) != null;
        }
    };

    static boolean isEntity(Object o) {
        return ENTITY_TYPES.get(o.getClass());
    }

    /** Entities compare by reference, everything else by value (byte[] by content). */
    static boolean valueEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (isEntity(a) || isEntity(b)) return false;
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        return a.equals(b);
    }
}
