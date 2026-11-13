package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Login, execute, perform, errors, soft break (ported from the C# login/execution/fetch tests). */
class GciBasicsIntegrationTest {
    @StoneTest
    void loginAndLogoutSucceed() {
        try (var s = StoneEnv.login()) {
            assertTrue(s.isLoggedIn());
        }
    }

    @StoneTest
    void wrongPasswordThrowsGciException() {
        var ex = assertThrows(GciException.class, () -> GciSession.login(StoneEnv.net(), StoneEnv.user(), "definitely-wrong"));
        assertNotEquals(0, ex.number());
    }

    @StoneTest
    void executeReturnsResultOop() {
        try (var s = StoneEnv.login()) {
            assertEquals(s.execute("7"), s.execute("3 + 4"));
            assertEquals(Oop.TRUE, s.execute("3 < 4"));
            assertEquals(Oop.NIL, s.execute("nil")); // nil is not an error
        }
    }

    @StoneTest
    void executeErrorThrowsAndTheSessionStaysUsable() {
        try (var s = StoneEnv.login()) {
            var ex = assertThrows(GciException.class, () -> s.execute("1/0"));
            assertNotEquals(0, ex.number());
            assertFalse(ex.getMessage().isEmpty());
            assertEquals(s.execute("2"), s.execute("1 + 1"));
        }
    }

    @StoneTest
    void performSendsUnaryBinaryAndKeywordMessages() {
        try (var s = StoneEnv.login()) {
            var three = s.execute("3");
            assertEquals(s.execute("-3"), s.perform(three, "negated"));
            assertEquals(s.execute("7"), s.perform(three, "+", s.execute("4")));
            assertEquals(s.execute("5"), s.perform(three, "max:", s.execute("5")));
            assertEquals(Oop.TRUE, s.perform(three, "between:and:", s.execute("1"), s.execute("5")));
            assertThrows(GciException.class, () -> s.perform(three, "noSuchSelectorXyz"));
        }
    }

    @StoneTest
    void callsAfterCloseThrow() {
        var s = StoneEnv.login();
        s.close();
        assertThrows(IllegalStateException.class, () -> s.execute("1"));
    }

    @StoneTest
    void nonAsciiSourceIsCompiledAsUnicode() {
        try (var s = StoneEnv.login()) {
            // Via GciExecuteStr this would be 7 (UTF-8 bytes read as 8-bit chars).
            assertEquals(s.execute("5"), s.execute("'Grüße' size"));
            assertEquals(s.execute("3"), s.execute("'€ 😀' size"));
            assertEquals(s.execute("1"), s.execute("| d | d := [:x | x]. 'ö' size")); // a block in non-ASCII source
        }
    }

    @StoneTest
    void printStringRoundTripsUnicode() {
        try (var s = StoneEnv.login()) {
            assertEquals("7", s.printString(s.execute("3 + 4")));
            assertEquals("nil", s.printString(Oop.NIL));
            assertEquals("'日本ä'", s.printString(s.execute("'日本' , 'ä'")));
        }
    }

    @StoneTest
    void fetchBytesReturnsByteObjectContents() {
        try (var s = StoneEnv.login()) {
            var str = s.execute("'hello' copy");
            try {
                assertEquals(5, s.fetchSize(str));
                assertEquals("hello", new String(s.fetchBytes(str), StandardCharsets.US_ASCII));
                assertArrayEquals(new byte[0], s.fetchBytes(s.execute("String new")));
            } finally {
                s.release(str);
            }
        }
    }

    @StoneTest
    void softBreakInterruptsRunningCodeAndSessionStaysUsable() throws InterruptedException {
        try (var s = StoneEnv.login()) {
            var breaker = Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                s.softBreak();
            });
            var ex = assertThrows(GciException.class, () -> s.execute("[true] whileTrue"));
            breaker.join();
            assertFalse(ex.isFatal());
            assertTrue(s.isLoggedIn());
            assertEquals(s.execute("2"), s.execute("1 + 1"));
        }
    }

    @StoneTest
    void errorsDoNotGrowTheExportSet() {
        try (var s = StoneEnv.login()) {
            var size = new AtomicReference<Long>();
            for (var i = 0; i < 3; i++) assertThrows(GciException.class, () -> s.execute("1/0")); // warm up
            size.set(s.evaluate("System hiddenSetSize: (System HiddenSetSpecifiers indexOf: 'PureExportSet')", Long.class));
            for (var i = 0; i < 50; i++) {
                assertThrows(GciException.class, () -> s.execute("1/0"));
                s.evaluate("3 + 4");
            }
            assertEquals(size.get(), s.evaluate("System hiddenSetSize: (System HiddenSetSpecifiers indexOf: 'PureExportSet')", Long.class));
        }
    }
}
