package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

/** Structural access, using kernel classes so no classes have to be defined. */
class GciObjectIntegrationTest {
    static long exportSetSize(GciSession s) {
        return s.evaluate("System hiddenSetSize: (System HiddenSetSpecifiers indexOf: 'PureExportSet')", Long.class);
    }

    @StoneTest
    void namedInstanceVariablesByIndexAndName() {
        try (var s = StoneEnv.login()) {
            var assoc = s.execute("Association key: #k value: 42");
            try {
                var cls = s.fetchClass(assoc);
                assertEquals(GciObjectFormat.POINTER, s.fetchFormat(assoc));
                assertEquals(2, s.fetchNamedSize(assoc));
                assertEquals(1, s.instVarIndex(cls, "key"));
                assertEquals(2, s.instVarIndex(cls, "value"));
                assertThrows(IllegalArgumentException.class, () -> s.instVarIndex(cls, "noSuchVar"));
                assertArrayEquals(new Oop[] {s.execute("#k"), Oop.fromSmallInteger(42)}, s.fetchNamedOops(assoc));
                assertEquals("k", s.getInstVar(assoc, "key"));
                assertEquals(42L, s.getInstVar(assoc, "value"));
                s.setInstVar(assoc, "value", "Grüße");
                assertEquals("Grüße", s.send(assoc, "value")); // the server sees the stored object
                s.storeNamedOop(assoc, 2, Oop.NIL);
                assertEquals(Oop.NIL, s.fetchNamedOop(assoc, "value"));
            } finally {
                s.release(assoc);
            }
        }
    }

    @StoneTest
    void newObjectAndIndexedSlots() {
        try (var s = StoneEnv.login()) {
            var array = s.newObject(KnownClasses.ARRAY, 3);
            try {
                assertEquals(3, s.fetchVaryingSize(array));
                assertArrayEquals(new Oop[] {Oop.NIL, Oop.NIL, Oop.NIL}, s.fetchVaryingOops(array));
                s.storeVaryingOops(array, 1, Oop.fromSmallInteger(1), Oop.fromSmallInteger(2));
                s.appendOops(array, Oop.TRUE);
                assertEquals(4, s.fetchVaryingSize(array));
                assertEquals(Arrays.asList(1L, 2L, null, true), s.toList(array));
                assertEquals(Oop.fromSmallInteger(2), s.fetchVaryingOop(array, 2));
                assertArrayEquals(new Oop[] {Oop.fromSmallInteger(2), Oop.NIL}, s.fetchVaryingOops(array, 2, 2));
                assertThrows(IndexOutOfBoundsException.class, () -> s.fetchVaryingOop(array, 5));
                assertEquals(4L, s.send(array, "size"));
            } finally {
                s.release(array);
            }
        }
    }

    @StoneTest
    void largeCollectionsAreFetchedAndStoredInChunks() {
        try (var s = StoneEnv.login()) {
            var array = s.newObject(KnownClasses.ARRAY);
            try {
                var values = IntStream.rangeClosed(1, 20_000).mapToObj(Oop::fromSmallInteger).toArray(Oop[]::new);
                s.storeVaryingOops(array, 1, values);
                assertEquals(20_000L, s.send(array, "size"));
                assertEquals(200_010_000L, s.send(array, "inject:into:", 0, s.execute("[:a :b | a + b]")));
                assertArrayEquals(values, s.fetchVaryingOops(array));
            } finally {
                s.release(array);
            }
        }
    }

    @StoneTest
    void byteObjects() {
        try (var s = StoneEnv.login()) {
            var bytes = s.newByteArray(new byte[] {1, 2, 3});
            try {
                assertEquals(GciObjectFormat.BYTE, s.fetchFormat(bytes));
                s.storeBytes(bytes, 2, new byte[] {9, 9, 9}); // grows
                s.appendBytes(bytes, new byte[] {7});
                assertArrayEquals(new byte[] {1, 9, 9, 9, 7}, s.fetchBytes(bytes));
                assertArrayEquals(new byte[] {1, 9, 9, 9, 7}, (byte[]) s.toValue(bytes));
            } finally {
                s.release(bytes);
            }
            var str = s.newString("abc");
            try {
                s.appendBytes(str, "def".getBytes(StandardCharsets.US_ASCII));
                assertEquals("abcdef", s.toStringValue(str));
            } finally {
                s.release(str);
            }
        }
    }

    @StoneTest
    void nscMembership() {
        try (var s = StoneEnv.login()) {
            var set = s.newObject(s.resolveSymbol("IdentitySet"));
            try {
                assertEquals(GciObjectFormat.NSC, s.fetchFormat(set));
                s.addToNsc(set, Oop.fromSmallInteger(1));
                s.addToNsc(set, Oop.fromSmallInteger(2));
                assertTrue(s.nscIncludes(set, Oop.fromSmallInteger(1)));
                assertEquals(2, s.fetchVaryingSize(set));
                assertTrue(s.removeFromNsc(set, Oop.fromSmallInteger(1)));
                assertFalse(s.removeFromNsc(set, Oop.fromSmallInteger(1)));
                assertFalse(s.nscIncludes(set, Oop.fromSmallInteger(1)));
                assertEquals(List.of(2L), s.toList(set));
            } finally {
                s.release(set);
            }
        }
    }

    @StoneTest
    void resolveSymbolFindsGlobals() {
        try (var s = StoneEnv.login()) {
            assertEquals(KnownClasses.ARRAY, s.resolveSymbol("Array"));
            assertEquals(KnownClasses.STRING, s.resolveSymbol("String"));
            assertThrows(NoSuchElementException.class, () -> s.resolveSymbol("NoSuchGlobalXyz"));
            assertNull(s.tryResolveSymbol("NoSuchGlobalXyz", null));
            var userGlobals = s.tryResolveSymbol("UserGlobals", null);
            assertNotNull(userGlobals);
            assertFalse(userGlobals.isSpecial());
            assertThrows(IllegalArgumentException.class, () -> s.newObject(KnownClasses.BOOLEAN));
        }
    }

    @StoneTest
    void errorsAndEvaluateDoNotGrowTheExportSet() {
        try (var s = StoneEnv.login()) {
            exportSetSize(s); // warm up lazily resolved helpers
            s.evaluate("Date today");
            var before = exportSetSize(s);
            for (var i = 0; i < 50; i++) {
                assertThrows(GciException.class, () -> s.execute("1/0"));
                s.evaluate("'a string' , 'another'");
                s.evaluate("100 factorial");
                s.evaluate("Date today");
            }
            var grown = exportSetSize(s) - before;
            assertTrue(grown >= 0 && grown <= 5, "grew by " + grown);
        }
    }

    @StoneTest
    void resolveSymbolDoesNotGrowTheExportSet() {
        // GciResolveSymbol (3.6.5–3.7.5) leaves a String per call in the export set; the wrapper
        // uses GciResolveSymbolObj with its own, released name object instead.
        try (var s = StoneEnv.login()) {
            var before = exportSetSize(s);
            for (var i = 0; i < 20; i++) {
                s.release(s.resolveSymbol("OrderedCollection"));
                assertNull(s.tryResolveSymbol("NoSuchGlobalXyz", null));
            }
            assertEquals(before, exportSetSize(s));
        }
    }
}
