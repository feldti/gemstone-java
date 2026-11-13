package net.gs64.gci.mapping;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.gs64.gci.CodePoint;
import net.gs64.gci.GciException;
import net.gs64.gci.GciNewObject;
import net.gs64.gci.GciSession;
import net.gs64.gci.KnownClasses;
import net.gs64.gci.Oop;

/**
 * Plans one saveChanges/add: compares, collects the operations and new objects, then sends
 * everything at once ({@link GciSession#storeAndExecute}). Snapshots and the identity map change
 * only after the batch succeeded.
 */
final class SaveRun {
    private static final long SMALLTALK_DATE_BASE = LocalDate.of(1901, 1, 1).toEpochDay();

    /**
     * Executed with the ops Array as {@code self}. Ops:
     * <ul>
     * <li>{@code 1 class}: create with {@code new};</li>
     * <li>{@code 2 target index value}: store a named instVar;</li>
     * <li>{@code 3 target items}: fill a new collection (dictionaries: key/value pairs);</li>
     * <li>{@code 4 target from removed inserted}: change a collection ({@code from} &gt; 0 for
     * ordered collections: remove at {@code from} and insert there; otherwise {@code remove:} and
     * {@code addAll:}; Arrays through an OrderedCollection copy; an RcQueue is only appended to);</li>
     * <li>{@code 5 target removedKeys puts}: change a dictionary;</li>
     * <li>{@code 6 feed objects}: {@code feed publish: objects};</li>
     * <li>{@code 7 target selector removed inserted}: change a dictionary held as a list ({@link GsDictionaryKey});</li>
     * <li>{@code 9 target index class selector items where}: a collection-valued instVar of a new
     * object (or one that was nil): takes the collection {@code initialize} put there or stores
     * {@code class new}, fills it and counts as a created object.</li>
     * </ul>
     * A value is two slots, {@code kind payload}: 0 = the payload itself, 1 = the n-th created
     * object, 2–8 = converted on the server (Symbol, Unicode string from Utf8, LargeInteger,
     * ScaledDecimal, Date, Time, DateAndTime). Answers the created objects followed by the
     * converted values, in the order in which they were evaluated.
     */
    static final String BATCH_SOURCE =
            "| created converted val decode patch i n op c |\n"
                    + "created := OrderedCollection new. converted := OrderedCollection new.\n"
                    + "val := [:k :p | | r | k == 0 ifTrue: [p] ifFalse: [k == 1 ifTrue: [created at: p] ifFalse: [\n"
                    + " r := k == 2 ifTrue: [((p isKindOf: Utf8) ifTrue: [p decodeToUnicode] ifFalse: [p]) asSymbol] ifFalse: [\n"
                    + " k == 3 ifTrue: [p decodeToUnicode] ifFalse: [\n"
                    + " k == 4 ifTrue: [Integer fromString: p] ifFalse: [\n"
                    + " k == 5 ifTrue: [((Integer fromString: (p at: 1)) / (10 raisedTo: (p at: 2))) asScaledDecimal: (p at: 2)] ifFalse: [\n"
                    + " k == 6 ifTrue: [Date fromDays: p] ifFalse: [\n"
                    + " k == 7 ifTrue: [Time fromMicroseconds: p] ifFalse: [\n"
                    + " k == 8 ifTrue: [DateAndTime posixSeconds: (p at: 1) / 1000000 offset: (Duration seconds: (p at: 2))] ifFalse: [\n"
                    + " self error: 'unknown value kind']]]]]]].\n"
                    + " converted add: r. r]]].\n"
                    + "decode := [:src | | a | a := Array new: src size // 2.\n"
                    + " 1 to: a size do: [:j | a at: j put: (val value: (src at: j * 2 - 1) value: (src at: j * 2))]. a].\n"
                    + "patch := [:coll :from :rem :ins | (coll isKindOf: RcQueue)\n"
                    + " ifTrue: [rem isEmpty ifFalse: [self error: 'an RcQueue can only be appended to from Java; take elements out in Smalltalk'].\n"
                    + "  ins do: [:x | coll add: x]] ifFalse: [(coll isKindOf: Array)\n"
                    + " ifTrue: [ | r | r := OrderedCollection withAll: coll. patch value: r value: from value: rem value: ins.\n"
                    + "  coll size: r size. coll replaceFrom: 1 to: r size with: r asArray startingAt: 1]\n"
                    + " ifFalse: [((from > 0) and: [(coll isKindOf: OrderedCollection) and: [(coll isKindOf: SortedCollection) not]])\n"
                    + "  ifTrue: [rem size timesRepeat: [coll removeIndex: from].\n"
                    + "   from > coll size ifTrue: [coll addAll: ins] ifFalse: [ins reverseDo: [:x | coll add: x beforeIndex: from]]]\n"
                    + "  ifFalse: [rem do: [:x | coll remove: x ifAbsent: [nil]]. coll addAll: ins]]]].\n"
                    + "i := 1. n := self size.\n"
                    + "[i <= n] whileTrue: [\n"
                    + " op := self at: i.\n"
                    + " op == 1 ifTrue: [created add: (self at: i + 1) new. i := i + 2] ifFalse: [\n"
                    + " op == 2 ifTrue: [(val value: (self at: i + 1) value: (self at: i + 2)) instVarAt: (self at: i + 3) put: (val value: (self at: i + 4) value: (self at: i + 5)). i := i + 6] ifFalse: [\n"
                    + " op == 4 ifTrue: [ | from rem |\n"
                    + "  c := val value: (self at: i + 1) value: (self at: i + 2). from := self at: i + 3.\n"
                    + "  rem := decode value: (self at: i + 4).\n"
                    + "  patch value: c value: from value: rem value: (decode value: (self at: i + 5)). i := i + 6] ifFalse: [\n"
                    + " op == 5 ifTrue: [ | keys puts |\n"
                    + "  c := val value: (self at: i + 1) value: (self at: i + 2).\n"
                    + "  keys := decode value: (self at: i + 3). puts := decode value: (self at: i + 4).\n"
                    + "  keys do: [:k | c removeKey: k ifAbsent: [nil]].\n"
                    + "  1 to: puts size by: 2 do: [:j | c at: (puts at: j) put: (puts at: j + 1)]. i := i + 5] ifFalse: [\n"
                    + " op == 3 ifTrue: [ | src new |\n"
                    + "  c := val value: (self at: i + 1) value: (self at: i + 2). src := self at: i + 3.\n"
                    + "  new := Array new: src size // 2.\n"
                    + "  1 to: new size do: [:j | new at: j put: (val value: (src at: j * 2 - 1) value: (src at: j * 2))].\n"
                    + "  (c isKindOf: AbstractDictionary)\n"
                    + "   ifTrue: [c keys do: [:k | c removeKey: k]. 1 to: new size by: 2 do: [:j | c at: (new at: j) put: (new at: j + 1)]]\n"
                    + "   ifFalse: [(c isKindOf: Array)\n"
                    + "    ifTrue: [c size: new size. c replaceFrom: 1 to: new size with: new startingAt: 1]\n"
                    + "    ifFalse: [c removeAll: c copy. c addAll: new]].\n"
                    + "  i := i + 4] ifFalse: [\n"
                    + " op == 6 ifTrue: [(self at: i + 1) publish: (decode value: (self at: i + 2)). i := i + 3] ifFalse: [\n"
                    + " op == 7 ifTrue: [ | sel rem ins |\n"
                    + "  c := val value: (self at: i + 1) value: (self at: i + 2). sel := (self at: i + 3) asSymbol.\n"
                    + "  rem := decode value: (self at: i + 4). ins := decode value: (self at: i + 5).\n"
                    + "  (c isKindOf: AbstractDictionary)\n"
                    + "   ifTrue: [rem do: [:x | | key | key := nil. c keysAndValuesDo: [:k :v | v == x ifTrue: [key := k]].\n"
                    + "     key notNil ifTrue: [c removeKey: key]].\n"
                    + "    ins do: [:x | c at: (x perform: sel) put: x]]\n"
                    + "   ifFalse: [patch value: c value: 0 value: rem value: ins].\n"
                    + "  i := i + 6] ifFalse: [\n"
                    + " op == 9 ifTrue: [ | t idx sel items |\n"
                    + "  t := val value: (self at: i + 1) value: (self at: i + 2). idx := self at: i + 3.\n"
                    + "  c := t instVarAt: idx.\n"
                    + "  (c isKindOf: Collection) ifFalse: [c := (self at: i + 4) new. t instVarAt: idx put: c].\n"
                    + "  created add: c.\n"
                    + "  sel := self at: i + 5. items := decode value: (self at: i + 6).\n"
                    + "  (c isKindOf: AbstractDictionary)\n"
                    + "   ifTrue: [sel isNil ifTrue: [self error: (self at: i + 7), ': the new object''s collection is a dictionary; add @GsDictionaryKey in Java'].\n"
                    + "    sel := sel asSymbol. items do: [:x | c at: (x perform: sel) put: x]]\n"
                    + "   ifFalse: [(c isKindOf: Array)\n"
                    + "    ifTrue: [c size: items size. c replaceFrom: 1 to: items size with: items startingAt: 1]\n"
                    + "    ifFalse: [items do: [:x | c add: x]]].\n"
                    + "  i := i + 8] ifFalse: [\n"
                    + " self error: 'unknown op']]]]]]]]].\n"
                    + "created asArray , converted asArray";

    /** An object the store traversal creates (its OOP is allocated when the batch is sent). */
    static final class NewObjectSpec {
        final Oop cls;
        final byte[] bytes;
        /** Slots: an Oop or another NewObjectSpec. */
        final List<Object> slots;
        final Double floatValue;
        Oop oop;

        NewObjectSpec(Oop cls, byte[] bytes, List<Object> slots, Double floatValue) {
            this.cls = cls;
            this.bytes = bytes;
            this.slots = slots;
            this.floatValue = floatValue;
        }
    }

    /**
     * A value in the batch. Its OOP is known up front (existing objects, immediates, objects the
     * store traversal creates) or only after the batch ran (created and converted objects).
     */
    static final class BatchValue {
        int kind;
        /** The OOP (kind 0 without spec), or the payload of a conversion. */
        Oop payload;
        /** A new object in the store traversal: the value itself (kind 0) or the payload. */
        NewObjectSpec spec;
        /** Kind 1: 1-based index into the created objects. */
        int created;
        Oop resolved;

        static BatchValue of(Oop oop) {
            var v = new BatchValue();
            v.payload = oop;
            v.resolved = oop;
            return v;
        }

        /** The slot of the payload: its new object, or its OOP. */
        Object slot() {
            return spec != null ? spec : payload;
        }
    }

    private sealed interface BatchOp permits CreateOp, StoreOp, ReplaceOp, PatchOp, DictionaryPatchOp, PublishOp, KeyedPatchOp, FillNewOp {
    }

    private record CreateOp(Oop cls) implements BatchOp {
    }

    private record StoreOp(BatchValue target, int index, BatchValue value) implements BatchOp {
    }

    private record ReplaceOp(BatchValue target, List<BatchValue> items) implements BatchOp {
    }

    private record PatchOp(BatchValue target, int from, List<BatchValue> removed, List<BatchValue> inserted) implements BatchOp {
    }

    private record DictionaryPatchOp(BatchValue target, List<BatchValue> removedKeys, List<BatchValue> puts) implements BatchOp {
    }

    private record PublishOp(Oop feed, List<BatchValue> objects) implements BatchOp {
    }

    private record KeyedPatchOp(BatchValue target, String selector, List<BatchValue> removed, List<BatchValue> inserted) implements BatchOp {
    }

    private record FillNewOp(BatchValue target, int index, Oop cls, String selector, List<BatchValue> items, String where) implements BatchOp {
    }

    /** An object whose fields are compared and written: tracked, or new in this save. */
    private record SaveTarget(Object entity, GsTypeMap map, BatchValue oop, EntityEntry entry) {
        PropertySnapshot snapshot(GsPropertyMap p) {
            return entry == null ? null : entry.snapshot(p);
        }
    }

    /** The snapshot of a written scalar field, applied after the batch succeeded (after the entries of new objects exist). */
    private record PendingSnapshot(SaveTarget target, GsPropertyMap property, BatchValue value, Object current, boolean isSymbol, boolean lowerFirst) {
    }

    /** A planned collection element: its value in the batch and how to snapshot it afterwards. */
    private record Planned(BatchValue value, boolean isSymbol, Supplier<CollectionSnapshot> nested) {
        SnapElement snap(Object item) {
            return new SnapElement(value.resolved, item, isSymbol, nested == null ? null : nested.get());
        }
    }

    private final GsObjectContext ctx;
    private final GciSession session;
    private final List<BatchOp> ops = new ArrayList<>();
    private final List<BatchValue> created = new ArrayList<>();
    private final Map<Object, SaveTarget> inserted = new IdentityHashMap<>();
    private final List<Runnable> onSuccess = new ArrayList<>();
    private final List<Runnable> onResolve = new ArrayList<>();
    private final List<PendingSnapshot> scalarSnapshots = new ArrayList<>();
    private final Set<CollectionSnapshot> patched = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Tracked objects this save writes to, for the change feed. */
    final List<Oop> changed = new ArrayList<>();
    /** The change feed object to publish to, or null. */
    Oop feed;
    int stores;

    SaveRun(GsObjectContext ctx) {
        this.ctx = ctx;
        this.session = ctx.session;
        this.feed = ctx.feedObject;
    }

    /** Whether {@link #execute} would send a batch. */
    boolean hasWork() {
        return !ops.isEmpty();
    }

    void update(EntityEntry entry) {
        update(new SaveTarget(entry.entity, entry.map, BatchValue.of(entry.oop), entry), null);
    }

    /** Plans a new object (once per entity) and returns its value. */
    BatchValue insert(Object entity) {
        var known = inserted.get(entity);
        if (known != null) return known.oop();
        var shape = ctx.prepare(entity.getClass());
        if (shape.kind != ShapeKind.MAPPED)
            throw GsMappingException.of(entity.getClass().getSimpleName() + " has no @GsClass and cannot be saved as a new object.");
        var map = shape.map;
        var oop = create(map.classOop);
        var target = new SaveTarget(entity, map, oop, null);
        inserted.put(entity, target); // before update: cycles back to this object find it
        var entry = new EntityEntry[1];
        onSuccess.add(() -> {
            var resolved = oop.resolved;
            ctx.remember(resolved, entity);
            ctx.entries.put(entity, entry[0] = new EntityEntry(entity, resolved, map));
            if (map.setOop != null) map.setOop.accept(entity, resolved);
        });
        update(target, () -> entry[0]);
        return oop;
    }

    private BatchValue create(Oop cls) {
        ops.add(new CreateOp(cls));
        var value = new BatchValue();
        value.kind = 1;
        value.created = created.size() + 1;
        created.add(value);
        return value;
    }

    private interface Remember {
        void snapshot(GsPropertyMap p, Supplier<PropertySnapshot> snapshot);
    }

    private void update(SaveTarget target, Supplier<EntityEntry> newEntry) {
        var entry = target.entry();
        var opsBefore = ops.size();
        Remember remember = (p, snapshot) -> onSuccess.add(() -> {
            // New objects are temporary until the commit and keep their state on an abort: only
            // snapshots of existing objects are journaled.
            if (entry != null) ctx.journalProperty(entry, p);
            (entry != null ? entry : newEntry.get()).setSnapshot(p, snapshot.get());
        });
        for (var p : target.map().properties) {
            if (p.readOnly()) continue;
            var current = p.get(target.entity());
            var snap = target.snapshot(p);
            if (p.converter() == null && p.shape.isCollection()) {
                updateCollection(target, p, current, snap, remember);
                continue;
            }
            if (snap != null && snap.matches(current)) continue;
            var value = toValue(current, p.shape, p, snap, p.path);
            store(target, p, value);
            var isSymbol = snap != null ? snap.isSymbol : current instanceof Enum<?>;
            var lowerFirst = snap == null || snap.lowerFirst;
            scalarSnapshots.add(new PendingSnapshot(target, p, value, current, isSymbol, lowerFirst));
        }
        // Ops of new objects planned meanwhile count too: they hang on a changed field of this one.
        if (entry != null && ops.size() > opsBefore) {
            changed.add(entry.oop);
            onSuccess.add(() -> {
                ctx.savedSinceCommit.add(entry.entity);
                ctx.listenForTransactionEnd();
            });
        }
    }

    private void store(SaveTarget target, GsPropertyMap p, BatchValue value) {
        ops.add(new StoreOp(target.oop(), p.index, value));
        stores++;
    }

    private void updateCollection(SaveTarget target, GsPropertyMap p, Object current, PropertySnapshot snap, Remember remember) {
        var old = snap == null ? null : snap.collection;
        if (current == null) {
            if (snap == null || !snap.oop.isNil()) store(target, p, BatchValue.of(Oop.NIL));
            remember.snapshot(p, () -> new PropertySnapshot(Oop.NIL));
            return;
        }
        if (old != null) {
            patchContents(old, current, p.shape, p.path, p.keySelector());
            remember.snapshot(p, () -> new PropertySnapshot(old.oop, current, old, false, true));
            return;
        }
        if (p.shape.kind != ShapeKind.DICTIONARY) {
            // Was nil (or a new object): fill the collection the server's initialize created (its
            // class follows the Smalltalk model: RcIdentityBag, a dictionary …), or create one.
            var items = items(current);
            var planned = new ArrayList<Planned>(items.size());
            for (var item : items) planned.add(element(item, p.shape.element, List.of(), p.path));
            var cls = p.keySelector() == null ? collectionClassName(p.collectionKind(), p.shape) : "KeyValueDictionary";
            var filled = new BatchValue();
            filled.kind = 1;
            filled.created = created.size() + 1;
            ops.add(new FillNewOp(target.oop(), p.index, ctx.kernel(cls), p.keySelector(), planned.stream().map(Planned::value).toList(), p.path));
            created.add(filled);
            stores++;
            remember.snapshot(p, () -> {
                var elements = new ArrayList<SnapElement>(items.size());
                for (var i = 0; i < items.size(); i++) elements.add(planned.get(i).snap(items.get(i)));
                return new PropertySnapshot(filled.resolved, current, new CollectionSnapshot(filled.resolved, elements, p.keySelector() != null), false, true);
            });
            return;
        }
        // A dictionary that was nil (or a new object): create the GemStone collection.
        var coll = create(ctx.kernel(collectionClassName(p.collectionKind(), p.shape)));
        var made = replaceContents(coll, p.shape, items(current), List.of(), p.path);
        store(target, p, coll);
        remember.snapshot(p, () -> new PropertySnapshot(coll.resolved, current, new CollectionSnapshot(coll.resolved, made.get()), false, true));
    }

    /**
     * Changes a loaded collection in place if its contents differ from the snapshot, then does
     * the same for the collections inside it (they are compared by reference one level up).
     */
    private void patchContents(CollectionSnapshot old, Object current, GsShape shape, String where, String keySelector) {
        if (!patched.add(old)) return; // the same collection reachable twice
        var items = items(current);
        var before = old.elements;
        if (!contentsEqual(items, before, shape.kind)) {
            var collOop = BatchValue.of(old.oop);
            Supplier<List<SnapElement>> elements;
            if (old.keyedValues) elements = patchKeyed(collOop, shape, items, before, where, keySelector);
            else if (shape.kind == ShapeKind.DICTIONARY) elements = patchDictionary(collOop, shape, items, before, where);
            else if (shape.kind == ShapeKind.SET) elements = patchUnordered(collOop, shape, items, before, where);
            else elements = patchOrdered(collOop, shape, items, before, where);
            stores++;
            onSuccess.add(() -> {
                ctx.journalElements(old);
                old.elements = elements.get();
            });
        }
        var isDict = shape.kind == ShapeKind.DICTIONARY;
        for (var i = 0; i < items.size(); i++) {
            var elementShape = isDict ? (i % 2 == 0 ? shape.key : shape.value) : shape.element;
            var inner = items.get(i);
            if (!elementShape.isCollection() || inner == null) continue;
            for (var e : before)
                if (e.nested() != null && e.value() == inner) {
                    patchContents(e.nested(), inner, elementShape, "element of " + where, null);
                    break;
                }
        }
    }

    private static String collectionClassName(GsCollectionKind kind, GsShape shape) {
        if (kind != null)
            return switch (kind) {
                case ARRAY -> "Array";
                case ORDERED -> "OrderedCollection";
                case SET -> "Set";
                case IDENTITY_SET -> "IdentitySet";
                case BAG -> "Bag";
                case IDENTITY_BAG -> "IdentityBag";
                case DICTIONARY -> "KeyValueDictionary";
            };
        return switch (shape.kind) {
            case ARRAY -> "Array";
            case SET -> "Set";
            case DICTIONARY -> "KeyValueDictionary";
            default -> "OrderedCollection";
        };
    }

    /** Plans rewriting a collection's contents (keeps its identity); returns the new snapshot elements, resolved after the batch. */
    private Supplier<List<SnapElement>> replaceContents(BatchValue coll, GsShape shape, List<Object> items, List<SnapElement> old, String where) {
        var isDict = shape.kind == ShapeKind.DICTIONARY;
        var planned = new ArrayList<Planned>(items.size());
        for (var i = 0; i < items.size(); i++) {
            var elementShape = isDict ? (i % 2 == 0 ? shape.key : shape.value) : shape.element;
            List<SnapElement> sameRole = old;
            if (isDict) {
                var role = new ArrayList<SnapElement>();
                for (var j = i % 2; j < old.size(); j += 2) role.add(old.get(j));
                sameRole = role;
            }
            planned.add(element(items.get(i), elementShape, sameRole, where));
        }
        ops.add(new ReplaceOp(coll, planned.stream().map(Planned::value).toList()));
        return () -> {
            var result = new ArrayList<SnapElement>(items.size());
            for (var i = 0; i < items.size(); i++) result.add(planned.get(i).snap(items.get(i)));
            return result;
        };
    }

    /** Ordered: the common head and tail stay, the middle is replaced (one op). */
    private Supplier<List<SnapElement>> patchOrdered(BatchValue coll, GsShape shape, List<Object> items, List<SnapElement> old, String where) {
        var min = Math.min(items.size(), old.size());
        var head = 0;
        while (head < min && GsObjectContext.valueEquals(items.get(head), old.get(head).value())) head++;
        var tail = 0;
        while (tail < min - head && GsObjectContext.valueEquals(items.get(items.size() - 1 - tail), old.get(old.size() - 1 - tail).value())) tail++;
        var removed = old.subList(head, old.size() - tail).stream().map(e -> BatchValue.of(e.oop())).toList();
        var middle = new ArrayList<>(items.subList(head, items.size() - tail));
        var planned = new ArrayList<Planned>(middle.size());
        for (var item : middle) planned.add(element(item, shape.element, old, where));
        ops.add(new PatchOp(coll, head + 1, removed, planned.stream().map(Planned::value).toList()));
        var h = head;
        var t = tail;
        return () -> {
            var result = new ArrayList<SnapElement>(old.subList(0, h));
            for (var i = 0; i < middle.size(); i++) result.add(planned.get(i).snap(middle.get(i)));
            result.addAll(old.subList(old.size() - t, old.size()));
            return result;
        };
    }

    /** Unordered (Set, Bag …): elements that are gone are removed, new ones added. */
    private Supplier<List<SnapElement>> patchUnordered(BatchValue coll, GsShape shape, List<Object> items, List<SnapElement> old, String where) {
        var unmatched = new ArrayList<>(old);
        var snapshot = new ArrayList<Supplier<SnapElement>>(items.size());
        var inserted = new ArrayList<BatchValue>();
        for (var item : items) {
            var i = indexOf(unmatched, item);
            if (i >= 0) {
                var kept = unmatched.remove(i);
                snapshot.add(() -> kept);
                continue;
            }
            var planned = element(item, shape.element, old, where);
            inserted.add(planned.value());
            snapshot.add(() -> planned.snap(item));
        }
        ops.add(new PatchOp(coll, 0, unmatched.stream().map(e -> BatchValue.of(e.oop())).toList(), inserted));
        return () -> snapshot.stream().map(Supplier::get).toList();
    }

    /** A list whose GemStone collection is a dictionary: removed values lose their key, new ones go under {@code value perform: keySelector}. */
    private Supplier<List<SnapElement>> patchKeyed(BatchValue coll, GsShape shape, List<Object> items, List<SnapElement> old, String where, String keySelector) {
        if (keySelector == null)
            throw GsMappingException.of(where + ": on the server this is a dictionary; add @GsDictionaryKey(\"selector\") to change it from Java.");
        var unmatched = new ArrayList<>(old);
        var snapshot = new ArrayList<Supplier<SnapElement>>(items.size());
        var inserted = new ArrayList<BatchValue>();
        for (var item : items) {
            var i = indexOf(unmatched, item);
            if (i >= 0) {
                var kept = unmatched.remove(i);
                snapshot.add(() -> kept);
                continue;
            }
            var planned = element(item, shape.element, old, where);
            inserted.add(planned.value());
            snapshot.add(() -> planned.snap(item));
        }
        ops.add(new KeyedPatchOp(coll, keySelector, unmatched.stream().map(e -> BatchValue.of(e.oop())).toList(), inserted));
        return () -> snapshot.stream().map(Supplier::get).toList();
    }

    /** Dictionaries: keys that are gone are removed, new or changed pairs are put (existing keys keep their object). */
    private Supplier<List<SnapElement>> patchDictionary(BatchValue coll, GsShape shape, List<Object> items, List<SnapElement> old, String where) {
        var oldKeys = new ArrayList<SnapElement>();
        var oldValues = new ArrayList<SnapElement>();
        for (var j = 0; j < old.size(); j++) (j % 2 == 0 ? oldKeys : oldValues).add(old.get(j));
        var unmatched = new ArrayList<Integer>();
        for (var j = 0; j < oldKeys.size(); j++) unmatched.add(j);
        var puts = new ArrayList<BatchValue>();
        var snapshot = new ArrayList<Supplier<SnapElement>>(items.size());
        for (var i = 0; i < items.size(); i += 2) {
            var key = items.get(i);
            var value = items.get(i + 1);
            var k = -1;
            for (var u = 0; u < unmatched.size(); u++)
                if (GsObjectContext.valueEquals(key, oldKeys.get(unmatched.get(u)).value())) {
                    k = u;
                    break;
                }
            if (k >= 0) {
                int j = unmatched.remove(k);
                var oldKey = oldKeys.get(j);
                if (GsObjectContext.valueEquals(value, oldValues.get(j).value())) {
                    var oldValue = oldValues.get(j);
                    snapshot.add(() -> oldKey);
                    snapshot.add(() -> oldValue);
                    continue;
                }
                var v = element(value, shape.value, oldValues, where);
                puts.add(BatchValue.of(oldKey.oop()));
                puts.add(v.value());
                snapshot.add(() -> oldKey);
                snapshot.add(() -> v.snap(value));
                continue;
            }
            var newKey = element(key, shape.key, oldKeys, where);
            var newValue = element(value, shape.value, oldValues, where);
            puts.add(newKey.value());
            puts.add(newValue.value());
            snapshot.add(() -> newKey.snap(key));
            snapshot.add(() -> newValue.snap(value));
        }
        ops.add(new DictionaryPatchOp(coll, unmatched.stream().map(j -> BatchValue.of(oldKeys.get(j).oop())).toList(), puts));
        return () -> snapshot.stream().map(Supplier::get).toList();
    }

    private static int indexOf(List<SnapElement> elements, Object item) {
        for (var i = 0; i < elements.size(); i++) if (GsObjectContext.valueEquals(item, elements.get(i).value())) return i;
        return -1;
    }

    private Planned element(Object value, GsShape shape, List<SnapElement> old, String where) {
        if (shape.isCollection()) {
            if (value == null) return new Planned(BatchValue.of(Oop.NIL), false, null);
            // The same Java collection as before (maybe moved): keep the GemStone collection;
            // patchContents brings its contents up to date.
            for (var e : old)
                if (e.nested() != null && e.value() == value) {
                    var nested = e.nested();
                    return new Planned(BatchValue.of(e.oop()), false, () -> nested);
                }
            var coll = create(ctx.kernel(collectionClassName(null, shape)));
            var made = replaceContents(coll, shape, items(value), List.of(), "element of " + where);
            return new Planned(coll, false, () -> new CollectionSnapshot(coll.resolved, made.get()));
        }
        // Unchanged scalar elements keep their OOP (and with it String vs. Symbol).
        if (value != null && !GsObjectContext.isEntity(value)) {
            var at = indexOf(old, value);
            if (at >= 0) return new Planned(BatchValue.of(old.get(at).oop()), old.get(at).isSymbol(), null);
        }
        var asSymbol = !old.isEmpty() ? old.stream().anyMatch(SnapElement::isSymbol) : value instanceof Enum<?>;
        var snap = new PropertySnapshot(Oop.NIL, null, null, asSymbol, true);
        return new Planned(toValue(value, shape, null, snap, where), asSymbol && (value instanceof String || value instanceof Enum<?>), null);
    }

    private BatchValue toValue(Object value, GsShape shape, GsPropertyMap property, PropertySnapshot snap, String where) {
        if (property != null && property.converter() != null) {
            var converter = property.converter();
            if (!converter.canWrite())
                throw new UnsupportedOperationException(where + ": " + converter.getClass().getSimpleName()
                        + " cannot convert values back to GemStone (implement toGemStone and canWrite).");
            return scalar(converter.toGemStone(value, property.metadata.type), snap, where);
        }
        if (value == null) return BatchValue.of(Oop.NIL);
        return switch (shape.kind) {
            case OOP -> BatchValue.of(orNil((Oop) value));
            case MAPPED -> entityValue(value);
            case OBJECT -> value instanceof Oop o ? BatchValue.of(orNil(o)) : GsObjectContext.isEntity(value) ? entityValue(value) : scalar(value, snap, where);
            case SCALAR -> scalar(value, snap, where);
            default -> throw new UnsupportedOperationException(where + ": nested collections cannot be saved here.");
        };
    }

    // An Oop field nobody set holds null or 0, which is not an object: writing it would be a
    // reference to OOP 0 and fail the batch with error 2106.
    private static Oop orNil(Oop oop) {
        return oop == null || oop.value() == 0 ? Oop.NIL : oop;
    }

    private BatchValue entityValue(Object entity) {
        var oop = ctx.oopFor(entity);
        return oop != null ? BatchValue.of(oop) : insert(entity);
    }

    // ---- Scalars ----------------------------------------------------------------------------

    private BatchValue scalar(Object value, PropertySnapshot snap, String where) {
        try {
            return scalarValue(value, snap, where);
        } catch (IllegalArgumentException | ArithmeticException | ClassCastException | java.time.DateTimeException e) {
            throw GsMappingException.of(where + ": " + value + " (" + (value == null ? "null" : value.getClass().getSimpleName())
                    + ") cannot be saved (" + e.getMessage() + ").");
        }
    }

    private BatchValue scalarValue(Object value, PropertySnapshot snap, String where) {
        return switch (value) {
            case null -> BatchValue.of(Oop.NIL);
            case Oop o -> BatchValue.of(o);
            case Enum<?> e when snap != null && snap.oop.isSmallInteger() -> BatchValue.of(Oop.fromSmallInteger(e.ordinal()));
            case Enum<?> e -> converted(2, text(symbolName(e, snap == null || snap.lowerFirst)));
            case String s when snap != null && snap.isSymbol -> converted(2, text(s));
            case String s -> GciSessionHelpers.isAscii(s) ? newObject(text(s)) : converted(3, text(s));
            case Boolean b -> BatchValue.of(Oop.fromBoolean(b));
            case CodePoint c -> BatchValue.of(Oop.fromCharacter(c.value()));
            case Character c when !Character.isSurrogate(c) -> BatchValue.of(Oop.fromCharacter(c));
            case Byte b -> integer(b);
            case Short s -> integer(s);
            case Integer i -> integer(i);
            case Long l -> integer(l);
            case BigInteger big -> big.bitLength() < 64 ? integer(big.longValue()) : converted(4, ascii(big.toString()));
            case Float f -> doubleValue(f);
            case Double d -> doubleValue(d);
            case BigDecimal m -> {
                var v = m.scale() < 0 ? m.setScale(0) : m;
                yield converted(5, pair(ascii(v.unscaledValue().toString()), Oop.fromSmallInteger(v.scale())));
            }
            case LocalDate date -> converted(6, Oop.fromSmallInteger(date.toEpochDay() - SMALLTALK_DATE_BASE));
            case LocalTime time -> converted(7, Oop.fromSmallInteger(time.toNanoOfDay() / 1000));
            case LocalDateTime dt -> dateAndTime(dt.atZone(ZoneId.systemDefault()).toOffsetDateTime());
            case OffsetDateTime dt -> dateAndTime(dt);
            case ZonedDateTime dt -> dateAndTime(dt.toOffsetDateTime());
            case Instant i -> dateAndTime(i.atOffset(ZoneOffset.UTC));
            case byte[] bytes -> newObject(new NewObjectSpec(KnownClasses.BYTE_ARRAY, bytes.clone(), null, null));
            default -> throw GsMappingException.of(where + ": no GemStone mapping for " + value.getClass().getName() + ".");
        };
    }

    /**
     * The Symbol an enum constant is written as: {@code IN_PROGRESS} (or {@code InProgress}) →
     * {@code inProgress}, or {@code InProgress} if the loaded Symbol started upper case.
     */
    static String symbolName(Enum<?> e, boolean lowerFirst) {
        var name = e.name();
        String camel;
        if (name.chars().noneMatch(Character::isLowerCase)) {
            var sb = new StringBuilder();
            for (var part : name.split("_")) {
                if (part.isEmpty()) continue;
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
            camel = sb.toString();
        } else {
            camel = name;
        }
        if (camel.isEmpty()) return camel;
        var first = lowerFirst ? Character.toLowerCase(camel.charAt(0)) : Character.toUpperCase(camel.charAt(0));
        return first + camel.substring(1);
    }

    private BatchValue integer(long value) {
        return Oop.fitsSmallInteger(value) ? BatchValue.of(Oop.fromSmallInteger(value)) : converted(4, ascii(Long.toString(value)));
    }

    private BatchValue doubleValue(double value) {
        var small = Oop.tryFromSmallDouble(value);
        return small != null ? BatchValue.of(small) : newObject(new NewObjectSpec(KnownClasses.FLOAT, null, null, value));
    }

    private BatchValue dateAndTime(OffsetDateTime value) {
        var instant = value.toInstant();
        var micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
        return converted(8, pair(Oop.fromSmallInteger(micros), Oop.fromSmallInteger(value.getOffset().getTotalSeconds())));
    }

    /** A String (ASCII) or Utf8 (anything else) for the store traversal. */
    private static NewObjectSpec text(String s) {
        return GciSessionHelpers.isAscii(s)
                ? new NewObjectSpec(KnownClasses.STRING, s.getBytes(StandardCharsets.US_ASCII), null, null)
                : new NewObjectSpec(KnownClasses.UTF8, s.getBytes(StandardCharsets.UTF_8), null, null);
    }

    private static NewObjectSpec ascii(String s) {
        return new NewObjectSpec(KnownClasses.STRING, s.getBytes(StandardCharsets.US_ASCII), null, null);
    }

    private static NewObjectSpec pair(Object first, Object second) {
        return new NewObjectSpec(KnownClasses.ARRAY, null, new ArrayList<>(List.of(first, second)), null);
    }

    private BatchValue newObject(NewObjectSpec spec) {
        var value = new BatchValue();
        value.spec = spec;
        onResolve.add(() -> value.resolved = spec.oop);
        return value;
    }

    private static BatchValue converted(int kind, Object payload) {
        var v = new BatchValue();
        v.kind = kind;
        if (payload instanceof NewObjectSpec spec) v.spec = spec;
        else v.payload = (Oop) payload;
        return v;
    }

    // ---- Comparison helpers -----------------------------------------------------------------

    /** Elements, or key/value pairs laid end to end for maps. */
    static List<Object> items(Object collection) {
        var items = new ArrayList<Object>();
        if (collection instanceof Map<?, ?> map) {
            for (var e : map.entrySet()) {
                items.add(e.getKey());
                items.add(e.getValue());
            }
        } else if (collection.getClass().isArray()) {
            var n = Array.getLength(collection);
            for (var i = 0; i < n; i++) items.add(Array.get(collection, i));
        } else {
            for (var e : (Iterable<?>) collection) items.add(e);
        }
        return items;
    }

    private static boolean contentsEqual(List<Object> current, List<SnapElement> old, ShapeKind kind) {
        if (current.size() != old.size()) return false;
        switch (kind) {
            case LIST, ARRAY -> {
                for (var i = 0; i < current.size(); i++) if (!GsObjectContext.valueEquals(current.get(i), old.get(i).value())) return false;
                return true;
            }
            case SET -> {
                var unmatched = new ArrayList<>(old);
                for (var c : current) {
                    var i = indexOf(unmatched, c);
                    if (i < 0) return false;
                    unmatched.remove(i);
                }
                return true;
            }
            default -> { // map: key/value pairs, order irrelevant
                for (var i = 0; i < current.size(); i += 2) {
                    var found = false;
                    for (var j = 0; j < old.size() && !found; j += 2)
                        found = GsObjectContext.valueEquals(current.get(i), old.get(j).value())
                                && GsObjectContext.valueEquals(current.get(i + 1), old.get(j + 1).value());
                    if (!found) return false;
                }
                return true;
            }
        }
    }

    // ---- Sending ----------------------------------------------------------------------------

    /** Sends the batch (if there is anything to do) and applies the snapshots. */
    void execute() {
        if (!changed.isEmpty() && feed != null) {
            ops.add(new PublishOp(feed, changed.stream().map(BatchValue::of).toList()));
            onSuccess.add(ctx::ringAfterCommit);
        }
        if (!ops.isEmpty()) send();
        for (var action : onSuccess) action.run();
        for (var s : scalarSnapshots) {
            var entry = s.target().entry();
            // New objects are temporary until the commit and keep their state on an abort: only
            // snapshots of existing objects are journaled.
            if (entry != null) ctx.journalProperty(entry, s.property());
            (entry != null ? entry : ctx.entries.get(s.target().entity())).setSnapshot(s.property(),
                    new PropertySnapshot(s.value().resolved, s.current(), null, s.isSymbol(), s.lowerFirst()));
        }
    }

    private void send() {
        var convertedValues = new ArrayList<BatchValue>();
        var size = 0;
        for (var op : ops)
            size += switch (op) {
                case CreateOp c -> 2;
                case StoreOp s -> 6;
                case ReplaceOp r -> 4;
                case PatchOp p -> 6;
                case DictionaryPatchOp d -> 5;
                case PublishOp p -> 3;
                case KeyedPatchOp k -> 6;
                case FillNewOp f -> 8;
            };
        var slots = new ArrayList<Object>(size);
        for (var op : ops) {
            switch (op) {
                case CreateOp c -> {
                    slots.add(small(1));
                    slots.add(c.cls());
                }
                case StoreOp st -> {
                    slots.add(small(2));
                    value(slots, st.target(), convertedValues);
                    slots.add(small(st.index()));
                    value(slots, st.value(), convertedValues);
                }
                case ReplaceOp r -> {
                    slots.add(small(3));
                    value(slots, r.target(), convertedValues);
                    slots.add(values(r.items(), convertedValues));
                }
                case PatchOp pa -> { // the server evaluates target, removed, inserted in this order
                    slots.add(small(4));
                    value(slots, pa.target(), convertedValues);
                    slots.add(small(pa.from()));
                    slots.add(values(pa.removed(), convertedValues));
                    slots.add(values(pa.inserted(), convertedValues));
                }
                case DictionaryPatchOp d -> {
                    slots.add(small(5));
                    value(slots, d.target(), convertedValues);
                    slots.add(values(d.removedKeys(), convertedValues));
                    slots.add(values(d.puts(), convertedValues));
                }
                case PublishOp pub -> {
                    slots.add(small(6));
                    slots.add(pub.feed());
                    slots.add(values(pub.objects(), convertedValues));
                }
                case KeyedPatchOp kp -> {
                    slots.add(small(7));
                    value(slots, kp.target(), convertedValues);
                    slots.add(text(kp.selector()));
                    slots.add(values(kp.removed(), convertedValues));
                    slots.add(values(kp.inserted(), convertedValues));
                }
                case FillNewOp f -> {
                    slots.add(small(9));
                    value(slots, f.target(), convertedValues);
                    slots.add(small(f.index()));
                    slots.add(f.cls());
                    slots.add(f.selector() == null ? Oop.NIL : text(f.selector()));
                    slots.add(values(f.items(), convertedValues));
                    slots.add(text(f.where()));
                }
            }
        }
        var root = new NewObjectSpec(KnownClasses.ARRAY, null, slots, null);
        var specs = reachable(root);
        var ids = session.getFreeOops(specs.size());
        for (var i = 0; i < specs.size(); i++) specs.get(i).oop = ids[i];
        var objects = specs.stream().map(SaveRun::toNewObject).toList();
        var result = Oop.NIL;
        try {
            result = session.storeAndExecute(objects, BATCH_SOURCE, root.oop);
            var answer = session.fetchVaryingOops(result);
            if (answer.length != created.size() + convertedValues.size())
                throw new GciException("saveChanges batch answered " + answer.length + " objects, expected " + (created.size() + convertedValues.size()) + ".");
            for (var i = 0; i < created.size(); i++) created.get(i).resolved = answer[i];
            for (var i = 0; i < convertedValues.size(); i++) convertedValues.get(i).resolved = answer[created.size() + i];
            for (var action : onResolve) action.run();
            ctx.retainAll(created.stream().map(c -> c.resolved).toList()); // new objects: the context owns them now
        } finally {
            // The objects of the store traversal are referenced from their holders now (or garbage).
            if (session.isLoggedIn()) {
                var all = java.util.Arrays.copyOf(ids, ids.length + 1);
                all[ids.length] = result;
                session.release(all);
            }
        }
    }

    private static Oop small(long v) {
        return Oop.fromSmallInteger(v);
    }

    private static void value(List<Object> slots, BatchValue v, List<BatchValue> converted) {
        slots.add(small(v.kind));
        slots.add(v.kind == 1 ? small(v.created) : v.slot());
        if (v.kind >= 2) converted.add(v);
    }

    private static NewObjectSpec values(List<BatchValue> values, List<BatchValue> converted) {
        var items = new ArrayList<Object>(values.size() * 2);
        for (var v : values) value(items, v, converted);
        return new NewObjectSpec(KnownClasses.ARRAY, null, items, null);
    }

    /** All objects of the store traversal: the ops Array and everything its slots create. */
    private static List<NewObjectSpec> reachable(NewObjectSpec root) {
        var all = new ArrayList<NewObjectSpec>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<NewObjectSpec, Boolean>());
        var todo = new ArrayDeque<NewObjectSpec>();
        todo.push(root);
        while (!todo.isEmpty()) {
            var spec = todo.pop();
            if (!seen.add(spec)) continue;
            all.add(spec);
            if (spec.slots != null) for (var slot : spec.slots) if (slot instanceof NewObjectSpec child) todo.push(child);
        }
        return all;
    }

    private static GciNewObject toNewObject(NewObjectSpec spec) {
        if (spec.floatValue != null) return GciNewObject.ofFloat(spec.oop, spec.floatValue);
        if (spec.bytes != null) return GciNewObject.byteObject(spec.oop, spec.cls, spec.bytes);
        var raw = new long[spec.slots.size()];
        for (var i = 0; i < raw.length; i++) {
            var slot = spec.slots.get(i);
            raw[i] = slot instanceof NewObjectSpec n ? n.oop.value() : ((Oop) slot).value();
        }
        return GciNewObject.pointers(spec.oop, spec.cls, raw);
    }

    /** Helpers shared with the core package. */
    static final class GciSessionHelpers {
        private GciSessionHelpers() {
        }

        static boolean isAscii(String s) {
            for (var i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
            return true;
        }
    }
}
