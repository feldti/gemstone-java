package net.gs64.gci.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.gs64.gci.GciException;
import net.gs64.gci.GciSession;
import net.gs64.gci.StoneEnv;
import net.gs64.gci.StoneTest;
import net.gs64.gci.mapping.ServerModel.BigSquare;
import net.gs64.gci.mapping.ServerModel.Circle;
import net.gs64.gci.mapping.ServerModel.Drawing;
import net.gs64.gci.mapping.ServerModel.Holder;
import net.gs64.gci.mapping.ServerModel.HolderWithoutKey;
import net.gs64.gci.mapping.ServerModel.ModelHolder;
import net.gs64.gci.mapping.ServerModel.ModelThing;
import net.gs64.gci.mapping.ServerModel.OwnList;
import net.gs64.gci.mapping.ServerModel.Plain;
import net.gs64.gci.mapping.ServerModel.Square;
import net.gs64.gci.mapping.ServerModel.Thing;

/** Own session; creates the classes (with an initialize that sets up RC collections) and never commits. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GsServerCollectionsIntegrationTest {
    GciSession s;

    @BeforeAll
    void setUp() {
        s = StoneEnv.login();
        s.execute("""
                Object subclass: 'GsSrvTestThing' instVarNames: #(#name)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                Object subclass: 'GsSrvTestHolder' instVarNames: #(#items #byName #queue)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                Object subclass: 'GsSrvTestPlain' instVarNames: #(#items #byName)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                Object subclass: 'GsSrvTestCircle' instVarNames: #(#name)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                Object subclass: 'GsSrvTestSquare' instVarNames: #(#name)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                Object subclass: 'GsSrvTestDrawing' instVarNames: #(#shapes #main #first)
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                nil
                """);
        s.execute("""
                (UserGlobals at: #GsSrvTestSquare) subclass: 'GsSrvTestBigSquare' instVarNames: #()
                    classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                nil
                """);
        s.execute("""
                (UserGlobals at: #GsSrvTestDrawing) compileMethod: 'initialize shapes := OrderedCollection new'
                    dictionaries: System myUserProfile symbolList category: 'initialization' environmentId: 0.
                (UserGlobals at: #GsSrvTestThing) compileMethod: 'name ^name' dictionaries: System myUserProfile symbolList
                    category: 'accessing' environmentId: 0.
                (UserGlobals at: #GsSrvTestHolder) compileMethod: 'initialize
                    items := RcIdentityBag new. byName := RcKeyValueDictionary new. queue := RcQueue new'
                    dictionaries: System myUserProfile symbolList category: 'initialization' environmentId: 0.
                nil
                """);
    }

    @AfterAll
    void tearDown() {
        if (s != null && s.isLoggedIn()) s.abort();
        if (s != null) s.close();
    }

    GsObjectContext newContext() {
        return new GsObjectContext(s);
    }

    private static String newKey() {
        return "GsSrvTest_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A holder whose initialize created the RC collections, filled with a, b, c; returns the expression that answers it. */
    private String newHolder() {
        var key = newKey();
        s.execute("""
                | h |
                h := (UserGlobals at: #GsSrvTestHolder) new.
                #('a' 'b' 'c') do: [:n | | t |
                    t := (UserGlobals at: #GsSrvTestThing) new instVarNamed: 'name' put: n; yourself.
                    (h instVarNamed: 'items') add: t.
                    (h instVarNamed: 'byName') at: n put: t.
                    (h instVarNamed: 'queue') add: t].
                UserGlobals at: #%s put: h.
                true
                """.formatted(key));
        return "(UserGlobals at: #" + key + ")";
    }

    /** Class name and sorted element names of a collection, e.g. {@code RcIdentityBag a b}. */
    private String describe(String collection) {
        return s.evaluate("""
                | c names s |
                c := %s.
                names := (c isKindOf: AbstractDictionary)
                    ifTrue: [c keys asSortedCollection collect: [:k | k , '=' , ((c at: k) instVarNamed: 'name')]]
                    ifFalse: [(c asArray collect: [:t | t instVarNamed: 'name']) asSortedCollection].
                s := WriteStream on: String new.
                s nextPutAll: c class name.
                names do: [:n | s space; nextPutAll: n].
                s contents
                """.formatted(collection), String.class);
    }

    private static List<String> names(Collection<Thing> things) {
        return things.stream().map(t -> t.name).sorted().toList();
    }

    private static Thing named(List<Thing> things, String name) {
        return things.stream().filter(t -> t.name.equals(name)).findFirst().orElseThrow();
    }

    @StoneTest
    void rcCollectionsDictionariesAndQueuesLoadAsLists() {
        var key = newHolder();
        try (var ctx = newContext()) {
            var h = ctx.load(key, Holder.class);
            assertEquals(List.of("a", "b", "c"), names(h.items));
            assertEquals(List.of("a", "b", "c"), names(h.byName));
            assertEquals(List.of("a", "b", "c"), names(h.queue));
            assertSame(named(h.items, "a"), named(h.byName, "a")); // one object, one instance
            assertEquals(3L, s.evaluate("(" + key + " instVarNamed: 'queue') size", Long.class)); // loading does not consume the queue
        }
    }

    @StoneTest
    void changedListsArePatchedIntoTheServerCollections() {
        var key = newHolder();
        try (var ctx = newContext()) {
            var h = ctx.load(key, Holder.class);
            var d = new Thing("d");
            h.items.remove(named(h.items, "b"));
            h.items.add(d);
            h.byName.remove(named(h.byName, "a"));
            h.byName.add(d);
            h.queue.add(d);
            ctx.saveChanges();
            assertEquals("RcIdentityBag a c d", describe(key + " instVarNamed: 'items'"));
            assertEquals("RcKeyValueDictionary b=b c=c d=d", describe(key + " instVarNamed: 'byName'"));
            assertEquals("RcQueue a b c d", describe(key + " instVarNamed: 'queue'"));
            assertTrue(s.evaluate("((" + key + " instVarNamed: 'byName') at: 'd') == ((" + key + " instVarNamed: 'items') detect: [:t | (t instVarNamed: 'name') = 'd'])", Boolean.class));
        }
        try (var again = newContext()) { // a second save after reloading: nothing left to write
            again.load(key, Holder.class);
            assertEquals(0, again.saveChanges());
        }
    }

    @StoneTest
    void newObjectsGetTheCollectionsOfInitialize() {
        var name = newKey();
        var key = "(UserGlobals at: #" + name + ")";
        var a = new Thing("a");
        var h = new Holder();
        h.items = new ArrayList<>(List.of(a, new Thing("b")));
        h.byName = new ArrayList<>(List.of(a));
        h.queue = new ArrayList<>(List.of(a));
        try (var ctx = newContext()) {
            var oop = ctx.add(h);
            s.execute("UserGlobals at: #" + name + " put: (Object _objectForOop: " + oop.value() + "). true");
            assertEquals("RcIdentityBag a b", describe(key + " instVarNamed: 'items'"));
            assertEquals("RcKeyValueDictionary a=a", describe(key + " instVarNamed: 'byName'"));
            assertEquals("RcQueue a", describe(key + " instVarNamed: 'queue'"));
            // The snapshots know the server collections: a later change patches them.
            h.byName.add(h.items.get(1));
            h.items.remove(0);
            ctx.saveChanges();
            assertEquals("RcIdentityBag b", describe(key + " instVarNamed: 'items'"));
            assertEquals("RcKeyValueDictionary a=a b=b", describe(key + " instVarNamed: 'byName'"));
        }
    }

    @StoneTest
    void withoutInitializeTheJavaSideChoosesTheCollections() {
        var name = newKey();
        var p = new Plain();
        p.items.add(new Thing("x"));
        p.byName.add(new Thing("y"));
        try (var ctx = newContext()) {
            var oop = ctx.add(p);
            s.execute("UserGlobals at: #" + name + " put: (Object _objectForOop: " + oop.value() + "). true");
            assertEquals("OrderedCollection x", describe("(UserGlobals at: #" + name + ") instVarNamed: 'items'"));
            assertEquals("KeyValueDictionary y=y", describe("(UserGlobals at: #" + name + ") instVarNamed: 'byName'"));
        }
    }

    @StoneTest
    void aDictionaryWithoutKeySelectorCannotBeChanged() {
        var key = newHolder();
        try (var ctx = newContext()) {
            var h = ctx.load(key, HolderWithoutKey.class);
            h.byName.add(new Thing("z"));
            var e = assertThrows(GsMappingException.class, ctx::saveChanges);
            assertTrue(e.getMessage().contains("GsDictionaryKey"), e.getMessage());
            assertEquals("RcKeyValueDictionary a=a b=b c=c", describe(key + " instVarNamed: 'byName'"));
        }
    }

    @StoneTest
    void protectedFieldsAndOwnListTypesLikeGeneratedDomainClasses() {
        var key = newHolder();
        var holderOop = s.execute(key);
        try (var ctx = newContext()) {
            var h = ctx.load(holderOop, ModelHolder.class);
            assertEquals(holderOop, h.oop()); // @GsOop on a protected field of the base class
            assertInstanceOf(OwnList.class, h.items); // the own list type, not ArrayList
            assertEquals(List.of("a", "b", "c"), h.items.stream().map(t -> t.name).sorted().toList());
            assertEquals(List.of("a", "b", "c"), h.byName.stream().map(t -> t.name).sorted().toList());
            for (var t : h.items) assertFalse(t.oop() == null || t.oop().isNil());
            // Change through the Java objects, save, compare with the server.
            h.items.stream().filter(t -> t.name.equals("a")).findFirst().orElseThrow().name = "A";
            h.items.removeIf(t -> t.name.equals("b"));
            var d = new ModelThing("d");
            h.items.add(d);
            h.byName.add(d);
            ctx.saveChanges();
            assertNotNull(d.oop()); // a new object gets its OOP
            assertEquals("RcIdentityBag A c d", describe(key + " instVarNamed: 'items'"));
            assertEquals("RcKeyValueDictionary a=A b=b c=c d=d", describe(key + " instVarNamed: 'byName'"));
            assertEquals("d", s.evaluate("(Object _objectForOop: " + d.oop().value() + ") instVarNamed: 'name'", String.class));
        } finally {
            s.release(holderOop);
        }
    }

    @StoneTest
    void newObjectsWithProtectedFields() {
        var name = newKey();
        var h = new ModelHolder();
        h.items.add(new ModelThing("x"));
        h.byName.add(h.items.get(0));
        try (var ctx = newContext()) {
            var oop = ctx.add(h);
            s.execute("UserGlobals at: #" + name + " put: (Object _objectForOop: " + oop.value() + "). true");
            assertEquals(oop, h.oop());
            assertEquals("RcIdentityBag x", describe("(UserGlobals at: #" + name + ") instVarNamed: 'items'"));
            assertEquals("RcKeyValueDictionary x=x", describe("(UserGlobals at: #" + name + ") instVarNamed: 'byName'"));
        }
    }

    @StoneTest
    void aQueueCanOnlyBeAppendedTo() {
        var key = newHolder();
        try (var ctx = newContext()) {
            var h = ctx.load(key, Holder.class);
            h.queue.remove(0);
            var e = assertThrows(GciException.class, ctx::saveChanges);
            assertTrue(e.getMessage().contains("RcQueue can only be appended to"), e.getMessage()); // GemStone's own error says nothing useful
            assertEquals("RcQueue a b c", describe(key + " instVarNamed: 'queue'"));
        }
    }

    /** A drawing with a circle, a square and a big square (main = the square, first = the circle). */
    private String newDrawing() {
        var key = newKey();
        s.execute("""
                | d new |
                new := [:cls :n | (UserGlobals at: cls) new instVarNamed: 'name' put: n; yourself].
                d := (UserGlobals at: #GsSrvTestDrawing) new.
                (d instVarNamed: 'shapes') add: (new value: #GsSrvTestCircle value: 'c');
                    add: (new value: #GsSrvTestSquare value: 's'); add: (new value: #GsSrvTestBigSquare value: 'b').
                d instVarNamed: 'main' put: ((d instVarNamed: 'shapes') at: 2).
                d instVarNamed: 'first' put: ((d instVarNamed: 'shapes') at: 1).
                UserGlobals at: #%s put: d.
                true
                """.formatted(key));
        return "(UserGlobals at: #" + key + ")";
    }

    private String shapesOnServer(String key) {
        return s.evaluate("""
                | s |
                s := WriteStream on: String new.
                (%s instVarNamed: 'shapes') do: [:x | s nextPutAll: x class name; nextPut: $:; nextPutAll: (x instVarNamed: 'name'); space].
                s contents trimSeparators
                """.formatted(key), String.class);
    }

    @StoneTest
    void interfaceElementsLoadAsTheMatchingMappedClass() {
        var key = newDrawing();
        try (var ctx = newContext()) {
            var d = ctx.load(key, Drawing.class);
            assertEquals(List.of(Circle.class, Square.class, BigSquare.class), d.shapes.stream().map(Object::getClass).toList());
            assertEquals(List.of("c", "s", "b"), d.shapes.stream().map(ServerModel.Shape::name).toList());
            assertSame(d.shapes.get(1), d.main); // a single interface-typed reference
            assertSame(d.shapes.get(0), d.first); // an unmapped abstract base class
            for (var x : d.shapes) assertNotNull(((ServerModel.ModelBase) x).oop());
            // Change, remove, add new objects of different classes, save.
            d.shapes.get(0).name("C");
            d.shapes.remove(1);
            d.shapes.add(new Circle("n1"));
            d.shapes.add(new BigSquare("n2"));
            d.main = d.shapes.get(3);
            ctx.saveChanges();
            assertEquals("GsSrvTestCircle:C GsSrvTestBigSquare:b GsSrvTestCircle:n1 GsSrvTestBigSquare:n2", shapesOnServer(key));
            assertEquals("n2", s.evaluate("(" + key + " instVarNamed: 'main') instVarNamed: 'name'", String.class));
        }
        try (var ctx2 = newContext()) {
            var again = ctx2.load(key, Drawing.class);
            assertEquals(List.of(Circle.class, BigSquare.class, Circle.class, BigSquare.class), again.shapes.stream().map(Object::getClass).toList());
        }
    }

    @StoneTest
    void newObjectsWithInterfaceElements() {
        var name = newKey();
        var d = new Drawing();
        d.shapes.add(new Square("x"));
        d.shapes.add(new Circle("y"));
        d.main = d.shapes.get(1);
        try (var ctx = newContext()) {
            var oop = ctx.add(d);
            s.execute("UserGlobals at: #" + name + " put: (Object _objectForOop: " + oop.value() + "). true");
            assertEquals("GsSrvTestSquare:x GsSrvTestCircle:y", shapesOnServer("(UserGlobals at: #" + name + ")"));
            assertEquals("OrderedCollection", s.evaluate("((UserGlobals at: #" + name + ") instVarNamed: 'shapes') class name asString", String.class));
        }
    }

    @StoneTest
    void anInterfaceElementOfAnUnmappedClassIsReported() {
        var key = newDrawing();
        s.execute("(" + key + " instVarNamed: 'shapes') add: ((UserGlobals at: #GsSrvTestThing) new). true");
        try (var ctx = newContext()) {
            var e = assertThrows(GsMappingException.class, () -> ctx.load(key, Drawing.class));
            assertTrue(e.getMessage().contains("no class mapped for Shape matches a GsSrvTestThing"), e.getMessage());
        }
    }
}
