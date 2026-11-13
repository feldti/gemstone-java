package net.gs64.samples;

import java.lang.management.ManagementFactory;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.sun.management.ThreadMXBean;

import net.gs64.gci.GciNetParameters;
import net.gs64.gci.GciSession;
import net.gs64.gci.Oop;
import net.gs64.gci.mapping.GsClass;
import net.gs64.gci.mapping.GsCollection;
import net.gs64.gci.mapping.GsCollectionKind;
import net.gs64.gci.mapping.GsInstVar;
import net.gs64.gci.mapping.GsLoadOptions;
import net.gs64.gci.mapping.GsObjectContext;
import net.gs64.gci.mapping.GsOop;

/**
 * Load benchmark (the Java counterpart of the C# ReplicaBench): a large object graph (about
 * 825,000 domain objects) loaded in one go, with and without change tracking, and a large save.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp 'samples/target/classes:samples/target/lib/*' net.gs64.samples.ReplicaBench setup
 * … ReplicaBench [rounds]      loads it (tracked and untracked)
 * … ReplicaBench bigsave [n]   writes a new graph of n customers (41 mapped objects each)
 * … ReplicaBench cleanup       removes data and classes
 * </pre>
 *
 * Connection: GS_STONE, GS_GEM_SERVICE, GS_USER (DataCurator), GS_PASSWORD (swordfish). Model:
 * root → 20,000 customers → 10 orders each → 3 positions each → one of 5,000 shared articles;
 * revisions and string keys, doubles, timestamps, an RcIdentityBag per customer, ways back
 * mapped as Oop.
 */
public final class ReplicaBench {
    private ReplicaBench() {
    }

    static final int CUSTOMERS = 20_000, ORDERS_PER_CUSTOMER = 10, POSITIONS_PER_ORDER = 3, ARTICLES = 5_000, CHUNK = 1_000;
    static final String ROOT = "GemStoneGciBench_Root";
    static final String[][] CLASSES = {
        {"BenchEntity", "Object", "rev key"},
        {"BenchRoot", "BenchEntity", "customers articles"},
        {"BenchCustomer", "BenchEntity", "id name city orders related"},
        {"BenchOrder", "BenchEntity", "customer number placed shipped cancelled total discount positions"},
        {"BenchPosition", "BenchEntity", "order article quantity price"},
        {"BenchArticle", "BenchEntity", "number name price"},
    };

    public abstract static class BenchEntity {
        @GsOop Oop oop;
        @GsInstVar("rev") long rev;
        @GsInstVar("key") String key;
    }

    @GsClass("BenchRoot")
    public static final class BenchRoot extends BenchEntity {
        @GsInstVar("customers") @GsCollection(GsCollectionKind.ORDERED) List<BenchCustomer> customers = new ArrayList<>();
        @GsInstVar("articles") @GsCollection(GsCollectionKind.ARRAY) List<BenchArticle> articles = new ArrayList<>();
    }

    @GsClass("BenchCustomer")
    public static final class BenchCustomer extends BenchEntity {
        @GsInstVar("id") long id;
        @GsInstVar("name") String name;
        @GsInstVar("city") String city;
        @GsInstVar("orders") @GsCollection(GsCollectionKind.IDENTITY_BAG) List<BenchOrder> orders = new ArrayList<>();
        @GsInstVar("related") @GsCollection(GsCollectionKind.ORDERED) List<Oop> related = new ArrayList<>();
    }

    @GsClass("BenchOrder")
    public static final class BenchOrder extends BenchEntity {
        @GsInstVar("customer") Oop customer;
        @GsInstVar("number") long number;
        @GsInstVar("placed") OffsetDateTime placed;
        @GsInstVar("shipped") OffsetDateTime shipped;
        @GsInstVar("cancelled") OffsetDateTime cancelled;
        @GsInstVar("total") double total;
        @GsInstVar("discount") Double discount;
        @GsInstVar("positions") @GsCollection(GsCollectionKind.ORDERED) List<BenchPosition> positions = new ArrayList<>();
    }

    @GsClass("BenchPosition")
    public static final class BenchPosition extends BenchEntity {
        @GsInstVar("order") Oop order;
        @GsInstVar("article") BenchArticle article;
        @GsInstVar("quantity") long quantity;
        @GsInstVar("price") double price;
    }

    @GsClass("BenchArticle")
    public static final class BenchArticle extends BenchEntity {
        @GsInstVar("number") long number;
        @GsInstVar("name") String name;
        @GsInstVar("price") double price;
    }

    private static String env(String name, String fallback) {
        var v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public static void main(String[] args) {
        var net = new GciNetParameters(env("GS_STONE", "gs64stone"), env("GS_GEM_SERVICE", "gemnetobject"));
        try (var session = GciSession.login(net, env("GS_USER", "DataCurator"), env("GS_PASSWORD", "swordfish"))) {
            var mode = args.length > 0 ? args[0] : "load";
            switch (mode) {
                case "setup" -> {
                    cleanup(session, true);
                    setup(session);
                }
                case "cleanup" -> cleanup(session, false);
                case "bigsave" -> bigSave(session, args.length > 1 ? Integer.parseInt(args[1]) : 20_000);
                default -> {
                    var rounds = mode.equals("load") ? 1 : Integer.parseInt(mode);
                    for (var r = 0; r < rounds; r++) {
                        measure(session, "tracked  ", GsLoadOptions.DEFAULT);
                        measure(session, "untracked", GsLoadOptions.DEFAULT.withTrackChanges(false));
                    }
                }
            }
        }
    }

    static void setup(GciSession session) {
        var start = System.nanoTime();
        for (var c : CLASSES) {
            var ivars = String.join(" ", java.util.Arrays.stream(c[2].split(" ")).map(v -> "#" + v).toList());
            session.release(session.execute("""
                    (%s subclass: '%s' instVarNames: #(%s)
                        classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals)
                        compileAccessingMethodsFor: #(%s).
                    nil
                    """.formatted(c[1], c[0], ivars, ivars)));
        }
        session.execute("""
                | arts root |
                arts := (1 to: %1$d) collect: [:i |
                    BenchArticle new rev: 1; key: 'A', i printString; number: i; name: 'Article ', i printString;
                        price: (i \\\\ 500) / 4.0; yourself].
                root := BenchRoot new rev: 1; key: 'R'; customers: OrderedCollection new; articles: arts; yourself.
                UserGlobals at: #%2$s put: root.
                nil
                """.formatted(ARTICLES, ROOT));
        session.commit();
        for (var from = 1; from <= CUSTOMERS; from += CHUNK) {
            session.execute("""
                    | root arts cities customers |
                    root := UserGlobals at: #%1$s.
                    arts := root articles.
                    customers := root customers.
                    cities := #(#Berlin #Hamburg #Muenchen #Koeln #Frankfurt).
                    %2$d to: %3$d do: [:i | | c |
                        c := BenchCustomer new rev: 1; key: 'C', i printString; id: i; name: 'Customer ', i printString;
                            city: (cities at: i \\\\ 5 + 1); orders: RcIdentityBag new; related: OrderedCollection new; yourself.
                        customers isEmpty ifFalse: [c related add: customers last. customers last related add: c].
                        1 to: %4$d do: [:j | | o |
                            o := BenchOrder new rev: 1; key: 'O', i printString, '-', j printString; customer: c;
                                number: i * 100 + j; placed: DateAndTime now; shipped: (j odd ifTrue: [DateAndTime now]);
                                total: (i * j \\\\ 100000) / 8.0; discount: (j = 3 ifTrue: [0.05]);
                                positions: OrderedCollection new; yourself.
                            1 to: %5$d do: [:k |
                                o positions add: (BenchPosition new rev: 1; key: o key, '-', k printString; order: o;
                                    article: (arts at: (i * 7 + (j * 13) + k) \\\\ %6$d + 1);
                                    quantity: k; price: (i + k \\\\ 1000) / 4.0; yourself)].
                            c orders add: o].
                        customers add: c].
                    nil
                    """.formatted(ROOT, from, from + CHUNK - 1, ORDERS_PER_CUSTOMER, POSITIONS_PER_ORDER, ARTICLES));
            session.commit();
            System.out.print("\r" + (from + CHUNK - 1) + " customers");
        }
        System.out.printf("\rcreated %d customers in %.1f s%n", CUSTOMERS, (System.nanoTime() - start) / 1e9);
    }

    static void cleanup(GciSession session, boolean quiet) {
        var names = new ArrayList<String>();
        for (var i = CLASSES.length - 1; i >= 0; i--) names.add(CLASSES[i][0]);
        session.execute("UserGlobals removeKey: #" + ROOT + " ifAbsent: []; removeKey: #GemStoneGciBench_Big ifAbsent: []. #("
                + String.join(" ", names) + ") do: [:n | UserGlobals removeKey: n ifAbsent: []]. nil");
        session.commit();
        if (!quiet) System.out.println("removed");
    }

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    private static long usedHeap() {
        System.gc();
        var rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static void measure(GciSession session, String label, GsLoadOptions options) {
        try (var ctx = new GsObjectContext(session, GsGeneratedMappings.DEFAULT)) {
            session.abort();
            var memBefore = usedHeap();
            var calls = session.callCount();
            var cpu = THREADS.getCurrentThreadCpuTime();
            var allocated = THREADS.getCurrentThreadAllocatedBytes();
            var gc = gcMillis();
            var start = System.nanoTime();
            var root = ctx.load("UserGlobals at: #" + ROOT, BenchRoot.class, options);
            var seconds = (System.nanoTime() - start) / 1e9;
            cpu = THREADS.getCurrentThreadCpuTime() - cpu;
            allocated = THREADS.getCurrentThreadAllocatedBytes() - allocated;
            gc = gcMillis() - gc;
            var memAfter = usedHeap();
            // Walk the graph like the application would.
            var walk = System.nanoTime();
            long objects = 1 + root.articles.size();
            double sum = 0;
            for (var c : root.customers) {
                objects++;
                for (var o : c.orders) {
                    objects++;
                    sum += o.total + (o.shipped == null ? 0 : 1);
                    for (var p : o.positions) {
                        objects++;
                        sum += p.quantity * p.price + p.article.price;
                    }
                }
            }
            System.out.printf("%s: %,d objects in %.2f s (load thread CPU %.2f s), %,d GCI calls, %.0f MB heap, walk %.0f ms (checksum %s)%n",
                    label, objects, seconds, cpu / 1e9, session.callCount() - calls, (memAfter - memBefore) / 1e6,
                    (System.nanoTime() - walk) / 1e6, sum);
            System.out.printf("           GC: %.2f s, %.0f MB allocated by the load thread%n", gc / 1e3, allocated / 1e6);
        }
    }

    /** A large graph of new objects built in Java: the add (one batch), a save without changes and the commit, timed separately. */
    static void bigSave(GciSession session, int count) {
        try (var c = new GsObjectContext(session, GsGeneratedMappings.DEFAULT)) {
            session.abort();
            var arts = new ArrayList<BenchArticle>();
            for (var i = 1; i <= 500; i++) {
                var a = new BenchArticle();
                a.rev = 1;
                a.key = "A" + i;
                a.number = i;
                a.name = "Article " + i;
                a.price = i / 4.0;
                arts.add(a);
            }
            var root = new BenchRoot();
            root.rev = 1;
            root.key = "BIG";
            root.articles = arts;
            for (var i = 0; i < count; i++) {
                var bc = new BenchCustomer();
                bc.rev = 1;
                bc.key = "C" + i;
                bc.id = i;
                bc.name = "Customer " + i;
                bc.city = "Berlin";
                for (var o = 0; o < 10; o++) {
                    var order = new BenchOrder();
                    order.rev = 1;
                    order.key = "O" + i + "/" + o;
                    order.number = o;
                    order.placed = OffsetDateTime.now();
                    order.total = o * 1.5;
                    for (var q = 0; q < 3; q++) {
                        var p = new BenchPosition();
                        p.rev = 1;
                        p.key = "P" + i + "/" + o + "/" + q;
                        p.article = arts.get((i + o + q) % arts.size());
                        p.quantity = q;
                        p.price = q * 2.5;
                        order.positions.add(p);
                    }
                    bc.orders.add(order);
                }
                root.customers.add(bc);
            }
            var gc = gcMillis();
            var allocated = THREADS.getCurrentThreadAllocatedBytes();
            var start = System.nanoTime();
            var rootOop = c.add(root); // writes the whole new graph in one batch
            var add = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("add allocated %.0f MB, GC %.2f s%n", (THREADS.getCurrentThreadAllocatedBytes() - allocated) / 1e6, (gcMillis() - gc) / 1e3);
            start = System.nanoTime();
            var stores = c.saveChanges(); // nothing left: only the comparison of all tracked objects
            var compare = (System.nanoTime() - start) / 1_000_000;
            session.execute("UserGlobals at: #GemStoneGciBench_Big put: (Object _objectForOop: " + Long.toUnsignedString(rootOop.value()) + "). nil");
            start = System.nanoTime();
            session.commit();
            var commit = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("%d customers (%d mapped objects): add %d ms, then saveChanges %d ms (%d stores), commit %d ms%n",
                    count, count * 41 + 501, add, compare, stores, commit);
            session.execute("UserGlobals removeKey: #GemStoneGciBench_Big ifAbsent: [nil]. nil");
            session.commit();
        }
    }

}
