package net.gs64.gci.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.gs64.gci.CodePoint;
import net.gs64.gci.GciException;
import net.gs64.gci.GciSession;
import net.gs64.gci.Oop;
import net.gs64.gci.StoneTest;
import net.gs64.gci.mapping.MappingModel.Account;
import net.gs64.gci.mapping.MappingModel.AccountStatus;
import net.gs64.gci.mapping.MappingModel.Address;
import net.gs64.gci.mapping.MappingModel.Holder;
import net.gs64.gci.mapping.MappingModel.Order;
import net.gs64.gci.mapping.SaveModel.AccountWithDateTime;
import net.gs64.gci.mapping.SaveModel.AccountWithOrderRefs;
import net.gs64.gci.mapping.SaveModel.AccountWithReadOnlyName;
import net.gs64.gci.mapping.SaveModel.NestedCollections;
import net.gs64.gci.mapping.SaveModel.OrderWithAccountOop;
import net.gs64.gci.mapping.SaveModel.OrderWithSpecialValues;

/**
 * saveChanges against the classes of {@link MappingFixture} (own session, never committed). Every
 * test works on fresh objects stored under its own key in UserGlobals.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GsSaveChangesIntegrationTest {
    private static final TypeRef<List<Account>> ACCOUNTS = new TypeRef<>() {
    };
    private MappingFixture fixture;

    @BeforeAll
    void setUp() {
        fixture = new MappingFixture();
    }

    @AfterAll
    void tearDown() {
        if (fixture != null) fixture.close();
    }

    GciSession s() {
        return fixture.session;
    }

    GsObjectContext newContext() {
        return new GsObjectContext(s());
    }

    private static String newKey() {
        return "GsSaveTest_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A fresh account (with one order and a dictionary) under a new key; returns the key. */
    private String newAccount() {
        var key = newKey();
        s().execute("""
                | a o addr |
                addr := (UserGlobals at: #GsMapTestAddress) new.
                addr instVarNamed: 'street' put: 'Old Street'; instVarNamed: 'city' put: 'Bonn'.
                a := (UserGlobals at: #GsMapTestAccount) new.
                a instVarNamed: 'accName' put: 'Dora'; instVarNamed: 'amount' put: 10.50s2;
                  instVarNamed: 'postalAddress' put: addr; instVarNamed: 'orderList' put: OrderedCollection new;
                  instVarNamed: 'tags' put: (Set withAll: #('a' 'b')); instVarNamed: 'state' put: #active.
                o := (UserGlobals at: #GsMapTestOrder) new.
                o instVarNamed: 'number' put: 1; instVarNamed: 'total' put: 1.5; instVarNamed: 'account' put: a;
                  instVarNamed: 'lines' put: (KeyValueDictionary new at: 'apple' put: 3; yourself).
                (a instVarNamed: 'orderList') add: o.
                UserGlobals at: #%s put: a.
                true
                """.formatted(key));
        return key;
    }

    private <T> T eval(String source, Class<T> type) {
        return s().evaluate(source, type);
    }

    /** A fresh GsMapTestNested under a new key; returns the expression that answers it. */
    private String newNested() {
        var key = newKey();
        s().execute("""
                | n |
                n := (UserGlobals at: #GsMapTestNested) new.
                n instVarNamed: 'rows' put: (OrderedCollection with: (OrderedCollection with: 1 with: 2) with: (OrderedCollection with: 3));
                  instVarNamed: 'groups' put: (KeyValueDictionary new at: 'a' put: (OrderedCollection with: 'x'); yourself);
                  instVarNamed: 'grid' put: (Array with: #(1 2) copy with: #(3 4) copy).
                UserGlobals at: #%s put: n.
                true
                """.formatted(key));
        return "(UserGlobals at: #" + key + ")";
    }

    private String print(String expr) {
        return eval(expr + " printString", String.class);
    }

    private static String acc(String key) {
        return "(UserGlobals at: #" + key + ")";
    }

    @StoneTest
    void anEnumKeepsTheCaseOfItsStoredText() {
        // The state is a String starting upper case: the enum is written back as a Symbol in that case.
        var acc = acc(newAccount());
        s().execute(acc + " instVarNamed: 'state' put: 'Active'. true");
        try (var ctx = newContext()) {
            var a = ctx.load(acc, Account.class);
            assertEquals(AccountStatus.ACTIVE, a.status);
            a.status = AccountStatus.FROZEN;
            assertEquals(1, ctx.saveChanges());
            assertTrue(eval("(" + acc + " instVarNamed: 'state') == #Frozen", Boolean.class));
        }
    }

    @StoneTest
    void changesOfValuesHeldInTheOopAreSaved() {
        var order = "(" + acc(newAccount()) + " instVarNamed: 'orderList') first";
        try (var ctx = newContext()) {
            var o = ctx.load(order, OrderWithSpecialValues.class);
            assertEquals(0, ctx.saveChanges());
            o.number = 2;
            assertEquals(1, ctx.saveChanges());
            assertEquals(2L, eval(order + " instVarNamed: 'number'", Long.class));
            o.total = 2.25;
            assertEquals(1, ctx.saveChanges());
            assertEquals(2.25, eval(order + " instVarNamed: 'total'", Double.class));
            o.account = Oop.NIL;
            assertEquals(1, ctx.saveChanges());
            assertTrue(eval("(" + order + " instVarNamed: 'account') isNil", Boolean.class));
            o.number = 1L << 62; // a LargeInteger now: the snapshot keeps the box
            assertEquals(1, ctx.saveChanges());
            assertEquals(0, ctx.saveChanges());
            o.number = 3;
            assertEquals(1, ctx.saveChanges());
            assertEquals(3L, eval(order + " instVarNamed: 'number'", Long.class));
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void twoClassesForTheSameObjectKeepTheirOwnInstances() {
        var acc = "UserGlobals at: #" + newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load(acc, Account.class);
            var b = ctx.load(a.oop, AccountWithReadOnlyName.class);
            assertNotSame(a, b);
            assertSame(a, ctx.load(acc, Account.class)); // not a new instance
            assertSame(b, ctx.load(a.oop, AccountWithReadOnlyName.class));
            assertSame(a, ctx.entityFor(a.oop));
            assertEquals(2, ctx.entitiesFor(a.oop).size());
            // Refresh updates both, in one load.
            s().execute("(" + acc + ") instVarNamed: 'amount' put: 77s2");
            assertEquals(2, ctx.refresh(List.of(a.oop)).size());
            assertEquals(0, new BigDecimal(77).compareTo(a.balance));
            assertEquals(0, new BigDecimal(77).compareTo(b.balance));
            // Unsaved changes of either are protected.
            b.balance = new BigDecimal(5);
            var e = assertThrows(GsUnsavedChangesException.class, () -> ctx.refresh(List.of(a.oop)));
            assertEquals(List.of(b), e.entities());
            assertEquals(1, ctx.saveChanges());
            assertEquals(0, new BigDecimal(5).compareTo(eval("(" + acc + ") instVarNamed: 'amount'", BigDecimal.class)));
        }
    }

    @StoneTest
    void anUnsetOopFieldIsSavedAsNil() {
        var key = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, AccountWithOrderRefs.class);
            assertNotEquals(Oop.NIL, a.orders.get(0).account);
            var o = new OrderWithAccountOop();
            o.number = 2;
            o.lines = new Oop(0);
            a.orders.add(o);
            ctx.saveChanges();
            assertEquals("nil nil", eval("| o | o := (UserGlobals at: #" + key + ") instVarNamed: 'orderList'. o size = 2 ifFalse: ['size ' , o size printString] ifTrue: [(o last instVarNamed: 'account') printString , ' ' , (o last instVarNamed: 'lines') printString]", String.class));
        }
    }

    @StoneTest
    void reloadDoesNotSilentlyDropUnsavedChanges() {
        var acc = "UserGlobals at: #" + newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load(acc, Account.class);
            assertSame(a, ctx.load(acc, Account.class)); // unchanged: reloading is fine
            a.balance = BigDecimal.ONE;
            var e = assertThrows(GsUnsavedChangesException.class, () -> ctx.load(acc, Account.class));
            assertEquals(List.of(a), e.entities());
            assertEquals(BigDecimal.ONE, a.balance); // nothing was overwritten
            // Also through a collection, and for an object only reached below the root.
            a.balance = new BigDecimal("10.50");
            a.orders.get(0).lines.put("kiwi", 2L);
            e = assertThrows(GsUnsavedChangesException.class, () -> ctx.load(acc, Account.class));
            assertEquals(List.of(a.orders.get(0)), e.entities());
            // Saved changes are no obstacle; the reload keeps the instances.
            assertEquals(1, ctx.saveChanges());
            var order = a.orders.get(0);
            assertSame(a, ctx.load(acc, Account.class));
            assertSame(order, a.orders.get(0));
            assertEquals(2L, order.lines.get("kiwi"));
        }
    }

    @StoneTest
    void refreshReloadsSeveralObjectsInOneLoad() {
        var first = newAccount();
        var second = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + first, Account.class);
            var b = ctx.load("UserGlobals at: #" + second, Account.class);
            var order = a.orders.get(0);
            var aOop = ctx.oopFor(a);
            var bOop = ctx.oopFor(b);
            s().execute(acc(first) + " instVarNamed: 'accName' put: 'Anna'. " + acc(second) + " instVarNamed: 'accName' put: 'Bert'. ("
                    + acc(first) + " instVarNamed: 'orderList') first instVarNamed: 'number' put: 99. true");
            var calls = s().callCount();
            var refreshed = ctx.refresh(List.of(aOop, bOop, aOop, Oop.fromSmallInteger(1), Oop.NIL));
            var refreshCalls = s().callCount() - calls;
            assertEquals(List.of(a, b), refreshed);
            assertEquals("Anna", a.name);
            assertEquals("Bert", b.name);
            assertSame(order, a.orders.get(0)); // same instances, refreshed below the root too
            assertEquals(99, order.number);
            assertEquals(0, ctx.saveChanges()); // the snapshots are the server's state
            calls = s().callCount();
            ctx.load(aOop, Account.class);
            var oneLoad = s().callCount() - calls;
            assertTrue(refreshCalls >= 1 && refreshCalls <= oneLoad, refreshCalls + " calls for two, " + oneLoad + " for one");
            assertTrue(ctx.refresh(List.of()).isEmpty());
        }
    }

    @StoneTest
    void refreshThroughACollectionRefreshesItsOwner() {
        for (var tracked : new boolean[] {true, false}) {
            var key = newAccount();
            var options = GsLoadOptions.DEFAULT.withTrackChanges(tracked);
            try (var ctx = newContext()) {
                var a = ctx.load("UserGlobals at: #" + key, Account.class, options);
                var orders = s().execute(acc(key) + " instVarNamed: 'orderList'"); // what a listener would watch
                s().execute("(" + acc(key) + " instVarNamed: 'orderList') add: (((UserGlobals at: #GsMapTestOrder) new) instVarNamed: 'number' put: 7; instVarNamed: 'total' put: 1.0; yourself). true");
                assertEquals(List.of(a), ctx.refresh(List.of(orders), options));
                assertEquals(List.of(1, 7), a.orders.stream().map(o -> o.number).toList());
                s().release(orders);
            }
        }
    }

    @StoneTest
    void refreshProtectsUnsavedChanges() {
        var key = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            var oop = ctx.oopFor(a);
            a.name = "local";
            assertEquals(List.of(a), assertThrows(GsUnsavedChangesException.class, () -> ctx.refresh(List.of(oop))).entities());
            assertEquals("local", a.name);
            ctx.refresh(List.of(oop), GsLoadOptions.DEFAULT.withDiscardLocalChanges(true));
            assertEquals("Dora", a.name);
        }
    }

    @StoneTest
    void discardLocalChangesOverwritesThem() {
        var acc = "UserGlobals at: #" + newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load(acc, Account.class);
            a.name = "local";
            a.tags.add("local");
            assertSame(a, ctx.load(acc, Account.class, GsLoadOptions.DEFAULT.withDiscardLocalChanges(true)));
            assertEquals("Dora", a.name);
            assertFalse(a.tags.contains("local"));
            assertEquals(0, ctx.saveChanges()); // the snapshot is the server's state again
        }
    }

    @StoneTest
    void unsavedChangesElsewhereDoNotBlockALoad() {
        var first = newAccount();
        var second = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + first, Account.class);
            a.name = "changed";
            var b = ctx.load("UserGlobals at: #" + second, Account.class); // does not reach a
            assertNotSame(a, b);
            assertEquals("changed", a.name);
            assertEquals(1, ctx.saveChanges());
        }
    }

    @StoneTest
    void nestedCollectionsArePatchedInPlace() {
        var n = newNested();
        try (var ctx = newContext()) {
            var obj = ctx.load(n, NestedCollections.class);
            assertEquals(List.of(List.of(1L, 2L), List.of(3L)), obj.rows);
            assertEquals(List.of("x"), obj.groups.get("a"));
            assertArrayEquals(new Long[] {3L, 4L}, obj.grid[1]);
            var row1 = eval("(" + n + " instVarNamed: 'rows') first asOop", Long.class);
            var groupA = eval("((" + n + " instVarNamed: 'groups') at: 'a') asOop", Long.class);
            var grid2 = eval("(" + n + " instVarNamed: 'grid') last asOop", Long.class);
            assertEquals(0, ctx.saveChanges());

            obj.rows.get(0).add(9L);
            obj.groups.get("a").add("y");
            obj.grid[1][0] = 30L;
            assertEquals(3, ctx.saveChanges()); // three inner collections, the outer ones are unchanged
            assertEquals(0, ctx.saveChanges());
            assertEquals("anOrderedCollection( anOrderedCollection( 1, 2, 9), anOrderedCollection( 3))", print("(" + n + " instVarNamed: 'rows')"));
            assertEquals("anOrderedCollection( 'x', 'y')", print("((" + n + " instVarNamed: 'groups') at: 'a')"));
            assertEquals("anArray( anArray( 1, 2), anArray( 30, 4))", print("(" + n + " instVarNamed: 'grid')"));
            // Changed in place: still the same GemStone collections.
            assertEquals(row1, eval("(" + n + " instVarNamed: 'rows') first asOop", Long.class));
            assertEquals(groupA, eval("((" + n + " instVarNamed: 'groups') at: 'a') asOop", Long.class));
            assertEquals(grid2, eval("(" + n + " instVarNamed: 'grid') last asOop", Long.class));
        }
    }

    @StoneTest
    void newAndMovedInnerCollections() {
        var n = newNested();
        try (var ctx = newContext()) {
            var obj = ctx.load(n, NestedCollections.class);
            var row2 = eval("(" + n + " instVarNamed: 'rows') last asOop", Long.class);
            var first = obj.rows.get(0);
            obj.rows.remove(0); // the second row moves to the front
            obj.rows.add(new ArrayList<>(List.of(7L, 8L))); // a new row
            obj.groups.put("b", new ArrayList<>(List.of("z"))); // a new group
            obj.grid = new Long[][] {{5L}, {}}; // a new outer array with new inner arrays
            assertTrue(ctx.saveChanges() > 0);
            assertEquals(0, ctx.saveChanges());
            assertEquals("anOrderedCollection( anOrderedCollection( 3), anOrderedCollection( 7, 8))", print("(" + n + " instVarNamed: 'rows')"));
            assertEquals(row2, eval("(" + n + " instVarNamed: 'rows') first asOop", Long.class)); // moved, not copied
            assertEquals("anOrderedCollection( 'z')", print("((" + n + " instVarNamed: 'groups') at: 'b')"));
            assertEquals("anArray( anArray( 5), anArray( ))", print("(" + n + " instVarNamed: 'grid')"));
            // The new inner collections are tracked as well; the removed one is not written any more.
            first.add(100L);
            obj.rows.get(1).add(9L);
            obj.groups.get("b").add("zz");
            obj.grid[1] = new Long[] {6L};
            assertEquals(3, ctx.saveChanges()); // rows[2], groups['b'] and grid itself
            assertEquals("anOrderedCollection( anOrderedCollection( 3), anOrderedCollection( 7, 8, 9))", print("(" + n + " instVarNamed: 'rows')"));
            assertEquals("anOrderedCollection( 'z', 'zz')", print("((" + n + " instVarNamed: 'groups') at: 'b')"));
            assertEquals("anArray( anArray( 5), anArray( 6))", print("(" + n + " instVarNamed: 'grid')"));
        }
        try (var fresh = newContext()) {
            var again = fresh.load(n, NestedCollections.class);
            assertEquals(List.of(List.of(3L), List.of(7L, 8L, 9L)), again.rows);
            assertEquals(List.of("z", "zz"), again.groups.get("b"));
        }
    }

    @StoneTest
    void newObjectWithNestedCollections() {
        try (var ctx = newContext()) {
            var obj = new NestedCollections();
            obj.rows = new ArrayList<>(List.of(new ArrayList<>(List.of(1L)), new ArrayList<>(List.of(2L, 3L))));
            obj.groups = new LinkedHashMap<>(Map.of("k", new ArrayList<>(List.of("v", "w"))));
            obj.grid = new Long[][] {{1L, 2L}};
            var oop = ctx.add(obj);
            var n = "(Object _objectForOop: " + oop.value() + ")";
            assertEquals("anOrderedCollection( anOrderedCollection( 1), anOrderedCollection( 2, 3))", print("(" + n + " instVarNamed: 'rows')"));
            assertEquals("anOrderedCollection( 'v', 'w')", print("((" + n + " instVarNamed: 'groups') at: 'k')"));
            assertEquals("anArray( anArray( 1, 2))", print("(" + n + " instVarNamed: 'grid')"));
            obj.rows.get(1).remove(Long.valueOf(2));
            assertEquals(1, ctx.saveChanges());
            assertEquals("anOrderedCollection( 3)", print("(" + n + " instVarNamed: 'rows') last"));
        }
    }

    @StoneTest
    void unchangedObjectsWriteNothing() {
        try (var ctx = newContext()) {
            ctx.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS);
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void changedScalarsAreWrittenOnce() {
        var key = newAccount();
        var acc = acc(key);
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            a.name = "Dora Neu";
            a.balance = new BigDecimal("99.95");
            a.status = AccountStatus.FROZEN;
            a.opened = LocalDate.of(2025, 12, 24);
            assertEquals(4, ctx.saveChanges());
            assertEquals(0, ctx.saveChanges()); // snapshots updated
            assertEquals("Dora Neu", eval(acc + " instVarNamed: 'accName'", String.class));
            assertEquals("String", eval("(" + acc + " instVarNamed: 'accName') class name", String.class));
            assertEquals(new BigDecimal("99.95"), eval(acc + " instVarNamed: 'amount'", BigDecimal.class));
            assertTrue(eval("(" + acc + " instVarNamed: 'state') == #frozen", Boolean.class)); // Symbol, Smalltalk case
            assertEquals(LocalDate.of(2025, 12, 24), eval(acc + " instVarNamed: 'opened'", LocalDate.class));
        }
        try (var fresh = newContext()) {
            var again = fresh.load("UserGlobals at: #" + key, Account.class);
            assertEquals(AccountStatus.FROZEN, again.status);
            assertEquals(new BigDecimal("99.95"), again.balance);
        }
    }

    @StoneTest
    void newObjectsAndReferencesAreCreated() {
        var key = newAccount();
        var acc = acc(key);
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            a.address = new Address();
            a.address.street = "New Street 5";
            a.address.city = "Köln";
            var order = new Order();
            order.number = 2;
            order.total = 3.25;
            order.account = a;
            order.lines = new LinkedHashMap<>(Map.of("pear", 7L));
            a.orders.add(order);
            assertTrue(ctx.saveChanges() > 0);
            var orderOop = ctx.oopFor(order);
            assertNotNull(orderOop);
            assertNotNull(ctx.oopFor(a.address));
            assertEquals("New Street 5", eval("(" + acc + " instVarNamed: 'postalAddress') instVarNamed: 'street'", String.class));
            assertEquals("Köln", eval("(" + acc + " instVarNamed: 'postalAddress') instVarNamed: 'city'", String.class));
            assertEquals(2L, eval("(" + acc + " instVarNamed: 'orderList') size", Long.class));
            assertTrue(eval("((" + acc + " instVarNamed: 'orderList') last instVarNamed: 'account') == " + acc, Boolean.class)); // cycle
            assertEquals(7L, eval("((" + acc + " instVarNamed: 'orderList') last instVarNamed: 'lines') at: 'pear'", Long.class));
            assertEquals("KeyValueDictionary", eval("((" + acc + " instVarNamed: 'orderList') last instVarNamed: 'lines') class name", String.class));
            assertTrue(eval("(" + acc + " instVarNamed: 'orderList') last == (Object _objectForOop: " + orderOop.value() + ")", Boolean.class));
        }
    }

    @StoneTest
    void changedCollectionsAreRewrittenInPlace() {
        var key = newAccount();
        var acc = acc(key);
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            var tagsBefore = eval("(" + acc + " instVarNamed: 'tags') asOop", Long.class);
            a.tags.remove("a");
            a.tags.add("c");
            a.orders.get(0).lines.put("apple", 4L);
            a.orders.get(0).lines.put("plum", 1L);
            assertEquals(2, ctx.saveChanges()); // two collections rewritten
            assertEquals(tagsBefore, eval("(" + acc + " instVarNamed: 'tags') asOop", Long.class)); // same Set object
            assertEquals("b c", eval("((" + acc + " instVarNamed: 'tags') asSortedCollection: [:x :y | x <= y]) inject: '' into: [:s :e | s isEmpty ifTrue: [e] ifFalse: [s, ' ', e]]", String.class));
            assertEquals(4L, eval("((" + acc + " instVarNamed: 'orderList') first instVarNamed: 'lines') at: 'apple'", Long.class));
            assertEquals(1L, eval("((" + acc + " instVarNamed: 'orderList') first instVarNamed: 'lines') at: 'plum'", Long.class));
            a.orders.remove(0);
            assertEquals(1, ctx.saveChanges());
            assertEquals(0L, eval("(" + acc + " instVarNamed: 'orderList') size", Long.class));
        }
    }

    @StoneTest
    void collectionsCanBecomeNilAndBeCreated() {
        var key = newAccount();
        try (var ctx = newContext()) {
            var order = ctx.load("UserGlobals at: #" + key, Account.class).orders.get(0);
            var lines = "(" + acc(key) + " instVarNamed: 'orderList') first instVarNamed: 'lines'";
            order.lines = null;
            assertEquals(1, ctx.saveChanges());
            assertTrue(eval("(" + lines + ") isNil", Boolean.class));
            order.lines = new LinkedHashMap<>(Map.of("kiwi", 9L));
            assertEquals(1, ctx.saveChanges());
            assertEquals(9L, eval("(" + lines + ") at: 'kiwi'", Long.class));
        }
    }

    @StoneTest
    void symbolKeysStaySymbols() {
        s().execute("""
                | h |
                h := (UserGlobals at: #GsMapTestHolder) new.
                h instVarNamed: 'symbols' put: (SymbolDictionary new at: #home put: (UserGlobals at: #GsMapTestAddress) new; yourself);
                  instVarNamed: 'big' put: 1; instVarNamed: 'when' put: DateAndTime now.
                UserGlobals at: #GsSaveTestHolder put: h.
                true
                """);
        try (var ctx = newContext()) {
            var h = ctx.load("UserGlobals at: #GsSaveTestHolder", Holder.class);
            var office = new Address();
            office.city = "Berlin";
            h.bySymbol.put("office", office);
            assertEquals(3, ctx.saveChanges()); // dictionary patched + street and city of the new Address
            var dict = "((UserGlobals at: #GsSaveTestHolder) instVarNamed: 'symbols')";
            assertEquals("SymbolDictionary", eval(dict + " class name", String.class));
            assertTrue(eval(dict + " keys allSatisfy: [:k | k isSymbol]", Boolean.class));
            assertEquals("Berlin", eval("(" + dict + " at: #office) instVarNamed: 'city'", String.class));
            assertEquals(2L, eval(dict + " size", Long.class));
        }
    }

    @StoneTest
    void readOnlyFieldsAndOneWayConvertersAreRespected() {
        var key = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, AccountWithReadOnlyName.class);
            a.name = "ignored";
            assertEquals(0, ctx.saveChanges());
            a.balance = BigDecimal.ONE;
            assertEquals(1, ctx.saveChanges());
            assertEquals("Dora", eval(acc(key) + " instVarNamed: 'accName'", String.class));
        }
        try (var hctx = newContext()) {
            var h = hctx.load("UserGlobals at: #GsMapTestHolderObj", Holder.class);
            h.code = "XYZ"; // UpperCaseConverter cannot write
            assertThrows(UnsupportedOperationException.class, hctx::saveChanges);
        }
    }

    @StoneTest
    void addCreatesAStandaloneObject() {
        try (var ctx = newContext()) {
            var address = new Address();
            address.street = "Solo 1";
            address.city = "Hamm";
            var oop = ctx.add(address);
            assertEquals("GsMapTestAddress", eval("(Object _objectForOop: " + oop.value() + ") class name", String.class));
            assertEquals("Hamm", eval("(Object _objectForOop: " + oop.value() + ") instVarNamed: 'city'", String.class));
            assertEquals(oop, ctx.add(address)); // already tracked
            assertEquals(0, ctx.saveChanges());
        }
    }

    @StoneTest
    void closeReleasesTrackedObjects() {
        var key = newAccount();
        try (var warm = newContext()) {
            warm.load("UserGlobals at: #" + key, Account.class);
        }
        var before = exportSetSize();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            assertTrue(exportSetSize() > before); // tracked objects are held
            a.address = new Address();
            a.address.city = "Essen";
            var o = new Order();
            o.number = 9;
            a.orders.add(o);
            ctx.saveChanges();
        }
        assertEquals(before, exportSetSize());
    }

    private long exportSetSize() {
        return eval("System hiddenSetSize: (System HiddenSetSpecifiers indexOf: 'PureExportSet')", Long.class);
    }

    // ---- Batched writes ----------------------------------------------------------------------

    static Stream<Arguments> scalarValues() {
        var micros = OffsetDateTime.of(2026, 11, 8, 10, 30, 15, 123_456_000, ZoneOffset.ofHours(2));
        return Stream.of(
                Arguments.of(new BigDecimal("12.34"), new BigDecimal("12.34")),
                Arguments.of(new BigDecimal("-0.0001"), new BigDecimal("-0.0001")),
                Arguments.of(new BigDecimal("123456789012345678901234.5678"), new BigDecimal("123456789012345678901234.5678")),
                Arguments.of(new BigDecimal("1e-40"), new BigDecimal("1e-40")),
                Arguments.of(7L, 7L),
                Arguments.of(42, 42L),
                Arguments.of(Long.MAX_VALUE, Long.MAX_VALUE), // LargeInteger
                Arguments.of(Long.MIN_VALUE, Long.MIN_VALUE),
                Arguments.of(BigInteger.TEN.pow(40), BigInteger.TEN.pow(40)),
                Arguments.of(BigInteger.valueOf(7).pow(50).negate(), BigInteger.valueOf(7).pow(50).negate()),
                Arguments.of(1.5, 1.5),
                Arguments.of(1e100, 1e100), // Float object
                Arguments.of(-1e-300, -1e-300),
                Arguments.of(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
                Arguments.of("plain", "plain"),
                Arguments.of("Grüße 😀", "Grüße 😀"),
                Arguments.of(true, true),
                Arguments.of(new CodePoint('ß'), new CodePoint('ß')),
                Arguments.of(LocalDate.of(1899, 12, 31), LocalDate.of(1899, 12, 31)),
                Arguments.of(LocalTime.of(23, 59, 58, 999_000_000), LocalTime.of(23, 59, 58, 999_000_000)),
                Arguments.of(micros, micros),
                Arguments.of(OffsetDateTime.of(1950, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5)), OffsetDateTime.of(1950, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5))),
                Arguments.of(Instant.parse("2026-01-02T03:04:05.678901Z"), OffsetDateTime.parse("2026-01-02T03:04:05.678901Z")),
                Arguments.of(new byte[] {0, 1, (byte) 255}, new byte[] {0, 1, (byte) 255}));
    }

    @ParameterizedTest
    @MethodSource("scalarValues")
    void scalarValuesSurviveABatchedSave(Object written, Object expected) {
        var key = newAccount();
        try (var ctx = newContext()) {
            ctx.load("UserGlobals at: #" + key, Account.class).extra = written;
            assertEquals(1, ctx.saveChanges());
            assertEquals(0, ctx.saveChanges());
        }
        try (var fresh = newContext()) {
            var read = fresh.load("UserGlobals at: #" + key, Account.class).extra;
            if (expected instanceof byte[] b) assertArrayEquals(b, (byte[]) read);
            else assertEquals(expected, read);
        }
    }

    @StoneTest
    void localDateTimeLoadsAsLocalTime() {
        for (var written : new LocalDateTime[] {LocalDateTime.of(2026, 3, 15, 0, 0), LocalDateTime.of(2026, 12, 24, 18, 30, 0, 123_456_000)}) {
            var key = newAccount();
            try (var ctx = newContext()) {
                ctx.load("UserGlobals at: #" + key, AccountWithDateTime.class).extra = written;
                assertEquals(1, ctx.saveChanges());
            }
            try (var fresh = newContext()) {
                var a = fresh.load("UserGlobals at: #" + key, AccountWithDateTime.class);
                assertEquals(written, a.extra);
                assertEquals(0, fresh.saveChanges());
            }
        }
    }

    @StoneTest
    void stringsGetTheSameClassesAsNewString() {
        var key = newAccount();
        var acc = acc(key);
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            for (var text : new String[] {"plain", "Grüße", "Grüße 😀", ""}) {
                a.name = text;
                ctx.saveChanges();
                var expected = s().fetchClass(s().newString(text));
                assertEquals(eval("(Object _objectForOop: " + expected.value() + ") name", String.class),
                        eval("(" + acc + " instVarNamed: 'accName') class name", String.class));
                assertEquals(text, eval(acc + " instVarNamed: 'accName'", String.class));
            }
            a.status = AccountStatus.CLOSED;
            ctx.saveChanges();
            assertTrue(eval("(" + acc + " instVarNamed: 'state') == #closed", Boolean.class));
        }
    }

    @StoneTest
    void manyChangesCostAFewRoundTrips() {
        var key = newKey();
        s().execute("""
                | all |
                all := OrderedCollection new.
                1 to: 300 do: [:i | | a |
                  a := (UserGlobals at: #GsMapTestAccount) new.
                  a instVarNamed: 'accName' put: 'acc', i printString; instVarNamed: 'amount' put: (i asScaledDecimal: 2);
                    instVarNamed: 'orderList' put: OrderedCollection new; instVarNamed: 'tags' put: Set new; instVarNamed: 'state' put: #active.
                  all add: a].
                UserGlobals at: #%s put: all asArray.
                true
                """.formatted(key));
        var all = acc(key);
        try (var ctx = newContext()) {
            var accounts = ctx.load("UserGlobals at: #" + key, Account[].class);
            assertEquals(300, accounts.length);
            var before = s().callCount();
            for (var a : accounts) {
                a.balance = a.balance.add(new BigDecimal("0.01"));
                a.name = a.name + " Müller";
                a.opened = LocalDate.of(2026, 1, 1);
                a.tags.add("vip");
                var o = new Order();
                o.number = 1;
                o.total = 1e100;
                o.account = a;
                o.lines = new LinkedHashMap<>(Map.of("x", Long.MAX_VALUE));
                a.orders.add(o);
            }
            var stores = ctx.saveChanges();
            var calls = s().callCount() - before;
            assertEquals(300 * 9, stores); // 3 scalars + 2 collections per account, 4 instVars per new order
            assertTrue(calls >= 1 && calls <= 15, "calls " + calls);
            assertEquals(300L, eval("(" + all + " select: [:a | (a instVarNamed: 'accName') endsWith: ' Müller']) size", Long.class));
            assertEquals(new BigDecimal("1.01"), eval("(" + all + " at: 1) instVarNamed: 'amount'", BigDecimal.class));
            assertTrue(eval("(" + all + " allSatisfy: [:a | ((a instVarNamed: 'orderList') first instVarNamed: 'account') == a])", Boolean.class));
            assertEquals(Long.MAX_VALUE, eval("((((" + all + " at: 300) instVarNamed: 'orderList') first instVarNamed: 'lines') at: 'x')", Long.class));
            var orderOop = ctx.oopFor(accounts[299].orders.get(0));
            assertTrue(eval("(((" + all + " at: 300) instVarNamed: 'orderList') first) == (Object _objectForOop: " + orderOop.value() + ")", Boolean.class));
            assertEquals(0, ctx.saveChanges());
            // A second round: only the batch itself (free OOPs, store traversal, execute, fetch the
            // answer, retain the new objects, release the traversal objects).
            before = s().callCount();
            for (var a : accounts) {
                var o = new Order();
                o.number = 2;
                o.lines = new LinkedHashMap<>(Map.of("y", 1L));
                a.orders.add(o);
            }
            ctx.saveChanges();
            var second = s().callCount() - before;
            assertTrue(second >= 6 && second <= 7, "calls " + second); // C#: 6 (size and contents of the answer count once each here)
            assertEquals(600L, eval(all + " inject: 0 into: [:sum :a | sum + (a instVarNamed: 'orderList') size]", Long.class));
        }
    }

    @StoneTest
    void aFailedBatchLeavesTheContextUnchanged() {
        var key = newAccount();
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            s().execute(acc(key) + " immediateInvariant. true");
            var address = new Address();
            address.city = "Trier";
            a.address = address;
            a.balance = new BigDecimal(5);
            assertThrows(GciException.class, ctx::saveChanges);
            assertNull(ctx.oopFor(address)); // not tracked: the batch failed
            assertThrows(GciException.class, ctx::saveChanges); // the changes are still pending
            assertEquals(new BigDecimal("10.50"), eval(acc(key) + " instVarNamed: 'amount'", BigDecimal.class));
        }
    }

    // ---- Incremental collection updates ------------------------------------------------------

    /** A fresh holder whose 'items' is a {@code collection} of 'a' 'b' 'c' 'd'. */
    private String newHolder(String collection) {
        var key = newKey();
        s().execute("""
                | h items |
                items := %s.
                #('a' 'b' 'c' 'd') do: [:e | items add: e copy].
                h := (UserGlobals at: #GsMapTestHolder) new.
                h instVarNamed: 'items' put: items; instVarNamed: 'idSet' put: (IdentitySet withAll: (1 to: 10));
                  instVarNamed: 'big' put: 0; instVarNamed: 'when' put: DateAndTime now.
                UserGlobals at: #%s put: h.
                true
                """.formatted(collection, key));
        return key;
    }

    static Stream<Arguments> listKinds() {
        return Stream.of(Arguments.of("OrderedCollection new", true), Arguments.of("SortedCollection sortBlock: [:x :y | x <= y]", false),
                Arguments.of("Bag new", false));
    }

    @ParameterizedTest
    @MethodSource("listKinds")
    void listChangesArePatched(String collection, boolean ordered) {
        var key = newHolder(collection);
        var items = "(" + acc(key) + " instVarNamed: 'items')";
        try (var ctx = newContext()) {
            var h = ctx.load("UserGlobals at: #" + key, Holder.class);
            var before = ordered ? List.of("a", "b", "c", "d") : h.items.stream().map(Object::toString).toList(); // Bag: any order
            assertEquals(List.of("a", "b", "c", "d"), before.stream().sorted().toList());
            var keptOop = eval("(" + items + " detect: [:e | e = '" + before.get(2) + "']) asOop", Long.class);
            var list = h.items;
            list.remove(1); // remove an element in the middle
            list.add(1, "x"); // and put a new one there
            list.add(0, "Ä"); // non-ASCII at the front
            list.add("z"); // append
            assertEquals(1, ctx.saveChanges());
            assertEquals(0, ctx.saveChanges());
            var server = eval(items + " inject: '' into: [:acc :e | acc , e , ' ']", String.class).strip();
            var expected = list.stream().map(Object::toString).toList();
            if (ordered) assertEquals(String.join(" ", expected), server);
            else assertEquals(expected.stream().sorted().toList(), Arrays.stream(server.split(" ")).sorted().toList());
            assertTrue(eval("(" + items + " detect: [:e | e = '" + before.get(2) + "']) asOop = " + keptOop, Boolean.class)); // untouched: same object
        }
    }

    @StoneTest
    void arrayChangesKeepTheArray() {
        var key = newKey();
        s().execute("| h | h := (UserGlobals at: #GsMapTestHolder) new. h instVarNamed: 'items' put: #(1 2 3) copy; instVarNamed: 'big' put: 0; instVarNamed: 'when' put: DateAndTime now. UserGlobals at: #" + key + " put: h. true");
        var items = "(" + acc(key) + " instVarNamed: 'items')";
        var arrayOop = eval(items + " asOop", Long.class);
        try (var ctx = newContext()) {
            var h = ctx.load("UserGlobals at: #" + key, Holder.class);
            h.items.set(1, "two"); // same size
            ctx.saveChanges();
            assertEquals("1 two 3", eval(items + " inject: '' into: [:acc :e | acc , e asString , ' ']", String.class).strip());
            h.items.remove(0); // smaller
            h.items.addAll(List.of(4L, 5L, 6L));
            ctx.saveChanges();
            assertEquals("two 3 4 5 6", eval(items + " inject: '' into: [:acc :e | acc , e asString , ' ']", String.class).strip());
            assertEquals(arrayOop, eval(items + " asOop", Long.class));
            assertEquals("Array", eval(items + " class name", String.class));
        }
    }

    @StoneTest
    void serverSideChangesToOtherElementsSurvive() {
        // A collection is not rewritten as a whole: elements the context did not touch, and even
        // elements added on the server after loading, stay.
        var key = newAccount();
        var acc = acc(key);
        try (var ctx = newContext()) {
            var a = ctx.load("UserGlobals at: #" + key, Account.class);
            s().execute("""
                    %1$s instVarNamed: 'tags' put: ((%1$s instVarNamed: 'tags') add: 'server'; yourself).
                    ((%1$s instVarNamed: 'orderList') first instVarNamed: 'lines') at: 'server' put: 1.
                    (%1$s instVarNamed: 'orderList') add: (UserGlobals at: #GsMapTestOrder) new.
                    true
                    """.formatted(acc));
            a.tags.add("client");
            a.tags.remove("a");
            a.orders.get(0).lines.put("apple", 30L);
            a.orders.get(0).lines.put("kiwi", 2L);
            var o = new Order();
            o.number = 7;
            a.orders.add(0, o);
            assertEquals(3, ctx.saveChanges() - 4); // 3 collections; 4 instVars of the new order
            assertEquals("b client server", eval("((" + acc + " instVarNamed: 'tags') asSortedCollection: [:x :y | x <= y]) inject: '' into: [:s :e | s isEmpty ifTrue: [e] ifFalse: [s, ' ', e]]", String.class));
            var lines = "(((" + acc + " instVarNamed: 'orderList') at: 2) instVarNamed: 'lines')";
            assertEquals(30L, eval(lines + " at: 'apple'", Long.class));
            assertEquals(2L, eval(lines + " at: 'kiwi'", Long.class));
            assertEquals(1L, eval(lines + " at: 'server'", Long.class));
            assertEquals(3L, eval("(" + acc + " instVarNamed: 'orderList') size", Long.class)); // new, loaded, server-added
            assertEquals(7L, eval("(" + acc + " instVarNamed: 'orderList') first instVarNamed: 'number'", Long.class));
        }
    }

    @StoneTest
    void dictionaryAndIdentitySetChangesArePatched() {
        var key = newHolder("OrderedCollection new");
        var holder = acc(key);
        try (var ctx = newContext()) {
            var h = ctx.load("UserGlobals at: #" + key, Holder.class);
            h.idSet.remove(3L);
            h.idSet.add(5000L);
            h.idSet.add(1L << 62); // LargeInteger
            assertEquals(1, ctx.saveChanges());
            assertEquals(11L, eval("(" + holder + " instVarNamed: 'idSet') size", Long.class));
            assertTrue(eval("((" + holder + " instVarNamed: 'idSet') includes: 5000) and: [((" + holder + " instVarNamed: 'idSet') includes: 3) not]", Boolean.class));
            assertTrue(eval("(" + holder + " instVarNamed: 'idSet') anySatisfy: [:x | x = (2 raisedTo: 62)]", Boolean.class));
            assertEquals("IdentitySet", eval("(" + holder + " instVarNamed: 'idSet') class name", String.class));
        }
        try (var order = newContext()) {
            var accountKey = newAccount();
            var lines = "((" + acc(accountKey) + " instVarNamed: 'orderList') first instVarNamed: 'lines')";
            var dictOop = eval(lines + " asOop", Long.class);
            var o = order.load("UserGlobals at: #" + accountKey, Account.class).orders.get(0);
            o.lines.remove("apple");
            o.lines.put("pear", 4L);
            assertEquals(1, order.saveChanges());
            assertEquals(dictOop, eval(lines + " asOop", Long.class));
            assertEquals(1L, eval(lines + " size", Long.class));
            assertEquals(4L, eval(lines + " at: 'pear'", Long.class));
            o.lines.clear();
            assertEquals(1, order.saveChanges());
            assertEquals(0L, eval(lines + " size", Long.class));
        }
    }
}
