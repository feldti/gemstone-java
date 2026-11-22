package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

class GciTraversalIntegrationTest {
    @StoneTest
    void theBufferSizeFollowsTheRoundTrip() {
        try (var s = StoneEnv.login()) {
            assertTrue(s.roundTrip().toNanos() > 0);
            assertEquals(s.roundTrip().compareTo(GciSession.LOCAL_ROUND_TRIP_LIMIT) < 0
                    ? GciTraversalOptions.DEFAULT_BUFFER_SIZE : GciTraversalOptions.NETWORK_BUFFER_SIZE, s.preferredTraversalBufferSize());
            // Several buffers (double-buffered with GciNbMoreTraversal), then the session is idle again.
            var t = s.executeTraverse("(1 to: 3000) collect: [:i | i printString]", 2, GciTraversalOptions.DEFAULT.withBufferSize(4096));
            assertEquals(3001, t.reports().size());
            assertEquals(7L, s.toValue(s.execute("3 + 4")));
        }
    }

    @StoneTest
    void aTraversalLeavesTheGciFreeWhileTheGemWorks() throws Exception {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            var block = a.execute("[:x | (Delay forMilliseconds: 600) wait. x]");
            try {
                var slow = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> a.performTraverse(block, "value:", 1, new Oop[] {Oop.fromSmallInteger(7)}, null));
                Thread.sleep(100); // a's gem is waiting now
                var start = System.nanoTime();
                assertEquals(Oop.fromSmallInteger(7), b.execute("3 + 4"));
                var other = (System.nanoTime() - start) / 1_000_000;
                assertTrue(!slow.isDone());
                assertEquals(Oop.fromSmallInteger(7), slow.get().result());
                assertTrue(other < 300, "the other session waited " + other + " ms");
            } finally {
                a.release(block);
            }
        }
    }

    @StoneTest
    void anotherThreadOnTheSameSessionWaitsForATraversal() throws Exception {
        try (var a = StoneEnv.login()) {
            var block = a.execute("[:x | (Delay forMilliseconds: 400) wait. x]");
            try {
                var slow = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> a.performTraverse(block, "value:", 1, new Oop[] {Oop.fromSmallInteger(5)}, null));
                Thread.sleep(100);
                assertEquals(Oop.fromSmallInteger(9), a.execute("4 + 5")); // waits for the traversal, does not fail
                assertTrue(slow.isDone());
                assertEquals(Oop.fromSmallInteger(5), slow.get().result());
            } finally {
                a.release(block);
            }
        }
    }

    @StoneTest
    void anExceptionBetweenTwoBuffersDrainsTheTraversal() {
        try (var s = StoneEnv.login()) {
            var big = s.execute("(1 to: 20000) collect: [:i | i printString]");
            try {
                var buffers = new int[1];
                // A load cancelled between two buffers ends this way, while the gem fills the next one.
                assertThrows(java.util.concurrent.CancellationException.class, () -> s.traverse(new Oop[] {big}, 2,
                        GciTraversalOptions.DEFAULT.withBufferSize(16 * 1024), (reports, first) -> {
                            if (++buffers[0] == 2) throw new java.util.concurrent.CancellationException();
                        }));
                assertEquals(2, buffers[0]);
                assertEquals(Oop.fromSmallInteger(7), s.execute("3 + 4")); // no call left running
                assertTrue(s.traverse(big, 2).reports().size() > 20000);
            } finally {
                s.release(big);
            }
        }
    }

    @StoneTest
    void smallDoubleEncodesLocallyLikeGciFltToOop() {
        try (var s = StoneEnv.login()) {
            var rnd = new Random(42);
            var values = new ArrayList<Double>(List.of(0.0, -0.0, 1.0, -1.5, 0.1, 1e30, -1e38, 6e38, 7e38, 1e-38, 2e-38, 5e-39,
                    1e-39, 1e100, 1e-100, Double.MAX_VALUE, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY));
            for (var bits : new long[] {0x3810000000000000L, 0x380FFFFFFFFFFFFFL, 0x47FFFFFFFFFFFFFFL, 0x4800000000000000L,
                    0x3800000000000000L, 0x3800000000000001L})
                values.add(Double.longBitsToDouble(bits));
            for (var i = 0; i < 500; i++) {
                values.add(Double.longBitsToDouble(rnd.nextLong()));
                values.add(rnd.nextDouble() * Math.pow(10, rnd.nextInt(-40, 40)));
            }
            for (var d : values) {
                var server = s.newFloat(d);
                try {
                    var local = Oop.tryFromSmallDouble(d);
                    // newFloat itself encodes locally; compare against the server's own encoding of the Float.
                    var viaServer = s.perform(server, "yourself");
                    assertEquals(viaServer.isSmallDouble(), local != null, "%s bits %016X".formatted(d, Double.doubleToRawLongBits(d)));
                    if (viaServer.isSmallDouble()) assertEquals(viaServer, local);
                } finally {
                    s.release(server);
                }
            }
        }
    }

    @StoneTest
    void smallDoubleDecodesLocally() {
        try (var s = StoneEnv.login()) {
            String[] literals = {"1.5", "-2.25", "0.0", "1.0e-10", "3.14159", "1.0e30"};
            double[] expected = {1.5, -2.25, 0.0, 1e-10, 3.14159, 1e30};
            for (var i = 0; i < literals.length; i++) {
                var oop = s.execute(literals[i]);
                assertTrue(oop.isSmallDouble());
                assertEquals(expected[i], oop.toSmallDouble());
            }
            assertTrue(s.execute("1.0e100").isSmallDouble() == false); // 8-bit exponent: a Float
        }
    }

    @StoneTest
    void traverseRespectsLevels() {
        try (var s = StoneEnv.login()) {
            var root = s.execute("Array with: 'abc' with: 42 with: (Array with: 'deep' with: #sym)");
            try {
                var level1 = s.traverse(root, 1);
                assertEquals(1, level1.reports().size());
                var rootReport = level1.get(root);
                assertEquals(KnownClasses.ARRAY, rootReport.objectClass());
                assertEquals(GciObjectFormat.POINTER, rootReport.format());
                assertEquals(3, rootReport.indexedSize());
                assertEquals(42L, level1.toValue(rootReport.oops().get(1)));
                assertInstanceOf(Oop.class, level1.toValue(rootReport.oops().get(0))); // 'abc' not reported at level 1

                var level2 = s.traverse(root, 2);
                assertEquals(3, level2.reports().size()); // root, 'abc', inner array
                var inner = level2.get(root).oops().get(2);
                assertEquals(Arrays.asList("abc", 42L, inner), level2.toList(root));
                assertInstanceOf(Oop.class, level2.toList(inner).get(0)); // 'deep' is level 3

                var all = s.traverse(root, 0);
                assertEquals(List.of("deep", "sym"), all.toList(all.get(root).oops().get(2)));
            } finally {
                s.release(root);
            }
        }
    }

    @StoneTest
    void executeTraverseDecodesKernelClassesWithoutRoundTrips() {
        try (var s = StoneEnv.login()) {
            var t = s.executeTraverse("""
                    Array new
                      add: 'latin1 äöü' asString;
                      add: #aSymbol;
                      add: (String new: 2) asUnicodeString;
                      add: ((Unicode16 new: 2) at: 1 put: (Character codePoint: 16r65E5); at: 2 put: (Character codePoint: 16r672C); yourself);
                      add: ((Unicode32 new: 1) at: 1 put: (Character codePoint: 16r1F600); yourself);
                      add: 'utf8 ü' encodeAsUTF8;
                      add: #[1 2 255];
                      add: 1.0e300;
                      add: 2.5;
                      add: $x;
                      add: nil;
                      add: true;
                      yourself
                    """, 2, null);
            assertNotNull(t.result());
            var list = t.toList(t.result());
            assertArrayEquals(new byte[] {1, 2, (byte) 255}, (byte[]) list.get(6));
            list.set(6, "bytes");
            assertEquals(Arrays.asList("latin1 äöü", "aSymbol", "\0\0", "日本", "😀", "utf8 ü", "bytes", 1e300, 2.5, new CodePoint('x'), null, true), list);
        }
    }

    @StoneTest
    void nonAsciiSourceAlsoWorks() {
        try (var s = StoneEnv.login()) {
            var t = s.executeTraverse("Array with: 'Grüße' size with: 'ok'", 2, null);
            assertEquals(List.of(5L, "ok"), t.toList(t.result()));
        }
    }

    @StoneTest
    void largeGraphsNeedSeveralBuffers() {
        try (var s = StoneEnv.login()) {
            var t = s.executeTraverse("(1 to: 3000) collect: [:i | i printString]", 2,
                    GciTraversalOptions.DEFAULT.withBufferSize(4096)); // forces many GciMoreTraversal calls
            assertEquals(3001, t.reports().size());
            assertEquals(IntStream.rangeClosed(1, 3000).mapToObj(Integer::toString).toList(), t.toList(t.result()));
        }
    }

    @StoneTest
    void objectsLargerThanTheBufferAreMerged() {
        try (var s = StoneEnv.login()) {
            var t = s.executeTraverse("(1 to: 5000) asArray", 1, GciTraversalOptions.DEFAULT.withBufferSize(4096));
            var r = t.get(t.result());
            assertEquals(5000, r.indexedSize());
            assertEquals(5000, r.oopCount());
            assertEquals(5000L, t.toValue(r.indexed().get(4999)));
        }
    }

    @StoneTest
    void performTraverse() {
        try (var s = StoneEnv.login()) {
            var array = s.execute("Array with: 'a' with: 'b'");
            try {
                var t = s.performTraverse(array, "reverse", 2, new Oop[0], null);
                assertEquals(List.of("b", "a"), t.toList(t.result()));
                var t2 = s.performTraverse(array, "copyWith:", 2, new Oop[] {s.newString("c")}, null);
                assertEquals(List.of("a", "b", "c"), t2.toList(t2.result()));
            } finally {
                s.release(array);
            }
        }
    }

    @StoneTest
    void scalarResultsAndErrors() {
        try (var s = StoneEnv.login()) {
            var t = s.executeTraverse("3 + 4", 1, null);
            assertEquals(Oop.fromSmallInteger(7), t.result());
            assertEquals(7L, t.toValue(t.result()));
            assertThrows(GciException.class, () -> s.executeTraverse("1/0", 1, null));
            assertEquals(2L, s.evaluate("1 + 1"));
        }
    }

    @StoneTest
    void fetchPathsFollowsNamedAndIndexedSteps() {
        try (var s = StoneEnv.login()) {
            var objects = s.execute("""
                    Array
                      with: (Association key: #a value: (Association key: 'inner' value: (Array with: 10 with: 20)))
                      with: (Association key: #b value: nil)
                    """);
            try {
                var roots = s.fetchVaryingOops(objects);
                var r = s.fetchPaths(roots, new int[] {1}, new int[] {2, 1}, new int[] {2, 2, -2});
                assertEquals(2, r.length);
                assertEquals(s.execute("#a"), r[0][0]);
                assertEquals("inner", s.toStringValue(r[0][1]));
                assertEquals(Oop.fromSmallInteger(20), r[0][2]);
                assertEquals(s.execute("#b"), r[1][0]);
                assertEquals(Oop.ILLEGAL, r[1][1]); // value is nil: path cannot be followed
                assertEquals(Oop.ILLEGAL, r[1][2]);
                assertEquals(1L, s.evaluate("1")); // no stale error left behind
            } finally {
                s.release(objects);
            }
        }
    }

    @StoneTest
    void traversalsDoNotGrowTheExportSetUnlessAsked() {
        try (var s = StoneEnv.login()) {
            var source = "(1 to: 50) collect: [:i | i printString]";
            GciObjectIntegrationTest.exportSetSize(s);
            var before = GciObjectIntegrationTest.exportSetSize(s);
            for (var i = 0; i < 5; i++) s.executeTraverse(source, 2, null);
            var afterPlain = GciObjectIntegrationTest.exportSetSize(s);
            var array = s.execute("#(1 2 3)");
            for (var i = 0; i < 5; i++) s.performTraverse(array, "reverse", 2, new Oop[0], null);
            var afterPerform = GciObjectIntegrationTest.exportSetSize(s);
            var kept = s.executeTraverse(source, 2, GciTraversalOptions.DEFAULT.withKeepResult(true));
            var afterKeep = GciObjectIntegrationTest.exportSetSize(s);
            s.release(kept.result(), array);
            var afterRelease = GciObjectIntegrationTest.exportSetSize(s);
            assertTrue(afterPlain - before <= 1 && afterPlain - before >= 0);
            assertTrue(afterPerform - afterPlain >= 1 && afterPerform - afterPlain <= 2); // just the receiver array
            assertEquals(1, afterKeep - afterPerform); // the kept result
            assertTrue(afterRelease - afterPlain >= 0 && afterRelease - afterPlain <= 1);
        }
    }
}
