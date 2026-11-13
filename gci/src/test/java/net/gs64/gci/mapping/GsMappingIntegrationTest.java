package net.gs64.gci.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.gs64.gci.GciSession;
import net.gs64.gci.StoneTest;
import net.gs64.gci.mapping.MappingModel.Account;
import net.gs64.gci.mapping.MappingModel.AccountStatus;
import net.gs64.gci.mapping.MappingModel.AddressWithTypo;
import net.gs64.gci.mapping.MappingModel.Holder;
import net.gs64.gci.mapping.MappingModel.Order;
import net.gs64.gci.mapping.MappingModel.OrderWithAddressAsAccount;
import net.gs64.gci.mapping.MappingModel.OrderWithWrongKind;
import net.gs64.gci.mapping.MappingModel.Phantom;
import net.gs64.gci.mapping.MappingModel.SpecialAccount;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GsMappingIntegrationTest {
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

    /** The context under test: mapping metadata by reflection here. */
    GsObjectContext newContext() {
        return new GsObjectContext(s());
    }

    @StoneTest
    void loadsGraphWithRenamedInstVarsSubclassesAndCycles() {
        var ctx = newContext();
        var accounts = ctx.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS);
        assertEquals(3, accounts.size());
        var alice = accounts.get(0);
        var bob = accounts.get(1);
        var carol = accounts.get(2);

        assertEquals("Alice", alice.name);
        assertEquals(new BigDecimal("1234.56"), alice.balance);
        assertEquals("Hauptstraße 1", alice.address.street);
        assertEquals("Köln", alice.address.city);
        assertEquals(AccountStatus.ACTIVE, alice.status);
        assertEquals(LocalDate.of(2021, 3, 15), alice.opened);
        assertEquals(42L, alice.extra);
        assertEquals(List.of("eu", "vip"), alice.tags.stream().sorted().toList());
        assertNotNull(alice.oop);

        assertEquals(2, alice.orders.size());
        assertEquals(1, alice.orders.get(0).number);
        assertEquals(19.5, alice.orders.get(0).total);
        assertSame(alice, alice.orders.get(0).account); // cycle → same instance
        assertEquals(3L, alice.orders.get(0).lines.get("apple"));
        assertEquals(5L, alice.orders.get(0).lines.get("pear"));
        assertNull(alice.orders.get(1).lines);

        assertSame(alice.address, bob.address); // shared reference → same instance
        assertNull(bob.opened);
        assertEquals("text", bob.extra);
        assertEquals(AccountStatus.FROZEN, bob.status);
        assertTrue(bob.tags.isEmpty());

        var special = assertInstanceOf(SpecialAccount.class, carol); // GemStone subclass → Java subclass
        assertEquals(500, special.bonus);
        assertEquals(new BigDecimal("99999999999999999999.99"), special.balance);
        assertEquals(AccountStatus.CLOSED, special.status);
        assertEquals(alice.oop, ctx.oopFor(alice));
    }

    @StoneTest
    void depthIsOnlyAPerformanceSetting() {
        var shallow = newContext().load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS, GsLoadOptions.DEFAULT.withDepth(1));
        var deep = newContext().load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS, GsLoadOptions.DEFAULT.withDepth(10));
        assertEquals(deep.stream().map(a -> a.name).toList(), shallow.stream().map(a -> a.name).toList());
        assertEquals("Köln", shallow.get(0).address.city);
        assertSame(shallow.get(0), shallow.get(0).orders.get(1).account);
        assertEquals(5L, shallow.get(0).orders.get(0).lines.get("pear"));
    }

    @StoneTest
    void loadsCollectionsDictionariesConvertersAndServerConvertedValues() {
        var h = newContext().load("UserGlobals at: #GsMapTestHolderObj", Holder.class);
        assertEquals(Arrays.asList(1L, "two", 3.5, null), h.items);
        assertEquals(2000, h.idSet.size()); // NSC read directly
        assertEquals(LongStream.rangeClosed(1, 2000).boxed().collect(Collectors.toSet()), h.idSet);
        assertEquals(List.of("x", "x", "y"), h.bag.stream().sorted().toList()); // Bag: flattened on the server
        assertEquals("Köln", h.bySymbol.get("home").city); // Symbol keys → string
        assertEquals("Portland", h.bySymbol.get("work").city);
        assertEquals("ABC", h.code);
        assertEquals(BigInteger.TWO.pow(100), h.big);
        assertEquals(OffsetDateTime.of(2024, 2, 29, 12, 30, 0, 0, ZoneOffset.UTC), h.when);
    }

    @StoneTest
    void serverConvertedValuesAreBatched() {
        try (var warm = newContext()) {
            warm.load("UserGlobals at: #GsMapTestMany", Account[].class);
        }
        var before = s().callCount();
        try (var ctx = newContext()) {
            var many = ctx.load("UserGlobals at: #GsMapTestMany", Account[].class, GsLoadOptions.DEFAULT.withTrackChanges(false));
            var calls = s().callCount() - before;
            assertEquals(3000, many.length);
            assertEquals(new BigDecimal("1234.00"), many[1233].balance);
            assertTrue(calls >= 1 && calls <= 200, "calls " + calls); // C#: 75 (12068 with one conversion per value)
        }
    }

    @StoneTest
    void manyObjectsAcrossSeveralBuffers() {
        try (var ctx = newContext()) {
            var many = ctx.load("UserGlobals at: #GsMapTestMany", Account[].class, GsLoadOptions.DEFAULT.withBufferSize(16 * 1024));
            assertEquals(3000, many.length);
            assertEquals("acc3000", many[2999].name);
            assertEquals(new BigDecimal("1500.00"), many[1499].balance);
            for (var a : many) assertSame(many[0].address, a.address);
        }
    }

    @StoneTest
    void reloadRefreshesTheSameInstance() {
        var ctx = newContext();
        var first = ctx.load("UserGlobals at: #GsMapTestOrderNoLines", Order.class);
        s().execute("(UserGlobals at: #GsMapTestOrderNoLines) instVarNamed: 'total' put: 8.5");
        try {
            var second = ctx.load("UserGlobals at: #GsMapTestOrderNoLines", Order.class);
            assertSame(first, second);
            assertEquals(8.5, second.total);
        } finally {
            s().execute("(UserGlobals at: #GsMapTestOrderNoLines) instVarNamed: 'total' put: 7.25");
        }
    }

    @StoneTest
    void validationListsAllProblemsWithSuggestions() {
        var ctx = newContext();
        var e = assertThrows(GsMappingException.class, () -> ctx.validate(AddressWithTypo.class));
        assertEquals(2, e.problems().size());
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("'stret'") && p.contains("did you mean 'street'")));
        assertTrue(e.problems().stream().anyMatch(p -> p.contains("'zip'") && p.contains("has: street, city")));
        var missing = assertThrows(GsMappingException.class, () -> ctx.validate(Phantom.class));
        assertTrue(missing.getMessage().contains("GsMapTestNoSuchClass"));
        ctx.validate(Account.class); // the good mapping passes
    }

    @StoneTest
    void dataThatDoesNotFitTheMappingIsReported() {
        var ctx = newContext();
        var kind = assertThrows(GsMappingException.class,
                () -> ctx.load("((UserGlobals at: #GsMapTestAccounts) first instVarNamed: 'orderList') first", OrderWithWrongKind.class));
        assertTrue(kind.getMessage().contains("KeyValueDictionary"), kind.getMessage());
        var cls = assertThrows(GsMappingException.class, () -> ctx.load("UserGlobals at: #GsMapTestOrderNoLines", OrderWithAddressAsAccount.class));
        assertTrue(cls.getMessage().contains("expected a GsMapTestAddress") && cls.getMessage().contains("GsMapTestAccount"), cls.getMessage());
        // nil into a primitive field (Order.number is an int)
        s().execute("(UserGlobals at: #GsMapTestOrderNoLines) instVarNamed: 'number' put: nil");
        try {
            var nil = assertThrows(GsMappingException.class, () -> ctx.load("UserGlobals at: #GsMapTestOrderNoLines", Order.class));
            assertTrue(nil.getMessage().contains("Order.number"), nil.getMessage());
        } finally {
            s().execute("(UserGlobals at: #GsMapTestOrderNoLines) instVarNamed: 'number' put: 2");
        }
    }

    private static String describe(List<Account> accounts) {
        return accounts.stream().map(a -> a.name + " " + a.balance + " " + (a.address == null ? null : a.address.city) + " " + a.status + " "
                + a.opened + " " + a.extra + " " + a.tags.stream().sorted().toList() + " "
                + a.orders.stream().map(o -> o.number + ":" + o.total + ":" + (o.account == null ? null : o.account.name) + ":"
                        + (o.lines == null ? "-" : new java.util.TreeMap<>(o.lines).toString())).collect(Collectors.joining(",")))
                .collect(Collectors.joining("; "));
    }

    @StoneTest
    void clampingLoadsTheSameWithLessTraffic() {
        // Alice has an unmapped instance variable with an Array of 1000 slots: with clamping the
        // traversal does not follow it; the loaded objects are the same.
        try (var clamped = newContext(); var plain = newContext()) {
            var withClamp = describe(clamped.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS));
            var clampedReports = clamped.lastLoadReports;
            var without = describe(plain.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS, GsLoadOptions.DEFAULT.withClamp(false)));
            assertEquals(without, withClamp);
            assertTrue(clampedReports < plain.lastLoadReports, clampedReports + " reports with clamping, " + plain.lastLoadReports + " without");
            assertThrows(IllegalArgumentException.class, () -> newContext().load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS,
                    GsLoadOptions.DEFAULT.withClampLevelsSelector("not a selector")));
        }
    }

    @StoneTest
    void loadingDoesNotGrowTheExportSet() {
        try (var warm = newContext()) { // warm up caches (the session resolves Date/DateAndTime once and keeps them)
            warm.load("UserGlobals at: #GsMapTestHolderObj", Holder.class);
            warm.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS);
        }
        var before = exportSetSize();
        for (var i = 0; i < 5; i++) {
            try (var ctx = newContext()) {
                ctx.load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS, GsLoadOptions.DEFAULT.withDepth(1));
            }
            try (var ctx = newContext()) {
                ctx.load("UserGlobals at: #GsMapTestHolderObj", Holder.class);
            }
        }
        assertEquals(before, exportSetSize());
        // Without change tracking nothing is held, even without close.
        var readOnly = GsLoadOptions.DEFAULT.withTrackChanges(false);
        newContext().load("UserGlobals at: #GsMapTestAccounts", ACCOUNTS, readOnly);
        newContext().load("UserGlobals at: #GsMapTestHolderObj", Holder.class, readOnly);
        assertEquals(before, exportSetSize());
    }

    private long exportSetSize() {
        return s().evaluate("System hiddenSetSize: (System HiddenSetSpecifiers indexOf: 'PureExportSet')", Long.class);
    }
}
