package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

class GciAsyncIntegrationTest {
    private static String sleep(int ms) {
        return "(Delay forMilliseconds: " + ms + ") wait. " + ms;
    }

    /** The cause a failed future was completed with. */
    private static Throwable failure(Future<?> f) {
        var e = assertThrows(ExecutionException.class, f::get);
        return e.getCause();
    }

    @StoneTest
    void asyncCallsReturnResultsAndErrors() throws Exception {
        try (var s = StoneEnv.login()) {
            assertEquals(7L, s.evaluateAsync("3 + 4").get());
            assertEquals(5, s.evaluateAsync("'Grüße' size", Integer.class).get()); // non-ASCII source path
            assertNull(s.evaluateAsync("nil").get()); // nil is not an error
            assertEquals(s.execute("7"), s.performAsync(Oop.fromSmallInteger(3), "+", Oop.fromSmallInteger(4)).get());
            assertEquals("ab!", s.sendAsync(s.execute("'ab'"), ",", "!").get());
            var ex = assertInstanceOf(GciException.class, failure(s.executeAsync("1/0")));
            assertEquals(2026, ex.number()); // ZeroDivide
            assertEquals(2L, s.evaluate("1 + 1")); // usable afterwards, sync and async
            assertEquals(3L, s.evaluateAsync("1 + 2").get());
        }
    }

    @StoneTest
    void sessionsExecuteInParallel() throws Exception {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            a.evaluateAsync("1").get();
            b.evaluateAsync("1").get();
            var start = System.nanoTime();
            var fa = a.evaluateAsync(sleep(1000), Long.class);
            var fb = b.evaluateAsync(sleep(1000), Long.class);
            assertEquals(1000L, fa.get());
            assertEquals(1000L, fb.get());
            var ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 950 && ms <= 1700, "took " + ms + " ms"); // sequential would take ≥ 2000 ms
        }
    }

    @StoneTest
    void otherSessionsStayUsableWhileOneWaits() throws Exception {
        try (var slow = StoneEnv.login(); var fast = StoneEnv.login()) {
            var pending = slow.evaluateAsync(sleep(1500), Long.class);
            var start = System.nanoTime();
            for (var i = 0; i < 20; i++) assertEquals((long) i, fast.evaluate(Integer.toString(i)));
            var ms = (System.nanoTime() - start) / 1_000_000;
            assertFalse(pending.isDone());
            assertTrue(ms < 1000, "fast session was blocked for " + ms + " ms");
            assertEquals(1500L, pending.get());
        }
    }

    @StoneTest
    void syncCallOnBusySessionIsRejected() throws Exception {
        try (var s = StoneEnv.login()) {
            var pending = s.executeAsync(sleep(500));
            assertThrows(IllegalStateException.class, () -> s.execute("1"));
            assertThrows(IllegalStateException.class, () -> s.executeAsync("1"));
            pending.get();
            assertEquals(1L, s.evaluate("1"));
        }
    }

    @StoneTest
    void cancellationSendsSoftBreak() throws Exception {
        try (var s = StoneEnv.login()) {
            var start = System.nanoTime();
            var f = s.evaluateAsync("[true] whileTrue");
            Thread.sleep(300);
            assertTrue(f.cancel(true));
            var e = assertThrows(CancellationException.class, f::get);
            assertTrue(f.isCancelled());
            assertInstanceOf(GciException.class, e.getCause() instanceof CancellationException c ? c.getCause() : e.getCause());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
            assertTrue(s.isLoggedIn());
            assertEquals(4L, s.evaluateAsync("2 + 2").get());
        }
    }

    @StoneTest
    void asyncTransactions() throws Exception {
        var key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            try {
                a.abortAsync().get();
                a.execute("UserGlobals at: #" + key + " put: 1");
                a.commitAsync().get();
                b.abortAsync().get();
                assertEquals(1L, b.evaluate("UserGlobals at: #" + key));
                // Conflict: b's snapshot predates a's second commit.
                a.execute("UserGlobals at: #" + key + " put: 2");
                a.commitAsync().get();
                b.execute("UserGlobals at: #" + key + " put: 3");
                assertFalse(b.tryCommitAsync().get());
                assertInstanceOf(GciTransactionConflictException.class, failure(b.commitAsync()));
                b.abortAsync().get();
                assertEquals(2L, b.evaluate("UserGlobals at: #" + key));
                a.setTransactionMode(GciTransactionMode.MANUAL_BEGIN);
                a.beginAsync().get();
                assertTrue(a.inTransaction());
            } finally {
                a.setTransactionMode(GciTransactionMode.AUTO_BEGIN);
                a.abort();
                a.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]");
                a.commit();
            }
        }
    }

    @StoneTest
    void closeDuringPendingCallInterruptsIt() throws Exception {
        try (var other = StoneEnv.login()) {
            var s = StoneEnv.login();
            var pending = s.executeAsync("[true] whileTrue");
            Thread.sleep(200);
            s.close();
            assertFalse(s.isLoggedIn());
            assertInstanceOf(IllegalStateException.class, failure(pending));
            assertEquals(1L, other.evaluate("1"));
        }
    }
}
