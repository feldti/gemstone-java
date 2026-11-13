package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.IntStream;

/** Several logged-in sessions in one process, switched via GciSetSessionId. */
class GciMultiSessionIntegrationTest {
    @StoneTest
    void sessionsAreIndependentGems() {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            assertNotEquals(a.sessionId(), b.sessionId());
            assertNotEquals(a.gemSessionId(), b.gemSessionId());
            for (var i = 0; i < 5; i++) { // interleaved calls hit the right gem every time
                assertEquals(a.gemSessionId(), a.gemSessionId());
                assertNotEquals(a.gemSessionId(), b.gemSessionId());
            }
        }
    }

    @StoneTest
    void sessionStateIsSeparate() {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            a.setTransactionMode(GciTransactionMode.MANUAL_BEGIN);
            assertEquals(GciTransactionMode.MANUAL_BEGIN, a.transactionMode());
            assertEquals(GciTransactionMode.AUTO_BEGIN, b.transactionMode());
            a.execute("SessionTemps current at: #GciProbe put: 'a'");
            b.execute("SessionTemps current at: #GciProbe put: 'b'");
            assertEquals("a", a.evaluate("SessionTemps current at: #GciProbe"));
            assertEquals("b", b.evaluate("SessionTemps current at: #GciProbe"));
        }
    }

    @StoneTest
    void objectsAndReleaseStayInTheirSession() {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            var strA = a.newString("from a");
            var strB = b.newString("from b");
            assertEquals("from a", a.toStringValue(strA));
            assertEquals("from b", b.toStringValue(strB));
            assertEquals("from a!", a.send(strA, ",", "!"));
            a.release(strA);
            b.release(strB);
        }
    }

    @StoneTest
    void closingOneSessionKeepsTheOthersWorking() {
        try (var a = StoneEnv.login()) {
            var b = StoneEnv.login();
            var c = StoneEnv.login();
            b.close();
            assertFalse(b.isLoggedIn());
            assertThrows(IllegalStateException.class, () -> b.execute("1"));
            assertEquals(7L, a.evaluate("3 + 4"));
            assertEquals(8L, c.evaluate("4 + 4"));
            c.close();
            assertEquals(9L, a.evaluate("4 + 5"));
        }
    }

    @StoneTest
    void errorInOneSessionDoesNotAffectTheOther() {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            assertThrows(GciException.class, () -> a.execute("1/0"));
            assertEquals(2L, b.evaluate("1 + 1")); // no stale error report from a
            assertEquals(3L, a.evaluate("1 + 2"));
        }
    }

    @StoneTest
    void sessionsCanBeUsedFromDifferentThreads() {
        try (var a = StoneEnv.login(); var b = StoneEnv.login()) {
            var gemA = a.gemSessionId();
            var gemB = b.gemSessionId();
            IntStream.range(0, 40).parallel().forEach(i -> {
                var s = i % 2 == 0 ? a : b;
                assertEquals(i % 2 == 0 ? gemA : gemB, s.gemSessionId());
                assertEquals((long) i * i, s.send(Oop.fromSmallInteger(i), "*", i));
            });
        }
    }

    @StoneTest
    void softBreakOnlyInterruptsTheTargetSession() throws InterruptedException {
        try (var a = StoneEnv.login(); var idle = StoneEnv.login()) {
            idle.softBreak(); // not executing: no effect, nothing pending afterwards
            assertEquals(1L, idle.evaluate("1"));
            var breaker = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                a.softBreak();
            });
            assertThrows(GciException.class, () -> a.execute("[true] whileTrue"));
            breaker.join();
            assertEquals(2L, idle.evaluate("2"));
            assertEquals(3L, a.evaluate("3"));
        }
    }
}
