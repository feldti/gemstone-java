package net.gs64.gci.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.gs64.gci.GciSession;
import net.gs64.gci.GciTransactionConflictException;
import net.gs64.gci.StoneEnv;
import net.gs64.gci.StoneTest;

/**
 * Saved but uncommitted changes and aborts: the snapshots must follow the server back. Commits a
 * class and a test object under a unique key in UserGlobals and removes both again.
 */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GsUncommittedSavesIntegrationTest {
    @GsClass("GsTxTestItem")
    static final class TxItem {
        @GsInstVar("name") String name;
        @GsInstVar("tags") List<String> tags = new ArrayList<>();
        @GsInstVar("child") TxItem child;

        TxItem() {
        }

        TxItem(String name) {
            this.name = name;
        }
    }

    private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "");
    private GciSession worker;
    private GciSession other;

    @BeforeEach
    void setUp() {
        worker = StoneEnv.login();
        other = StoneEnv.login();
        worker.execute("""
                | c | c := Object subclass: 'GsTxTestItem' instVarNames: #('name' 'tags' 'child') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                UserGlobals at: #%s put: (c new instVarNamed: 'name' put: 'Dora';
                    instVarNamed: 'tags' put: (OrderedCollection with: 'a' with: 'b'); yourself).
                true
                """.formatted(key));
        worker.commit();
        other.abort();
    }

    @AfterEach
    void tearDown() {
        if (worker.isLoggedIn()) {
            worker.abort();
            worker.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]; removeKey: #GsTxTestItem ifAbsent: [nil]. true");
            worker.commit();
        }
        worker.close();
        other.close();
    }

    private String item() {
        return "(UserGlobals at: #" + key + ")";
    }

    /** What another session sees after the worker committed (or not). */
    private <T> T committed(String source, Class<T> type) {
        other.abort();
        return other.evaluate(source, type);
    }

    @StoneTest
    void anAbortTakesBackUncommittedSaves() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            item.tags.add("c");
            assertEquals(2, ctx.saveChanges());
            worker.abort(); // the server forgets the saved changes …
            assertEquals("Dora", worker.evaluate(item() + " instVarNamed: 'name'", String.class));
            // … and so does the context: the object is changed again, not silently overwritten …
            assertThrows(GsUnsavedChangesException.class, () -> ctx.load(item(), TxItem.class));
            // … and the next save writes everything again.
            assertEquals(2, ctx.saveChanges());
            worker.commit();
            assertEquals("Emil", committed(item() + " instVarNamed: 'name'", String.class));
            assertEquals(3L, committed("(" + item() + " instVarNamed: 'tags') size", Long.class));
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void aCommitMakesSavesFinal() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            ctx.saveChanges();
            worker.commit();
            worker.abort();
            assertEquals(0, ctx.saveChanges());
            assertSame(item, ctx.load(item(), TxItem.class));
        }
    }

    @StoneTest
    void newObjectsSurviveAnAbort() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            var child = new TxItem("Kind");
            item.child = child;
            ctx.saveChanges();
            var before = ctx.oopFor(child);
            assertNotNull(before);
            worker.abort(); // the reference is gone, the new (temporary) object keeps its state
            assertEquals(true, worker.evaluate("(" + item() + " instVarNamed: 'child') isNil"));
            assertEquals(1, ctx.saveChanges()); // only the reference is written again
            assertEquals(before, ctx.oopFor(child));
            worker.commit();
            assertEquals("Kind", committed("(" + item() + " instVarNamed: 'child') instVarNamed: 'name'", String.class));
        }
    }

    @StoneTest
    void aReloadBetweenSaveAndAbort() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            ctx.saveChanges();
            assertSame(item, ctx.load(item(), TxItem.class)); // new snapshot, taken from the uncommitted state
            worker.abort();
            assertEquals(1, ctx.saveChanges());
            assertEquals("Emil", worker.evaluate(item() + " instVarNamed: 'name'", String.class));
        }
    }

    /** A rollback as an application does it: abort, then reload over the local changes. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRollbackIsAnAbortAndAReloadThatDiscardsLocalChanges(boolean savedBefore) {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            var child = new TxItem("Kind");
            item.name = "Emil";
            item.tags.add("c");
            item.child = child;
            if (savedBefore) assertEquals(6, ctx.saveChanges()); // 3 changes, 3 instance variables of the new object
            worker.abort();
            var again = ctx.load(item(), TxItem.class, GsLoadOptions.DEFAULT.withDiscardLocalChanges(true));
            assertSame(item, again); // the same Java instance, refilled
            assertEquals("Dora", item.name);
            assertEquals(List.of("a", "b"), item.tags);
            assertNull(item.child); // the new object is no longer referenced
            assertEquals(0, ctx.saveChanges()); // nothing left to write
            assertEquals(savedBefore, ctx.oopFor(child) != null); // a saved new object stays known (a temporary object)
        }
    }

    @StoneTest
    void afterAConflictTheChangesCanBeSavedAgain() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            ctx.saveChanges();
            other.execute(item() + " instVarNamed: 'name' put: 'Fritz'. true");
            other.commit();
            assertThrows(GciTransactionConflictException.class, worker::commit);
            worker.abort();
            assertEquals(1, ctx.saveChanges());
            worker.commit();
            assertEquals("Emil", committed(item() + " instVarNamed: 'name'", String.class));
        }
    }

    @StoneTest
    void saveChangesAndCommitRetriesAConflict() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            item.tags.remove("a");
            // Another session commits a change to the same objects after the worker's view began.
            other.execute(item() + " instVarNamed: 'name' put: 'Fritz'. (" + item() + " instVarNamed: 'tags') add: 'x'. true");
            other.commit();
            assertEquals(2, ctx.saveChangesAndCommit()); // conflict, abort, written again, committed
            assertEquals("Emil", committed(item() + " instVarNamed: 'name'", String.class));
            // The collection was patched, not replaced: the other session's element survives.
            assertEquals("b x", committed("(" + item() + " instVarNamed: 'tags') inject: '' into: [:s :e | s isEmpty ifTrue: [e] ifFalse: [s, ' ', e]]", String.class));
            assertEquals(0, ctx.saveChanges());
        }
    }

    /** Loads the item and gives it a committed child, so the context tracks two objects. */
    private TxItem[] twoTracked(GsObjectContext ctx) {
        var item = ctx.load(item(), TxItem.class);
        item.child = new TxItem("Kind");
        ctx.saveChangesAndCommit();
        return new TxItem[] {item, item.child};
    }

    @StoneTest
    void saveChangesWithCandidatesComparesOnlyThose() {
        try (var ctx = new GsObjectContext(worker)) {
            var two = twoTracked(ctx);
            two[0].name = "Emil";
            two[1].name = "Paula";
            assertEquals(1, ctx.saveChanges(Arrays.asList(two[0], null, two[0], "not tracked")));
            assertEquals("Emil", worker.evaluate(item() + " instVarNamed: 'name'", String.class));
            assertEquals("Kind", worker.evaluate("(" + item() + " instVarNamed: 'child') instVarNamed: 'name'", String.class));
            assertEquals(1, ctx.saveChanges()); // the child is still unsaved; a full save finds it
            assertEquals("Paula", worker.evaluate("(" + item() + " instVarNamed: 'child') instVarNamed: 'name'", String.class));
        }
    }

    @StoneTest
    void newObjectsReachableFromACandidateAreSaved() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            var child = new TxItem("Neu");
            item.child = child;
            assertEquals(4, ctx.saveChanges(List.of(item))); // the reference and the new object's 3 instance variables
            assertEquals("Neu", worker.evaluate("(" + item() + " instVarNamed: 'child') instVarNamed: 'name'", String.class));
            assertNotNull(ctx.oopFor(child));
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void anAbortAfterACandidateSaveLosesNothing() {
        try (var ctx = new GsObjectContext(worker)) {
            var two = twoTracked(ctx);
            two[0].name = "Emil";
            assertEquals(1, ctx.saveChanges(List.of(two[0])));
            worker.abort(); // takes the save back; the next transaction's candidates do not name the item
            two[1].name = "Paula";
            assertEquals(2, ctx.saveChanges(List.of(two[1]))); // the item is compared again anyway
            worker.commit();
            assertEquals("Emil", committed(item() + " instVarNamed: 'name'", String.class));
            assertEquals("Paula", committed("(" + item() + " instVarNamed: 'child') instVarNamed: 'name'", String.class));
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void saveChangesAndCommitWithCandidatesRetriesAConflict() {
        try (var ctx = new GsObjectContext(worker)) {
            var item = ctx.load(item(), TxItem.class);
            item.name = "Emil";
            other.execute(item() + " instVarNamed: 'name' put: 'Fritz'. true");
            other.commit();
            Iterable<Object> once = new Iterable<>() { // enumerated once, written twice
                private boolean used;

                @Override
                public java.util.Iterator<Object> iterator() {
                    if (used) throw new IllegalStateException("enumerated twice");
                    used = true;
                    return List.<Object>of(item).iterator();
                }
            };
            assertEquals(1, ctx.saveChangesAndCommit(once, 3));
            assertEquals("Emil", committed(item() + " instVarNamed: 'name'", String.class));
            assertEquals(0, ctx.saveChanges());
        }
    }
}
