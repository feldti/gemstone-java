package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.gs64.gci.mapping.GsClass;
import net.gs64.gci.mapping.GsCollection;
import net.gs64.gci.mapping.GsCollectionKind;
import net.gs64.gci.mapping.GsInstVar;
import net.gs64.gci.mapping.GsObjectContext;
import net.gs64.gci.mapping.GsReplica;

/**
 * The change feed and the replica that follows it. Commits the class GciChangeFeed, a feed, a
 * test class and a test object under unique keys in UserGlobals and removes them again.
 */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GciChangeFeedIntegrationTest {
    @GsClass("GsFeedTestItem")
    static final class FeedItem {
        @GsInstVar("name") String name;
        @GsInstVar("children") @GsCollection(GsCollectionKind.IDENTITY_BAG) List<FeedItem> children = new ArrayList<>();

        FeedItem() {
        }

        FeedItem(String name) {
            this.name = name;
        }
    }

    private GciSession w;      // consumer side (replica work session)
    private GciSession writer; // the importer
    private GciSession listen; // the replica's listener session
    private final String feedName = "GemStoneGciTest_Feed" + UUID.randomUUID().toString().replace("-", "");
    private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        w = StoneEnv.login();
        writer = StoneEnv.login();
        listen = StoneEnv.login();
        GciChangeFeed.install(w, feedName);
        w.execute("""
                | c | c := Object subclass: 'GsFeedTestItem' instVarNames: #('name' 'children') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                UserGlobals at: #%s put: (c new instVarNamed: 'name' put: 'Dora';
                    instVarNamed: 'children' put: (RcIdentityBag with: (c new instVarNamed: 'name' put: 'a'; yourself)); yourself).
                true
                """.formatted(key));
        w.commit();
        writer.abort();
    }

    @AfterEach
    void tearDown() {
        if (w.isLoggedIn()) {
            w.abort();
            w.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]; removeKey: #" + feedName + " ifAbsent: [nil]; "
                    + "removeKey: #GsFeedTestItem ifAbsent: [nil]; removeKey: #GciChangeFeed ifAbsent: [nil]. true");
            w.commit();
        }
        w.close();
        writer.close();
        listen.close();
    }

    private String item() {
        return "(UserGlobals at: #" + key + ")";
    }

    private GciChangeFeed feed(GciSession session) {
        session.abort();
        return new GciChangeFeed(session, feedName);
    }

    private static <T> T within(CompletableFuture<T> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    @StoneTest
    void publishedObjectsReachEveryConsumer() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        feed.register("two", w.gemSessionId());
        w.commit();
        assertEquals(List.of("one", "two"), feed.consumers().stream().sorted().toList());
        var writerFeed = feed(writer);
        var item = writer.execute(item());
        writerFeed.publish(item);
        writerFeed.publish(item, Oop.NIL); // twice in one transaction: taken once
        writer.commit();
        w.abort();
        assertEquals(2, feed.pending("one"));
        assertEquals(List.of(item), Arrays.asList(feed.take("one")));
        assertEquals(List.of(item), Arrays.stream(feed.take("two")).filter(o -> !o.isNil()).toList());
        w.commit();
        w.abort();
        assertEquals(0, feed.take("one").length);
        assertEquals(0, feed.pending("two"));
        writer.release(item);
    }

    @StoneTest
    void anAbortedTakeKeepsTheObjects() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        var item = w.execute(item());
        feed.publish(item);
        w.commit();
        assertEquals(List.of(item), Arrays.asList(feed.take("one")));
        w.abort(); // e.g. the refresh failed
        assertEquals(List.of(item), Arrays.asList(feed.take("one")));
        w.commit();
        w.release(item);
    }

    @StoneTest
    void parallelWritersDoNotConflict() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        // Both writers publish in overlapping transactions; both commits must succeed.
        var a = feed(w);
        var b = feed(writer);
        var x = w.execute(item());
        var y = writer.execute(item() + " instVarNamed: 'children'");
        a.publish(x);
        b.publish(y);
        assertTrue(w.tryCommit());
        assertTrue(writer.tryCommit());
        w.abort();
        assertEquals(Set.of(x, y), new HashSet<>(Arrays.asList(feed.take("one"))));
        w.commit();
        w.release(x);
        writer.release(y);
    }

    @StoneTest
    void unregisteredConsumersGetNothing() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        feed.unregister("one");
        w.commit();
        assertTrue(feed.consumers().isEmpty());
        var item = w.execute(item());
        feed.publish(item);
        w.commit();
        assertEquals(0, feed.take("one").length);
        w.release(item);
    }

    private GsReplica<FeedItem> replica(GsObjectContext ctx, GciSignalListener listener, Duration poll, CompletableFuture<GsReplica.Change> changed) {
        var replica = new GsReplica<>(ctx, feed(w), "replica", listener, item(), FeedItem.class);
        replica.setPollInterval(poll);
        replica.onChanged(changed::complete);
        replica.start();
        return replica;
    }

    @StoneTest
    void theReplicaFollowsASmalltalkWriter() throws Exception {
        var changed = new CompletableFuture<GsReplica.Change>();
        try (var ctx = new GsObjectContext(w); var listener = new GciSignalListener(listen);
             var replica = replica(ctx, listener, Duration.ofMinutes(5), changed)) {
            assertEquals("Dora 1", replica.read(r -> r.name + " " + r.children.size()));
            // An importer written in Smalltalk: change, publish, commit, ring.
            writer.abort();
            writer.execute("""
                    | item feed |
                    item := %s.
                    feed := UserGlobals at: #%s.
                    item instVarNamed: 'name' put: 'Emil'.
                    (item instVarNamed: 'children') add: (GsFeedTestItem new instVarNamed: 'name' put: 'b'; yourself).
                    feed publish: {item. item instVarNamed: 'children'}.
                    System commitTransaction ifFalse: [nil error: 'commit failed'].
                    feed ring.
                    true
                    """.formatted(item(), feedName));
            var e = within(changed); // the doorbell, not the timer (5 minutes)
            assertTrue(e.entities().stream().anyMatch(o -> o instanceof FeedItem f && "Emil".equals(f.name)));
            assertEquals("Emil 2", replica.read(r -> r.name + " " + r.children.size()));
            assertEquals(List.of("a", "b"), replica.read(r -> r.children.stream().map(c -> c.name).sorted().toList()));
        }
    }

    @StoneTest
    void theReplicaFollowsAWriterThatListsNothing() throws Exception {
        var changed = new CompletableFuture<GsReplica.Change>();
        try (var ctx = new GsObjectContext(w); var listener = new GciSignalListener(listen);
             var replica = replica(ctx, listener, Duration.ofMinutes(5), changed)) {
            assertEquals("Dora", replica.read(r -> r.name));
            // Plain Smalltalk; the commit method publishes the dirty list (what a code generator would emit).
            writer.abort();
            assertTrue(writer.evaluate(item() + " instVarNamed: 'name' put: 'Emil'.\n(UserGlobals at: #" + feedName
                    + ") commitPublishingWrittenObjects: [:o | o isKindOf: GsFeedTestItem]", Boolean.class));
            var e = within(changed);
            assertTrue(e.entities().stream().anyMatch(o -> o instanceof FeedItem f && "Emil".equals(f.name)));
            assertEquals("Emil", replica.read(r -> r.name));
        }
    }

    @StoneTest
    void publishWrittenObjectsPublishesWhatTheTransactionWrote() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        writer.abort();
        var item = writer.execute(item());
        // Writes the item, a new (temporary) child and UserGlobals; the filter keeps the item only.
        assertEquals(1L, writer.evaluate(item() + " instVarNamed: 'name' put: 'Fritz'.\nUserGlobals at: #" + key
                + "_tmp put: (GsFeedTestItem new instVarNamed: 'name' put: 'new'; yourself).\n(UserGlobals at: #" + feedName
                + ") publishWrittenObjects: [:o | o isKindOf: GsFeedTestItem]", Long.class));
        writer.commit();
        w.abort();
        assertEquals(List.of(item), Arrays.asList(feed.take("one")));
        w.commit();
        writer.execute("UserGlobals removeKey: #" + key + "_tmp. true");
        writer.commit();
        writer.release(item);
    }

    @StoneTest
    void aCommitConflictTakesThePublishBack() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        // The writer and another session change the same object; the other one commits first.
        writer.abort();
        writer.execute(item() + " instVarNamed: 'name' put: 'Writer'. true");
        w.execute(item() + " instVarNamed: 'name' put: 'Other'. true");
        w.commit();
        assertFalse(writer.evaluate("(UserGlobals at: #" + feedName + ") commitPublishingWrittenObjects: nil", Boolean.class));
        writer.abort();
        w.abort();
        assertEquals(0, feed.pending("one"));
    }

    @StoneTest
    void withoutTheDoorbellTheTimerCatchesUp() throws Exception {
        var changed = new CompletableFuture<GsReplica.Change>();
        try (var ctx = new GsObjectContext(w); var listener = new GciSignalListener(listen);
             var replica = replica(ctx, listener, Duration.ofMillis(200), changed)) {
            var writerFeed = feed(writer);
            writer.execute(item() + " instVarNamed: 'name' put: 'Frieda'. true");
            var item = writer.execute(item());
            writerFeed.publish(item);
            writer.commit(); // no ring: the signal got lost, say
            writer.release(item);
            within(changed);
            assertEquals("Frieda", replica.read(r -> r.name));
        }
    }

    @StoneTest
    void saveChangesPublishesInItsTransaction() {
        var feed = feed(w);
        feed.register("one", w.gemSessionId());
        w.commit();
        writer.abort();
        try (var ctx = new GsObjectContext(writer)) {
            ctx.setChangeFeed(feed(writer));
            var item = ctx.load(item(), FeedItem.class);
            var itemOop = ctx.oopFor(item);
            var child = item.children.get(0);
            ctx.saveChanges(); // nothing changed: nothing published
            child.name = "a2"; // only the child changes
            ctx.saveChanges();
            writer.abort(); // takes back the change and the publish
            w.abort();
            assertEquals(0, feed.pending("one"));
            item.children.add(new FeedItem("c")); // a collection of the item and a new object
            ctx.saveChangesAndCommit(); // the child counts as changed again
            var childOop = ctx.oopFor(child);
            w.abort();
            assertEquals(1, feed.pending("one")); // one publish per batch
            assertEquals(Set.of(itemOop, childOop), new HashSet<>(Arrays.asList(feed.take("one"))));
            w.commit();
        }
    }

    @StoneTest
    void settingAFeedOfAnotherSessionThrows() {
        try (var ctx = new GsObjectContext(writer)) {
            assertThrows(IllegalArgumentException.class, () -> ctx.setChangeFeed(feed(w)));
        }
    }

    @StoneTest
    void theReplicaFollowsAMappedWriter() throws Exception {
        var changed = new CompletableFuture<GsReplica.Change>();
        try (var ctx = new GsObjectContext(w); var listener = new GciSignalListener(listen);
             var replica = replica(ctx, listener, Duration.ofMinutes(5), changed)) {
            // An importer written in Java: no publish, no ring.
            writer.abort();
            try (var importer = new GsObjectContext(writer)) {
                importer.setChangeFeed(feed(writer));
                var item = importer.load(item(), FeedItem.class);
                item.name = "Gustav";
                item.children.add(new FeedItem("d"));
                importer.saveChangesAndCommit();
            }
            within(changed); // the doorbell, not the timer (5 minutes)
            assertEquals("Gustav 2", replica.read(r -> r.name + " " + r.children.size()));
        }
    }
}
