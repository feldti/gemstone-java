package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Store traversal + execution (GciStoreTrav + GciExecuteStrFromContext), the base of batched saves. */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GciStoreIntegrationTest {
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @StoneTest
    void createsObjectsAndExecutesWithThemAsSelf() {
        try (var s = StoneEnv.login()) {
            var ids = s.getFreeOops(6);
            assertEquals(6, new HashSet<>(Arrays.asList(ids)).size());
            var objects = List.of(
                    // The root first: it references objects that come later in the buffer.
                    GciNewObject.pointers(ids[0], KnownClasses.ARRAY, ids[1], ids[2], ids[3], ids[4], ids[5], Oop.fromSmallInteger(7)),
                    GciNewObject.byteObject(ids[1], KnownClasses.STRING, ascii("plain")),
                    GciNewObject.byteObject(ids[2], KnownClasses.UTF8, "Grüße 😀".getBytes(StandardCharsets.UTF_8)),
                    GciNewObject.byteObject(ids[3], KnownClasses.BYTE_ARRAY, new byte[] {1, 2, (byte) 255}),
                    GciNewObject.ofFloat(ids[4], 1.0e100),
                    GciNewObject.pointers(ids[5], KnownClasses.ARRAY));
            var before = s.callCount();
            var result = s.storeAndExecute(objects,
                    "{ (self at: 1) class name. self at: 1. (self at: 2) decodeToUnicode. self at: 3. (self at: 4) class name. self at: 4. (self at: 5) size. self at: 6 }",
                    ids[0]);
            assertEquals(2, s.callCount() - before);
            try {
                var values = s.toValues(Arrays.asList(s.fetchVaryingOops(result)));
                assertEquals("String", values[0]);
                assertEquals("plain", values[1]);
                assertEquals("Grüße 😀", values[2]);
                assertArrayEquals(new byte[] {1, 2, (byte) 255}, (byte[]) values[3]);
                assertEquals("Float", values[4]);
                assertEquals(1.0e100, values[5]);
                assertEquals(0L, values[6]);
                assertEquals(7L, values[7]);
            } finally {
                s.release(result);
                s.release(ids);
            }
        }
    }

    @StoneTest
    void createdObjectsAreReleasedFromTheExportSet() {
        try (var s = StoneEnv.login()) {
            GciObjectIntegrationTest.exportSetSize(s);
            var before = GciObjectIntegrationTest.exportSetSize(s);
            var ids = s.getFreeOops(2);
            var result = s.storeAndExecute(List.of(GciNewObject.pointers(ids[0], KnownClasses.ARRAY, ids[1]),
                    GciNewObject.byteObject(ids[1], KnownClasses.STRING, ascii("x"))), "self size", ids[0]);
            assertEquals(1L, s.toValue(result));
            assertTrue(GciObjectIntegrationTest.exportSetSize(s) >= before + 2);
            s.release(ids);
            assertEquals(before, GciObjectIntegrationTest.exportSetSize(s));
        }
    }

    @StoneTest
    void errorsInTheCodeAreReported() {
        try (var s = StoneEnv.login()) {
            var ids = s.getFreeOops(1);
            var ex = assertThrows(GciException.class,
                    () -> s.storeAndExecute(List.of(GciNewObject.pointers(ids[0], KnownClasses.ARRAY)), "self foo", ids[0]));
            assertTrue(ex.getMessage().contains("foo"));
            s.release(ids);
            assertEquals(3L, s.evaluate("1 + 2", Long.class)); // the session is still usable
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 1_000, 4_000, 20_000, 150_000}) // 150,000: more than GciGetFreeOops hands out per call
    void manySmallObjectsInOneStoreTraversal(int count) {
        try (var s = StoneEnv.login()) {
            var ids = s.getFreeOops(count + 1);
            var objects = new ArrayList<GciNewObject>(count + 1);
            objects.add(GciNewObject.pointers(ids[0], KnownClasses.ARRAY, Arrays.copyOfRange(ids, 1, ids.length)));
            for (var i = 1; i <= count; i++) objects.add(GciNewObject.byteObject(ids[i], KnownClasses.STRING, ascii("element " + i)));
            var result = s.storeAndExecute(objects, "(self inject: 0 into: [:sum :e | sum + e size])", ids[0]);
            var expected = IntStream.rangeClosed(1, count).mapToLong(i -> ("element " + i).length()).sum();
            assertEquals(expected, s.toValue(result));
            s.release(ids);
            assertEquals(3L, s.evaluate("1 + 2"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 64 * 1024, 1024 * 1024, 8 * 1024 * 1024})
    void oneLargeByteObjectInOneStoreTraversal(int size) {
        try (var s = StoneEnv.login()) {
            var ids = s.getFreeOops(1);
            var bytes = new byte[size];
            for (var i = 0; i < size; i++) bytes[i] = (byte) (i * 7);
            var result = s.storeAndExecute(List.of(GciNewObject.byteObject(ids[0], KnownClasses.BYTE_ARRAY, bytes)),
                    "{ self size. self inject: 0 into: [:sum :b | (sum + b) \\\\ 1000000007] }", ids[0]);
            var values = s.toValues(Arrays.asList(s.fetchVaryingOops(result)));
            assertEquals((long) size, values[0]);
            long sum = 0;
            for (var b : bytes) sum = (sum + (b & 0xFF)) % 1000000007;
            assertEquals(sum, values[1]);
            s.release(result);
            s.release(ids);
        }
    }

    @StoneTest
    void freeOopsBeyondTheLimitPerCall() {
        try (var s = StoneEnv.login()) {
            var ids = s.getFreeOops(GciSession.MAX_FREE_OOPS_PER_CALL * 2 + 7);
            assertTrue(Arrays.stream(ids).noneMatch(o -> o.value() == 0)); // GciGetFreeOops alone leaves everything above 100,000 untouched
            assertEquals(ids.length, new HashSet<>(Arrays.asList(ids)).size());
        }
    }

    @StoneTest
    void freeOopsInCountsThatAreNotWholeBlocks() {
        // The Windows client fails on GciGetFreeOops(n) with n > 2000 and n % 2000 != 0 (error 2166);
        // getFreeOops asks for whole blocks and keeps the rest for the next call.
        try (var s = StoneEnv.login()) {
            var all = new ArrayList<Oop>();
            for (var n : new int[] {1, 1_999, 2_000, 2_001, 4_001, 3_601, 7, 50_001}) {
                var ids = s.getFreeOops(n);
                assertEquals(n, ids.length);
                assertTrue(Arrays.stream(ids).noneMatch(o -> o.value() == 0));
                all.addAll(Arrays.asList(ids));
            }
            assertEquals(all.size(), new HashSet<>(all).size()); // never handed out twice
            var before = s.callCount();
            s.getFreeOops(5); // served from the rest of the last block
            assertEquals(0, s.callCount() - before);
            var some = s.getFreeOops(3);
            var result = s.storeAndExecute(List.of(GciNewObject.pointers(some[0], KnownClasses.ARRAY, some[1], some[2]),
                    GciNewObject.byteObject(some[1], KnownClasses.STRING, ascii("a")),
                    GciNewObject.byteObject(some[2], KnownClasses.STRING, ascii("b"))), "self first , self last", some[0]);
            assertEquals("ab", s.toValue(result));
            s.release(result);
            s.release(some);
        }
    }
}
