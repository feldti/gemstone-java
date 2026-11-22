package net.gs64.gci.mapping;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.gs64.gci.CodePoint;
import net.gs64.gci.GciObjectReport;
import net.gs64.gci.GciSession;
import net.gs64.gci.GciTraversal;
import net.gs64.gci.GciTraversalOptions;
import net.gs64.gci.KnownClasses;
import net.gs64.gci.Oop;
import net.gs64.gci.internal.SessionAccess;
import net.gs64.gci.mapping.GsObjectContext.CollectionClass;

/** One load: phase 1 fetches everything the mapping needs, phase 2 builds the Java objects. */
final class LoadRun {
    private static final String FLATTEN_BLOCK =
            "[:cs | cs collect: [:c | (c isKindOf: AbstractDictionary)"
                    + " ifTrue: [| a | a := Array new. c keysAndValuesDo: [:k :v | a add: k; add: v]. a]"
                    + " ifFalse: [c asArray]]]";

    record Root(Oop oop, GsShape shape, String where) {
    }

    /** A mapped object the load reaches, with the Java type it is loaded as. */
    record Reached(Oop oop, Class<?> type) {
    }

    private record Node(Oop oop, GsShape shape, String where) {
    }

    private record Job(Object instance, Oop oop, GsTypeMap map) {
    }

    private final GsObjectContext ctx;
    private final GsLoadOptions options;
    private final boolean callerOwnsRoot;
    private final GciSession session;
    private final GciTraversalOptions travOptions;
    private final SessionAccess access = SessionAccess.get();
    private Oop root = Oop.ILLEGAL;
    private final List<Oop> toRetain = new ArrayList<>();
    private final Map<Oop, GciObjectReport> reports = new HashMap<>();
    private final Map<Oop, long[]> flattened = new HashMap<>();
    private final Map<Oop, long[]> dictionaryValues = new HashMap<>();
    /** Values the traversal cannot decode (LargeInteger, ScaledDecimal, date/time …), converted in one batch. */
    private final Set<Oop> toConvert = new LinkedHashSet<>();
    private final Map<Oop, Object> converted = new HashMap<>();
    /** Only deviations from the expected map (polymorphic objects). */
    private final Map<Oop, Map<GsTypeMap, GsTypeMap>> actualMaps = new HashMap<>();
    private final ArrayDeque<Job> fill = new ArrayDeque<>();
    private final List<Reached> reached = new ArrayList<>();
    /** Collections the load reaches (tracked loads only): retained with the objects. */
    private final List<Oop> collectionsReached = new ArrayList<>();
    /** Instances filled in this run (an object reached as two Java types is two instances). */
    private final Set<Object> filled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final GsLoadStatistics stats = new GsLoadStatistics();
    /** Reports per class, with {@link GsLoadOptions#countReportsByClass()}. */
    private final Map<Oop, Long> byClass;
    private final long loadStart;
    private long reportsSoFar;
    private long lastProgress;

    LoadRun(GsObjectContext ctx, GsLoadOptions options, boolean callerOwnsRoot, long loadStart, long prepareTime, long resolveTime) {
        this.ctx = ctx;
        this.options = options;
        this.callerOwnsRoot = callerOwnsRoot;
        this.session = ctx.session;
        this.loadStart = loadStart;
        this.byClass = options.countReportsByClass() ? new HashMap<>() : null;
        stats.prepare = prepareTime;
        stats.prepareResolve = resolveTime;
        ctx.lastLoadReports = 0;
        this.travOptions = ctx.traversalOptions(options);
    }

    /** Reports progress at a phase start ({@code force}) and otherwise at most every 200 ms. */
    private void progress(GsLoadProgress.Phase phase, boolean force) {
        if (options.progress() == null) return;
        var now = System.nanoTime();
        if (!force && now - lastProgress < 200_000_000L) return;
        lastProgress = now;
        options.progress().accept(new GsLoadProgress(phase, reportsSoFar, filled.size(), reached.size(),
                java.time.Duration.ofNanos(now - loadStart)));
    }

    private void count(GciObjectReport r) {
        byClass.merge(r.objectClass(), 1L, Long::sum);
    }

    // ---- Phase 1: fetch everything the mapping needs ----

    void discover(List<Root> roots) {
        var start = System.nanoTime();
        var waited = access.traversalWaitNanos(session);
        progress(GsLoadProgress.Phase.DISCOVER, true);
        try {
            discoverAll(roots);
        } finally {
            stats.discover = System.nanoTime() - start;
            stats.traverseWait = access.traversalWaitNanos(session) - waited;
        }
    }

    // The walk over the reports. Depth first: a breadth-first queue holds the whole frontier of a
    // large graph at once (millions of entries), a stack only the open paths. The order does not matter.
    private final ArrayDeque<Node> stack = new ArrayDeque<>();
    /** Visited per shape. */
    private final Map<GsShape, Set<Oop>> visited = new HashMap<>();
    /** Nodes waiting for the report of their object, by OOP: the next traversal fetches these. */
    private final Map<Oop, List<Node>> waiting = new LinkedHashMap<>();
    /** Nodes the walk during a traversal could not handle without the server (unknown class, partial report …). */
    private final List<Node> deferred = new ArrayList<>();
    /** Hashed collections to flatten on the server, and the nodes waiting for that. */
    private final Set<Oop> toFlatten = new LinkedHashSet<>();
    private final List<Node> blocked = new ArrayList<>();
    private int round;

    private void discoverAll(List<Root> roots) {
        // Reversed: popped (and so fetched) in the order given.
        for (var i = roots.size() - 1; i >= 0; i--) stack.push(new Node(roots.get(i).oop(), roots.get(i).shape(), roots.get(i).where()));
        if (options.stopAtLoadedObjects()) for (var r : roots) rootOops.add(r.oop());
        while (true) {
            ctx.checkCancelled();
            walk(false);
            if (waiting.isEmpty() && toFlatten.isEmpty()) return;
            if (!waiting.isEmpty()) {
                var fetch = waiting.keySet().toArray(Oop[]::new);
                if (options.stopAtLoadedObjects() && round > 0) {
                    // A refresh in a large context: a collection fetched now (one an earlier root
                    // reached too deep to expand, see fetch) mostly holds known objects. With the full
                    // depth the traversal would report all of them; new ones come in the next round.
                    var collections = Arrays.stream(fetch).filter(o -> waiting.get(o).stream().allMatch(n -> n.shape().isCollection())).toArray(Oop[]::new);
                    var colls = Set.of(collections);
                    var others = collections.length == 0 ? fetch : Arrays.stream(fetch).filter(o -> !colls.contains(o)).toArray(Oop[]::new);
                    if (collections.length > 0) fetch(collections, 1);
                    if (others.length > 0) fetch(others, options.depth());
                } else {
                    fetch(fetch, options.depth());
                }
                round++;
                for (var o : fetch)
                    if (!reports.containsKey(o)) throw GsMappingException.of("The server did not report " + o + ".");
                // What the walk during the traversal left for afterwards (it needed the server).
                for (var d : deferred) stack.push(d);
                deferred.clear();
            }
            if (!toFlatten.isEmpty()) {
                flatten(toFlatten.toArray(Oop[]::new));
                toFlatten.clear();
                for (var b : blocked) stack.push(b);
                blocked.clear();
            }
        }
    }

    /** A report arrived: the nodes waiting for it can go on. */
    private void arrived(Oop oop) {
        if (waiting.isEmpty()) return;
        var nodes = waiting.remove(oop);
        if (nodes != null) for (var n : nodes) stack.push(n);
    }

    /**
     * Walks what can be walked. {@code live}: called while a traversal is running (between two
     * buffers, the gem fills the next one): no GCI call is allowed then, so anything that would need
     * the server (a class not seen yet, a report still incomplete) is deferred until afterwards.
     * Errors with a message built locally may be thrown; the traversal is then drained.
     */
    private void walk(boolean live) {
        while (!stack.isEmpty()) {
            var node = stack.pop();
            var oop = node.oop();
            var s = node.shape();
            if (isLoadedElsewhere(oop, s)) continue;
            if ((s.kind == ShapeKind.SCALAR || s.kind == ShapeKind.OBJECT) && oop.isSpecial() && !GciTraversal.canDecode(oop, reports))
                toConvert.add(oop); // SmallScaledDecimal, SmallDate, SmallDateAndTime …
            if (!needsReport(oop, s)) continue;
            var seen = visited.computeIfAbsent(s, k -> new HashSet<>());
            if (!seen.add(oop)) continue;
            var report = reports.get(oop);
            if (report == null) {
                seen.remove(oop);
                waiting.computeIfAbsent(oop, k -> new ArrayList<>(1)).add(node);
                continue;
            }
            if (live && !locallyWalkable(oop, report, s)) {
                seen.remove(oop);
                deferred.add(node);
                continue;
            }
            if (report.noReadAuthorization()) throw GsMappingException.of(node.where() + ": no read authorization for " + oop + ".");
            switch (s.kind) {
                case MAPPED -> {
                    var map = actualMap(oop, report, s.map, node.where());
                    reached.add(new Reached(oop, map.type));
                    for (var p : map.properties) stack.push(new Node(namedValue(report, p, node.where()), p.shape, p.path));
                }
                case SCALAR, OBJECT -> {
                    if (!GciTraversal.canDecode(oop, reports)) toConvert.add(oop);
                }
                case LIST, ARRAY, SET, DICTIONARY -> {
                    var elements = elements(oop, report, s, node.where(), null);
                    if (elements == null) {
                        toFlatten.add(oop);
                        seen.remove(oop);
                        blocked.add(node);
                        continue;
                    }
                    if (options.trackChanges()) collectionsReached.add(oop);
                    if (elements.length == 0) stats.emptyCollections++;
                    if (s.kind == ShapeKind.DICTIONARY) {
                        var keyWhere = "key of " + node.where();
                        var valueWhere = "value of " + node.where();
                        for (var i = 0; i + 1 < elements.length; i += 2) {
                            stack.push(new Node(new Oop(elements[i]), s.key, keyWhere));
                            stack.push(new Node(new Oop(elements[i + 1]), s.value, valueWhere));
                        }
                    } else {
                        var elementWhere = "element of " + node.where();
                        for (var e : elements) stack.push(new Node(new Oop(e), s.element, elementWhere));
                    }
                }
                default -> {
                }
            }
        }
    }

    /**
     * Whether a node can be walked during a traversal: everything it needs is known on the client (the
     * class of a mapped object or collection was seen before, the report is complete), and no error
     * message would have to ask the server for a class name.
     */
    private boolean locallyWalkable(Oop oop, GciObjectReport report, GsShape s) {
        if (report.isPartial() || report.noReadAuthorization()) return false;
        return switch (s.kind) {
            case MAPPED -> {
                var byExpected = actualMaps.get(oop);
                if (byExpected != null && byExpected.containsKey(s.map)) yield true;
                if (!report.objectClass().equals(s.map.classOop) && !ctx.classKnown(report.objectClass())) yield false;
                var map = ctx.selectMap(s.map, report.objectClass()); // local: the superclasses are known
                yield map != null && map.create != null;
            }
            case LIST, ARRAY, SET, DICTIONARY -> {
                var kind = ctx.tryClassifyLocally(report.objectClass());
                yield kind != null && kind != CollectionClass.NONE && (s.kind != ShapeKind.DICTIONARY || kind == CollectionClass.DICTIONARY);
            }
            default -> true;
        };
    }

    private static boolean needsReport(Oop oop, GsShape shape) {
        return !oop.isSpecial() && !oop.equals(Oop.ILLEGAL) && shape.kind != ShapeKind.OOP;
    }

    /**
     * One traversal round. Reports go into the table buffer by buffer while the gem fills the next
     * one; an object larger than a buffer arrives in pieces and goes in merged at the end. Note: the
     * gem reports every object once, at the level it first reaches it; a root that an earlier root
     * reaches deeper down is not expanded again as a root. What it leaves out comes in a later round.
     */
    private void fetch(Oop[] roots, int depth) {
        var start = System.nanoTime();
        var continued = new ArrayList<Oop>();
        var t = access.traverse(session, roots, depth, travOptions, (list, first) -> {
            for (var i = first; i < list.size(); i++) {
                var r = list.get(i);
                if (r.firstOffset() != 1) continued.add(r.oop());
                else {
                    reportsSoFar++;
                    // A later round reports again what an earlier one already delivered: counted, the first report stays.
                    if (reports.putIfAbsent(r.oop(), r) == null) arrived(r.oop());
                    else stats.duplicateReports++;
                    if (byClass != null) count(r);
                }
            }
            // Cancelled: the traversal lets the running buffer finish and drops it.
            ctx.checkCancelled();
            // While the gem fills the next buffer: go on with what these reports make possible.
            if (options.walkDuringTraversal()) walk(true);
            progress(GsLoadProgress.Phase.DISCOVER, false);
        });
        for (var oop : continued) {
            reports.put(oop, t.get(oop));
            arrived(oop);
        }
        progress(GsLoadProgress.Phase.DISCOVER, false);
        stats.traverse += System.nanoTime() - start;
        ctx.lastLoadReports += t.reports().size();
    }

    /** Server-side asArray / key-value pairs for hashed collections, all in one round trip. */
    private void flatten(Oop[] collections) {
        var array = session.newObject(KnownClasses.ARRAY, collections.length);
        var block = Oop.NIL;
        try {
            session.storeVaryingOops(array, 1, collections);
            block = session.execute(FLATTEN_BLOCK);
            var start = System.nanoTime();
            // Depth capped: the flattened contents plus a little below them. With a large depth the
            // whole subtree would come again (reported twice: once here, once from the first round).
            var t = session.performTraverse(block, "value:", Math.min(options.depth(), 4) + 2, new Oop[] {array}, travOptions);
            stats.traverse += System.nanoTime() - start;
            ctx.lastLoadReports += t.reports().size();
            reportsSoFar += t.reports().size();
            for (var r : t.reports()) {
                if (reports.putIfAbsent(r.oop(), r) == null) arrived(r.oop());
                else stats.duplicateReports++;
                if (byClass != null) count(r);
            }
            var flat = t.get(t.result()).indexedValues();
            for (var i = 0; i < collections.length; i++) flattened.put(collections[i], t.get(new Oop(flat[i])).indexedValues());
        } finally {
            session.release(array, block);
        }
    }

    private GsTypeMap actualMap(Oop oop, GciObjectReport report, GsTypeMap expected, String where) {
        var byExpected = actualMaps.get(oop);
        if (byExpected != null && byExpected.containsKey(expected)) return byExpected.get(expected);
        var map = ctx.selectMap(expected, report.objectClass());
        if (map == null)
            throw GsMappingException.of(expected.polymorphic
                    ? where + ": no class mapped for " + expected.type.getSimpleName() + " matches a " + ctx.className(report.objectClass())
                    + " (" + oop + "); mapped: " + String.join(", ", expected.subtypes.stream().map(s -> s.className).toList()) + "."
                    : where + ": expected a " + expected.className + " for " + expected.type.getSimpleName() + ", got a "
                    + ctx.className(report.objectClass()) + " (" + oop + ").");
        if (map.create == null)
            throw GsMappingException.of(where + ": " + map.type.getSimpleName() + " cannot be instantiated (abstract or no parameterless constructor) for a "
                    + ctx.className(report.objectClass()) + ".");
        if (map != expected) actualMaps.computeIfAbsent(oop, k -> new HashMap<>()).put(expected, map); // the usual case needs no entry
        return map;
    }

    private GsTypeMap mapFor(Oop oop, GsTypeMap expected) {
        var byExpected = actualMaps.get(oop);
        if (byExpected == null) return expected;
        var actual = byExpected.get(expected);
        return actual == null ? expected : actual;
    }

    private static Oop namedValue(GciObjectReport report, GsPropertyMap p, String where) {
        if (p.index > report.namedSize())
            throw GsMappingException.of(where + ": " + report.oop() + " has only " + report.namedSize() + " named instance variables, '"
                    + p.instVar() + "' is #" + p.index + ".");
        return new Oop(report.namedValue(p.index));
    }

    /** Collection contents, or null if they have to be flattened on the server first. */
    private long[] elements(Oop oop, GciObjectReport report, GsShape shape, String where, GsCollectionKind declared) {
        if (report.isPartial()) throw GsMappingException.of(where + ": incomplete report for " + oop + ".");
        var kind = ctx.classify(report.objectClass());
        if (declared != null && !ctx.matchesKind(report.objectClass(), declared))
            throw GsMappingException.of(where + ": declared as " + declared + ", but " + oop + " is a " + ctx.className(report.objectClass()) + ".");
        if (kind == CollectionClass.NONE)
            throw GsMappingException.of(where + ": " + oop + " is a " + ctx.className(report.objectClass()) + ", not a collection.");
        if (shape.kind == ShapeKind.DICTIONARY && kind != CollectionClass.DICTIONARY)
            throw GsMappingException.of(where + ": " + shape + " needs a dictionary, but " + oop + " is a " + ctx.className(report.objectClass()) + ".");
        if (kind == CollectionClass.SEQUENCE || kind == CollectionClass.NSC) return report.indexedValues();
        var flat = flattened.get(oop);
        if (flat == null) return null;
        if (shape.kind != ShapeKind.DICTIONARY && kind == CollectionClass.DICTIONARY) {
            // A dictionary loaded as a list: its values (key/value pairs from flatten).
            return dictionaryValues.computeIfAbsent(oop, k -> {
                var values = new long[flat.length / 2];
                for (var i = 0; i < values.length; i++) values[i] = flat[2 * i + 1];
                return values;
            });
        }
        return flat;
    }

    // ---- Phase 2: build the Java objects ----

    /** What a tracked load keeps in the export set, known after discover: every mapped object and collection reached. */
    private List<Oop> retainCandidates() {
        var all = new ArrayList<Oop>(reached.size() + collectionsReached.size());
        for (var r : reached) if (!(callerOwnsRoot && r.oop().equals(root))) all.add(r.oop());
        for (var o : collectionsReached) if (!(callerOwnsRoot && o.equals(root))) all.add(o);
        return all;
    }

    Object[] build(List<Root> roots) {
        // Before anything changes: refreshing an instance must not silently drop unsaved changes.
        if (!options.discardLocalChanges()) ctx.throwIfUnsavedChanges(reached);
        var start = System.nanoTime();
        root = roots.size() == 1 ? roots.get(0).oop() : Oop.ILLEGAL;
        // Values only the server can convert: one batch, on another thread while the objects are
        // built (objects that need one of them are filled when it is there). Not if the caller holds
        // the GCI lock: waiting for the other thread would deadlock.
        CompletableFuture<Object[]> converting = null;
        if (!toConvert.isEmpty()) {
            var pending = pendingConversion = new ArrayList<>(toConvert);
            if (options.convertInParallel() && !access.holdsLock()) {
                converting = CompletableFuture.supplyAsync(() -> {
                    var values = session.toValues(pending);
                    stats.convert = System.nanoTime() - start;
                    return values;
                }, GsObjectContext.BACKGROUND);
            } else {
                var values = session.toValues(pending);
                for (var i = 0; i < pending.size(); i++) converted.put(pending.get(i), values[i]);
                stats.convert = stats.convertWait = System.nanoTime() - start;
            }
        }
        var buildStart = System.nanoTime();
        // Tracked: GciSaveObjs for everything reached runs on another thread while the objects are
        // built (no GCI call in between; one that happens anyway waits for the lock).
        CompletableFuture<List<Oop>> retaining = null;
        if (options.trackChanges() && options.retainInParallel() && !access.holdsLock()) {
            var candidates = retainCandidates();
            var retainStart = System.nanoTime();
            retaining = CompletableFuture.supplyAsync(() -> {
                var fresh = ctx.retainAll(candidates);
                stats.retain = System.nanoTime() - retainStart;
                return fresh;
            }, GsObjectContext.BACKGROUND);
        }
        var results = new Object[roots.size()];
        progress(GsLoadProgress.Phase.BUILD, true);
        try {
            for (var i = 0; i < roots.size(); i++) {
                if (converting != null && roots.get(i).shape().kind != ShapeKind.MAPPED) converting = finishConverting(converting);
                results[i] = value(roots.get(i).oop(), roots.get(i).shape(), roots.get(i).where(), null);
            }
            var sinceCheck = 0;
            var later = new ArrayList<Job>();
            while (true) {
                while (!fill.isEmpty()) {
                    var job = fill.poll();
                    if (converting != null && needsConverted(job.oop(), job.map())) {
                        later.add(job);
                        continue;
                    }
                    fill(job.instance(), job.oop(), job.map());
                    if (++sinceCheck == 1024) {
                        sinceCheck = 0;
                        progress(GsLoadProgress.Phase.BUILD, false);
                    }
                }
                if (converting == null) break;
                converting = finishConverting(converting);
                fill.addAll(later);
                later.clear();
            }
        } catch (RuntimeException | Error e) {
            if (converting != null) {
                try {
                    converting.join();
                } catch (RuntimeException ignored) { // the build error is what matters
                }
            }
            if (retaining != null) {
                try { // nothing was loaded: give back what was retained for it
                    ctx.releaseRetained(retaining.join());
                } catch (RuntimeException ignored) { // the build error is what matters
                }
            }
            throw e;
        }
        stats.build = System.nanoTime() - buildStart - stats.convertWait;
        if (retaining != null) {
            var waitStart = System.nanoTime();
            join(retaining);
            stats.retainWait = System.nanoTime() - waitStart;
        } else if (options.trackChanges()) {
            var retainStart = System.nanoTime();
            // Root passed in by the caller: the caller keeps it alive (and may release it).
            var keep = toRetain.stream().filter(o -> !(callerOwnsRoot && o.equals(root))).toList();
            ctx.retainAll(keep);
            stats.retain = stats.retainWait = System.nanoTime() - retainStart;
        }
        stats.objects = filled.size();
        stats.reports = ctx.lastLoadReports;
        progress(GsLoadProgress.Phase.DONE, true);
        if (byClass != null) {
            var byName = new HashMap<String, Long>();
            byClass.forEach((cls, n) -> byName.merge(ctx.className(cls), n, Long::sum));
            stats.reportsByClass = byName;
        }
        ctx.lastLoad = stats;
        return results;
    }

    private List<Oop> pendingConversion = List.of();

    /** Waits for the conversion batch (counted as convert wait) and takes its values, here on the building thread. */
    private CompletableFuture<Object[]> finishConverting(CompletableFuture<Object[]> converting) {
        var waitStart = System.nanoTime();
        var values = join(converting);
        stats.convertWait += System.nanoTime() - waitStart;
        for (var i = 0; i < pendingConversion.size(); i++) converted.put(pendingConversion.get(i), values[i]);
        return null;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Whether filling this object needs a value of the conversion batch: a scalar (or converted)
     * field, or an element of a collection of scalars. Collections of collections count as needing
     * one (not looked into).
     */
    private boolean needsConverted(Oop oop, GsTypeMap map) {
        var report = reports.get(oop);
        for (var p : map.properties) {
            if (p.index > report.namedSize()) return true; // fill reports the error
            var iv = new Oop(report.namedValue(p.index));
            if (p.converter() != null || p.shape.kind == ShapeKind.SCALAR || p.shape.kind == ShapeKind.OBJECT) {
                if (toConvert.contains(iv)) return true;
                continue;
            }
            if (!p.shape.isCollection() || iv.isSpecial()) continue;
            var dict = p.shape.kind == ShapeKind.DICTIONARY;
            if (dict ? needsLookAt(p.shape.key) || needsLookAt(p.shape.value) : needsLookAt(p.shape.element)) {
                var elements = collectionElements(iv);
                if (elements == null) return true;
                for (var e : elements) if (toConvert.contains(new Oop(e))) return true;
            }
            if (dict ? p.shape.key.isCollection() || p.shape.value.isCollection() : p.shape.element.isCollection()) return true;
        }
        return false;
    }

    private static boolean needsLookAt(GsShape element) {
        return element.kind == ShapeKind.SCALAR || element.kind == ShapeKind.OBJECT;
    }

    /** The element OOPs discover saw for a collection (key/value pairs for dictionaries), or null. */
    private long[] collectionElements(Oop oop) {
        var flat = flattened.get(oop);
        if (flat != null) return flat;
        var r = reports.get(oop);
        return r != null && !r.isPartial() ? r.indexedValues() : null;
    }

    private void fill(Object instance, Oop oop, GsTypeMap map) {
        var report = reports.get(oop);
        if (map.setOop != null) map.setOop.accept(instance, oop);
        EntityEntry entry = null;
        if (options.trackChanges()) {
            entry = new EntityEntry(instance, oop, map);
            ctx.entries.put(instance, entry); // reload: replaces the old snapshot
            toRetain.add(oop);
        }
        for (var p : map.properties) {
            var ivOop = namedValue(report, p, p.path);
            Object value;
            CollectionSnapshot coll = null;
            if (entry != null && p.converter() == null && p.shape.isCollection() && !ivOop.isSpecial()) {
                var elements = new ArrayList<SnapElement>();
                value = collection(ivOop, p.shape, p.path, p.collectionKind(), elements);
                elements.trimToSize(); // kept for the life of the context
                coll = new CollectionSnapshot(ivOop, elements,
                        p.shape.kind != ShapeKind.DICTIONARY && ctx.classify(reports.get(ivOop).objectClass()) == CollectionClass.DICTIONARY);
                toRetain.add(ivOop);
                ctx.rememberCollectionOwner(ivOop, instance);
            } else if (ivOop.isNil() && ctx.emptyCollectionsAsNil() && p.converter() == null && p.shape.isCollection()) {
                value = p.shape.newCollection.apply(0); // nil stands for an empty collection
            } else {
                value = value(ivOop, p.shape, p.path, p);
                if (entry == null && p.shape.isCollection() && !ivOop.isSpecial()) ctx.rememberCollectionOwner(ivOop, instance);
            }
            if (value == null && p.nilForbidden)
                throw GsMappingException.of(p.path + ": nil cannot be assigned to " + Types.raw(p.metadata.type).getSimpleName() + " (" + oop + ").");
            p.set(instance, value);
            if (entry != null) {
                // lowerFirst only matters for enums (written back as Symbols in the original case) and Symbols.
                var isSymbol = isSymbol(ivOop);
                var lowerFirst = !(isSymbol || value instanceof Enum<?>)
                        || !(GciTraversal.decode(ivOop, reports) instanceof String text && !text.isEmpty())
                        || Character.isLowerCase(((String) GciTraversal.decode(ivOop, reports)).charAt(0));
                entry.setSnapshot(p, new PropertySnapshot(ivOop, value, coll, isSymbol, lowerFirst));
            }
        }
    }

    private boolean isSymbol(Oop oop) {
        var r = reports.get(oop);
        return r != null && (r.objectClass().equals(KnownClasses.SYMBOL) || r.objectClass().equals(KnownClasses.DOUBLE_BYTE_SYMBOL)
                || r.objectClass().equals(KnownClasses.QUAD_BYTE_SYMBOL));
    }

    private Object value(Oop oop, GsShape shape, String where, GsPropertyMap property) {
        if (property != null && property.converter() != null)
            return property.converter().fromGemStone(decode(oop, true), property.metadata.type);
        return switch (shape.kind) {
            case OOP -> oop;
            case OBJECT -> decode(oop, false);
            case SCALAR -> coerce(decode(oop, true), shape, where);
            case MAPPED -> {
                if (oop.isNil()) yield null;
                if (oop.isSpecial())
                    throw GsMappingException.of(where + ": expected a " + shape.map.className + ", got the special object " + session.printString(oop) + ".");
                yield instance(oop, mapFor(oop, shape.map));
            }
            default -> {
                if (oop.isNil()) yield null;
                if (oop.isSpecial()) throw GsMappingException.of(where + ": expected a collection, got " + session.printString(oop) + ".");
                yield collection(oop, shape, where, property == null ? null : property.collectionKind(), null);
            }
        };
    }

    /** Roots of the load (filled even if known), with {@link GsLoadOptions#stopAtLoadedObjects}. */
    private final Set<Oop> rootOops = new HashSet<>();

    /** With {@link GsLoadOptions#stopAtLoadedObjects}: a mapped object the context holds and that is not a root. */
    private boolean isLoadedElsewhere(Oop oop, GsShape shape) {
        return options.stopAtLoadedObjects() && shape.kind == ShapeKind.MAPPED && !oop.isSpecial()
                && !rootOops.contains(oop) && ctx.find(oop, shape.map.type) != null;
    }

    private Object instance(Oop oop, GsTypeMap map) {
        var existing = ctx.find(oop, map.type);
        if (existing != null) {
            if (options.stopAtLoadedObjects() && !rootOops.contains(oop)) return existing; // linked, not reloaded
            if (filled.add(existing)) fill.add(new Job(existing, oop, map)); // loaded again: refresh
            return existing;
        }
        var instance = map.create.get();
        ctx.remember(oop, instance);
        if (!options.trackChanges()) ctx.oops.put(instance, oop); // tracked: the entry has it
        filled.add(instance);
        fill.add(new Job(instance, oop, map));
        return instance;
    }

    @SuppressWarnings("unchecked")
    private Object collection(Oop oop, GsShape shape, String where, GsCollectionKind declared, List<SnapElement> snapshot) {
        var elements = elements(oop, reports.get(oop), shape, where, declared);
        switch (shape.kind) {
            case ARRAY -> {
                var array = shape.newCollection.apply(elements.length);
                for (var i = 0; i < elements.length; i++) Array.set(array, i, element(new Oop(elements[i]), shape.element, where, snapshot));
                return array;
            }
            case LIST, SET -> {
                var c = (Collection<Object>) shape.newCollection.apply(elements.length);
                for (var e : elements) c.add(element(new Oop(e), shape.element, where, snapshot));
                return c;
            }
            default -> {
                var dict = (Map<Object, Object>) shape.newCollection.apply(elements.length / 2);
                for (var i = 0; i + 1 < elements.length; i += 2) {
                    var key = element(new Oop(elements[i]), shape.key, "key of " + where, snapshot);
                    if (key == null) throw GsMappingException.of("key of " + where + ": nil key.");
                    if (dict.containsKey(key))
                        throw GsMappingException.of("key of " + where + ": '" + key + "' occurs twice after conversion (e.g. a String and a Symbol with the same text).");
                    dict.put(key, element(new Oop(elements[i + 1]), shape.value, "value of " + where, snapshot));
                }
                return dict;
            }
        }
    }

    private Object element(Oop e, GsShape s, String where, List<SnapElement> snapshot) {
        if (snapshot != null && s.isCollection() && !e.isSpecial()) {
            // A collection inside a collection: its own snapshot, so that saving can patch it in place.
            var inner = new ArrayList<SnapElement>();
            var c = collection(e, s, where, null, inner);
            inner.trimToSize();
            snapshot.add(new SnapElement(e, c, false, new CollectionSnapshot(e, inner)));
            toRetain.add(e);
            return c;
        }
        var v = value(e, s, where, null);
        if (v == null && s.primitive) throw GsMappingException.of(where + ": nil cannot be stored as " + s + ".");
        if (snapshot != null) snapshot.add(new SnapElement(e, v, isSymbol(e)));
        return v;
    }

    /**
     * Offline decoding; values the traversal cannot decode (LargeInteger, ScaledDecimal, date/time
     * …) were converted in one batch before building.
     */
    private Object decode(Oop oop, boolean forScalar) {
        var value = GciTraversal.decode(oop, reports);
        if (!(value instanceof Oop)) return value;
        if (converted.containsKey(oop)) return converted.get(oop);
        return forScalar || oop.isSpecial() ? session.toValue(oop) : oop; // not collected during discovery
    }

    private Object coerce(Object value, GsShape shape, String where) {
        var target = shape.scalarClass;
        if (value == null) {
            if (shape.primitive) throw GsMappingException.of(where + ": nil cannot be assigned to " + shape + ".");
            return null;
        }
        if (target.isInstance(value)) return value;
        try {
            if (target.isEnum()) {
                if (value instanceof String s) return enumConstant(target, s, where);
                if (value instanceof Long l) return target.getEnumConstants()[Math.toIntExact(l)];
                throw mismatch(value, target, where);
            }
            if (value instanceof Long l) {
                if (target == Integer.class) return Math.toIntExact(l);
                if (target == Short.class) return shortExact(l);
                if (target == Byte.class) return byteExact(l);
                if (target == BigInteger.class) return BigInteger.valueOf(l);
                if (target == BigDecimal.class) return BigDecimal.valueOf(l);
                if (target == Double.class) return (double) l;
                if (target == Float.class) return (float) l;
            }
            if (value instanceof BigInteger b) {
                if (target == BigDecimal.class) return new BigDecimal(b);
                if (target == Double.class) return b.doubleValue();
                if (target == Long.class) return b.longValueExact();
            }
            if (value instanceof Double d) {
                if (target == Float.class) return d.floatValue();
                if (target == BigDecimal.class) return BigDecimal.valueOf(d);
            }
            if (value instanceof BigDecimal m && target == Double.class) return m.doubleValue();
            if (value instanceof CodePoint c) {
                if (target == Character.class && Character.isBmpCodePoint(c.value())) return (char) c.value();
                if (target == String.class) return c.toString();
            }
            if (value instanceof String s && target == Character.class && s.length() == 1) return s.charAt(0);
            if (value instanceof OffsetDateTime dt) {
                // Local time: an application's LocalDateTime values are local (UI, input).
                if (target == LocalDateTime.class) return dt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
                if (target == Instant.class) return dt.toInstant();
                if (target == ZonedDateTime.class) return dt.toZonedDateTime();
            }
        } catch (ArithmeticException | IndexOutOfBoundsException e) {
            throw GsMappingException.of(where + ": " + describe(value) + " cannot be converted to " + target.getSimpleName() + " (" + e.getMessage() + ").");
        }
        throw mismatch(value, target, where);
    }

    private static short shortExact(long l) {
        if (l < Short.MIN_VALUE || l > Short.MAX_VALUE) throw new ArithmeticException("short overflow");
        return (short) l;
    }

    private static byte byteExact(long l) {
        if (l < Byte.MIN_VALUE || l > Byte.MAX_VALUE) throw new ArithmeticException("byte overflow");
        return (byte) l;
    }

    /** An enum constant by name: exact, then ignoring case and underscores (#inProgress → IN_PROGRESS). */
    static Object enumConstant(Class<?> type, String name, String where) {
        for (var c : type.getEnumConstants()) if (((Enum<?>) c).name().equals(name)) return c;
        var normalized = normalize(name);
        for (var c : type.getEnumConstants()) if (normalize(((Enum<?>) c).name()).equals(normalized)) return c;
        throw GsMappingException.of(where + ": '" + name + "' is no constant of " + type.getSimpleName() + ".");
    }

    private static String normalize(String s) {
        return s.replace("_", "").toLowerCase(java.util.Locale.ROOT);
    }

    private GsMappingException mismatch(Object value, Class<?> target, String where) {
        return GsMappingException.of(where + ": " + describe(value) + " cannot be assigned to " + target.getSimpleName() + ".");
    }

    private String describe(Object value) {
        if (value instanceof Oop o) {
            var r = reports.get(o);
            return "a " + (r != null ? ctx.className(r.objectClass()) : o.toString());
        }
        return value.getClass().getSimpleName() + " " + value;
    }
}
