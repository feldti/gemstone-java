package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.gs64.gci.GciChangeFeedIntegrationTest.FeedItem;
import net.gs64.gci.mapping.GsChangeSync;
import net.gs64.gci.mapping.GsClass;
import net.gs64.gci.mapping.GsInstVar;
import net.gs64.gci.mapping.GsLoadOptions;
import net.gs64.gci.mapping.GsObjectContext;
import net.gs64.gci.mapping.GsUnsavedChangesException;

/**
 * Two clients with one session each keep their working contexts in sync through the change feed
 * ({@link GsChangeSync}). Commits the class GciChangeFeed, a feed, a test class and a test object
 * under unique keys in UserGlobals and removes them again.
 */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GsChangeSyncIntegrationTest {
    private GciSession sa, sb;
    private final String feedName = "GemStoneGciTest_Feed" + UUID.randomUUID().toString().replace("-", "");
    private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        sa = StoneEnv.login();
        sb = StoneEnv.login();
        GciChangeFeed.install(sa, feedName);
        sa.execute("""
                | c | c := Object subclass: 'GsFeedTestItem' instVarNames: #('name' 'children') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                UserGlobals at: #%s put: (c new instVarNamed: 'name' put: 'Dora';
                    instVarNamed: 'children' put: (RcIdentityBag with: (c new instVarNamed: 'name' put: 'a'; yourself)); yourself).
                true
                """.formatted(key));
        sa.commit();
        sb.abort();
    }

    @AfterEach
    void tearDown() {
        if (sa.isLoggedIn()) {
            sa.abort();
            sa.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]; removeKey: #" + feedName + " ifAbsent: [nil]; "
                    + "removeKey: #GsFeedTestItem ifAbsent: [nil]; removeKey: #GciChangeFeed ifAbsent: [nil]. true");
            sa.commit();
        }
        sa.close();
        sb.close();
    }

    private String item() {
        return "(UserGlobals at: #" + key + ")";
    }

    /** One client: a working context that publishes its commits, and a sync registered for it. */
    private final class Client implements AutoCloseable {
        final GsObjectContext context;
        final GsChangeSync sync;
        final FeedItem root;

        Client(GciSession session, String name) {
            session.abort();
            var feed = new GciChangeFeed(session, feedName);
            context = new GsObjectContext(session);
            context.setChangeFeed(feed);
            sync = new GsChangeSync(context, feed, name);
            sync.setFullCheckInterval(Duration.ofHours(1));
            sync.register();
            root = context.load(item(), FeedItem.class);
        }

        @Override
        public void close() {
            sync.close();
            context.close();
        }
    }

    /** Signals travel through the stone asynchronously: poll until the registration's ring arrived. */
    private static void seesNewClients(Client c) throws InterruptedException {
        var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!c.sync.poll().rang()) {
            if (System.nanoTime() > until) throw new AssertionError("The registration did not ring within 5 seconds.");
            Thread.sleep(5);
        }
    }

    @StoneTest
    void changesOfOtherClientsArriveInTheSameInstancesOwnChangesDoNot() throws Exception {
        try (var a = new Client(sa, "A"); var b = new Client(sb, "B")) {
            var bChild = b.root.children.get(0);
            seesNewClients(a); // B's registration rang A: A sees B now

            a.root.name = "Emil";
            a.root.children.add(new FeedItem("b"));
            a.context.saveChangesAndCommit();

            var r = b.sync.poll();
            assertTrue(r.rang());
            assertEquals(List.of(b.root), r.refreshed());
            assertSame(b.root, r.refreshed().get(0));
            assertEquals("Emil", b.root.name);
            assertEquals(List.of("a", "b"), b.root.children.stream().map(c -> c.name).sorted().toList());
            assertTrue(b.root.children.stream().anyMatch(c -> c == bChild)); // the untouched child keeps its instance
            assertEquals("b", ((FeedItem) r.added().get(0)).name);
            assertEquals(1, r.added().size());
            assertTrue(r.removed().isEmpty());

            var own = a.sync.poll(true); // A's own commit does not come back
            assertTrue(own.isEmpty());
            assertFalse(own.rang());

            b.root.name = "Fritz";
            b.context.saveChangesAndCommit();
            seesNewClients(a);
            assertEquals("Fritz", a.root.name);
        }
    }

    /** Registers {@code consumer} for a session that then logs out (a crashed client). */
    private void registerGoneConsumer(String consumer, boolean withoutTime) throws InterruptedException {
        long serial;
        try (var gone = StoneEnv.login()) {
            serial = gone.evaluate("GsSession serialOfSession: " + gone.gemSessionId(), Long.class);
            sa.abort();
            if (withoutTime) // an entry from before expiry existed
                sa.execute("((UserGlobals at: #" + feedName + ") instVarNamed: 'consumers') at: '" + consumer + "' put: "
                        + "(Array with: RcQueue new with: " + serial + " with: true). true");
            else
                new GciChangeFeed(sa, feedName).register(consumer, gone.gemSessionId(), true);
            sa.execute("(UserGlobals at: #" + feedName + ") publish: {" + item() + "}. true"); // its queue holds an object
            sa.commit();
        }
        // The stone notices the logout asynchronously.
        var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sa.evaluate("(GsSession sessionIdOfSerial: " + serial + ") ifNil: [0]", Long.class) > 0) {
            if (System.nanoTime() > until) throw new AssertionError("The session did not go away within 5 seconds.");
            Thread.sleep(20);
        }
    }

    @StoneTest
    void consumersOfSessionsThatAreGoneExpire() throws Exception {
        registerGoneConsumer("gone", false);
        registerGoneConsumer("old", true);
        try (var b = new Client(sb, "B")) { // registers B: expiry with grace 10 min
            var feed = new GciChangeFeed(sa, feedName);
            sa.abort();
            assertEquals(List.of("B", "gone"), feed.consumers().stream().sorted().toList()); // "old" had no time: gone at once
            assertEquals(1, b.sync.expiredConsumers()); // removed by B's registration

            assertTrue(feed.expireAbsentConsumers(Duration.ofHours(1)).isEmpty()); // within the grace time
            assertEquals(List.of("gone"), feed.expireAbsentConsumers(Duration.ZERO)); // B is logged in: never
            sa.commit();
            assertEquals(List.of("B"), feed.consumers());
        }
    }

    @StoneTest
    void aFullCheckExpiresConsumersThatAreGone() throws Exception {
        registerGoneConsumer("gone", false);
        try (var b = new Client(sb, "B")) { // registering keeps it: within the grace time
            b.sync.setExpireAbsentConsumersAfter(Duration.ZERO);
            assertTrue(b.sync.poll().isEmpty()); // no full check: nothing removed
            assertTrue(b.sync.poll(true).isEmpty());
            assertEquals(1, b.sync.expiredConsumers());
            sa.abort();
            assertEquals(List.of("B"), new GciChangeFeed(sa, feedName).consumers()); // committed
        }
    }

    @StoneTest
    void aClientWhoseEntryIsGoneRegistersAgainAndReloads() {
        try (var b = new Client(sb, "B")) {
            sa.abort();
            new GciChangeFeed(sa, feedName).unregister("B");
            sa.commit();

            assertTrue(b.sync.poll(true).reloadRequired());
            sa.abort();
            assertTrue(new GciChangeFeed(sa, feedName).isRegistered("B"));
            assertFalse(b.sync.poll(true).reloadRequired());
        }
    }

    @StoneTest
    void unregisterRemovesTheClient() {
        try (var b = new Client(sb, "B")) {
            b.sync.unregister();
            sa.abort();
            assertFalse(new GciChangeFeed(sa, feedName).isRegistered("B"));
        }
    }

    @GsClass("GsSyncTestNode")
    static final class Node {
        @GsInstVar("name") String name;
        @GsInstVar("parent") Node parent;
        @GsInstVar("children") List<Node> children = new ArrayList<>();

        Node() {
        }

        Node(String name, Node parent) {
            this.name = name;
            this.parent = parent;
        }
    }

    /**
     * The gem expands a root only where it first reaches it: with the child first, the owner is
     * reached from the child one level down, and its children collection comes in a later round.
     */
    @StoneTest
    void aRefreshOfAnOwnerAndAChildStopsAtKnownObjectsInEitherOrder() {
        sa.abort();
        var rootOop = sa.execute("""
                | c root |
                c := Object subclass: 'GsSyncTestNode' instVarNames: #('name' 'parent' 'children') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                root := c new instVarNamed: 'name' put: 'root'; instVarNamed: 'children' put: OrderedCollection new; yourself.
                1 to: 2000 do: [:i | (root instVarNamed: 'children') add: (c new instVarNamed: 'name' put: i printString;
                    instVarNamed: 'parent' put: root; instVarNamed: 'children' put: OrderedCollection new; yourself)].
                root
                """);
        try (var ctx = new GsObjectContext(sa)) {
            var root = ctx.load(rootOop, Node.class);
            var child = ctx.oopFor(root.children.get(0));
            var options = GsLoadOptions.DEFAULT.withStopAtLoadedObjects(true).withDepth(2);
            for (var order : List.of(List.of(rootOop, child), List.of(child, rootOop))) {
                assertEquals(2, ctx.refresh(order, options).size());
                assertTrue(ctx.lastLoadReports() < 100, ctx.lastLoadReports() + " reports (" + ctx.lastLoad() + ")");
            }
        } finally {
            sa.release(rootOop);
            sa.abort();
        }
    }

    /**
     * Every node points back to the root (bidirectional associations): a refresh must stop at the
     * objects the context already holds, otherwise refreshing one node reloads the whole graph.
     */
    @StoneTest
    void aRefreshStopsAtObjectsTheContextAlreadyHolds() throws Exception {
        var graph = "(UserGlobals at: #" + key + "Graph)";
        sa.abort();
        sa.execute("""
                | c root |
                c := Object subclass: 'GsSyncTestNode' instVarNames: #('name' 'parent' 'children') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                root := c new instVarNamed: 'name' put: 'root'; instVarNamed: 'children' put: OrderedCollection new; yourself.
                1 to: 2000 do: [:i | | n | n := c new instVarNamed: 'name' put: i printString; instVarNamed: 'parent' put: root;
                    instVarNamed: 'children' put: OrderedCollection new; yourself.
                    (root instVarNamed: 'children') add: n].
                UserGlobals at: #%sGraph put: root.
                true
                """.formatted(key));
        sa.commit();

        GsObjectContext aCtx = null, bCtx = null;
        GsChangeSync aSync = null, bSync = null;
        try {
            sa.abort();
            var aFeed = new GciChangeFeed(sa, feedName);
            aCtx = new GsObjectContext(sa);
            aCtx.setChangeFeed(aFeed);
            aSync = new GsChangeSync(aCtx, aFeed, "A");
            aSync.setFullCheckInterval(Duration.ofHours(1));
            aSync.register();
            var aRoot = aCtx.load(graph, Node.class);

            sb.abort();
            var bFeed = new GciChangeFeed(sb, feedName);
            bCtx = new GsObjectContext(sb);
            bCtx.setChangeFeed(bFeed);
            bSync = new GsChangeSync(bCtx, bFeed, "B");
            bSync.setFullCheckInterval(Duration.ofHours(1));
            bSync.register();
            var bRoot = bCtx.load(graph, Node.class);

            var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!aSync.poll().rang()) {
                if (System.nanoTime() > until) throw new AssertionError("The registration did not ring within 5 seconds.");
                Thread.sleep(5);
            }
            var bFirst = bRoot.children.get(0);
            var bSecond = bRoot.children.get(1);

            // Changed on the server, but not published: a refresh that stops at known objects leaves it alone.
            sa.execute("((" + graph + " instVarNamed: 'children') at: 2) instVarNamed: 'name' put: 'silent'. true");
            aRoot.children.get(0).name = "first";
            aRoot.children.add(new Node("new", aRoot));
            aCtx.saveChangesAndCommit();

            var r = bSync.poll();
            assertEquals("first", bFirst.name);
            assertEquals("2", bSecond.name); // not published: not reloaded
            assertEquals(2001, bRoot.children.size());
            assertEquals(1, r.added().size());
            var added = (Node) r.added().get(0);
            assertEquals("new", added.name);
            assertSame(bRoot, added.parent); // the new node's reference to a known object
            assertSame(bRoot, bFirst.parent);
            assertTrue(bCtx.lastLoadReports() < 100, bCtx.lastLoadReports() + " reports for 2 published objects.");
        } finally {
            if (aSync != null) aSync.close();
            if (aCtx != null) aCtx.close();
            if (bSync != null) bSync.close();
            if (bCtx != null) bCtx.close();
            sa.abort();
            sa.execute("UserGlobals removeKey: #" + key + "Graph ifAbsent: [nil]; removeKey: #GsSyncTestNode ifAbsent: [nil]. true");
            sa.commit();
        }
    }

    @StoneTest
    void aPollWithoutDoorbellIsOneRoundTrip() {
        try (var b = new Client(sb, "B")) {
            var calls = sb.callCount();
            assertTrue(b.sync.poll().isEmpty());
            assertEquals(1, sb.callCount() - calls);
        }
    }

    @StoneTest
    void removedElementsAreReported() throws Exception {
        try (var a = new Client(sa, "A"); var b = new Client(sb, "B")) {
            var bChild = b.root.children.get(0);
            seesNewClients(a);
            a.root.children.clear();
            a.context.saveChangesAndCommit();

            var r = b.sync.poll();
            assertTrue(b.root.children.isEmpty());
            assertEquals(1, r.removed().size());
            assertSame(bChild, r.removed().get(0));
        }
    }

    @StoneTest
    void aFullCheckCatchesAWriterThatDidNotRing() {
        try (var b = new Client(sb, "B")) {
            sa.abort();
            sa.execute(item() + " instVarNamed: 'name' put: 'Silent'. (UserGlobals at: #" + feedName + ") publish: {" + item() + "}. true");
            sa.commit(); // no ring

            assertTrue(b.sync.poll().isEmpty()); // no doorbell, full check not due
            assertEquals("Dora", b.root.name);
            b.sync.setFullCheckInterval(Duration.ZERO);
            var r = b.sync.poll();
            assertFalse(r.rang());
            assertEquals("Silent", b.root.name);
        }
    }

    @StoneTest
    void unsavedChangesKeepThePublishedObjectsInTheQueue() throws Exception {
        try (var a = new Client(sa, "A"); var b = new Client(sb, "B")) {
            seesNewClients(a);
            a.root.name = "Emil";
            a.context.saveChangesAndCommit();

            b.root.name = "local"; // not idle: an unsaved change
            assertThrows(GsUnsavedChangesException.class, () -> b.sync.poll());
            assertEquals("local", b.root.name);

            b.root.name = "Dora"; // back to the loaded value
            var r = b.sync.poll(); // the failed take is taken again without a doorbell
            assertEquals(List.of(b.root), r.refreshed());
            assertEquals("Emil", b.root.name);
        }
    }

    @StoneTest
    void manyPublishedObjectsRequireAReload() throws Exception {
        try (var a = new Client(sa, "A"); var b = new Client(sb, "B")) {
            b.sync.setReloadThreshold(0);
            seesNewClients(a);
            a.root.name = "Emil";
            a.context.saveChangesAndCommit();

            var r = b.sync.poll();
            assertTrue(r.reloadRequired());
            assertTrue(r.refreshed().isEmpty());
            assertEquals("Dora", b.root.name); // untouched: the application reloads
            assertTrue(b.sync.poll(true).isEmpty()); // the take was committed
        }
    }

    @StoneTest
    void backgroundPollsOnlyBehindTheGate() throws Exception {
        try (var a = new Client(sa, "A"); var b = new Client(sb, "B")) {
            seesNewClients(a);
            var gate = new ReentrantLock();
            var open = new java.util.concurrent.atomic.AtomicBoolean();
            var changed = new CompletableFuture<GsChangeSync.Result>();
            b.sync.onChanged(changed::complete);
            b.sync.start(Duration.ofMillis(20), () -> open.get() && gate.tryLock(), gate::unlock);

            a.root.name = "Gustav";
            a.context.saveChangesAndCommit();
            Thread.sleep(300);
            assertFalse(changed.isDone()); // the client is busy: no poll
            open.set(true);

            var e = changed.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(b.root), e.refreshed());
            gate.lock();
            try {
                assertEquals("Gustav", b.root.name);
            } finally {
                gate.unlock();
            }
            b.sync.stop();
        }
    }
}
