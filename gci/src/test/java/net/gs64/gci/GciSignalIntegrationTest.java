package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import net.gs64.gci.mapping.GsClass;
import net.gs64.gci.mapping.GsInstVar;
import net.gs64.gci.mapping.GsObjectContext;

/**
 * Notifications between sessions: a listener session, a worker session that commits changes and
 * sends signals. Commits a test object under a unique key in UserGlobals and removes it again.
 */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GciSignalIntegrationTest {
    private GciSession w;
    private GciSession listenerSession;
    private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void setUp() {
        w = StoneEnv.login();
        listenerSession = StoneEnv.login();
    }

    @AfterEach
    void tearDown() {
        if (w.isLoggedIn()) {
            w.abort();
            w.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]. true");
            w.commit();
        }
        w.close();
        listenerSession.close();
    }

    private Oop commitTestObject() {
        var oop = w.execute("UserGlobals at: #" + key + " put: (Array new: 1)");
        w.commit();
        return oop;
    }

    private void change(Object value) {
        w.execute("(UserGlobals at: #" + key + ") at: 1 put: " + value + ". true");
        w.commit();
    }

    /** Polls until something arrived (signals travel through the stone asynchronously). */
    private static GciSignals pollUntil(GciSignalListener listener, Predicate<GciSignals> done) {
        var all = new ArrayList<GciSessionSignal>();
        var changed = new ArrayList<Oop>();
        var until = System.nanoTime() + 10_000_000_000L;
        while (true) {
            var s = listener.poll();
            all.addAll(s.messages());
            changed.addAll(s.changedObjects());
            var sofar = new GciSignals(changed, all);
            if (done.test(sofar) || System.nanoTime() > until) return sofar;
            sleep(50);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link GciSignalListener#await} on another thread. */
    private static CompletableFuture<GciSignals> awaitAsync(GciSignalListener listener, Duration timeout) {
        var f = new CompletableFuture<GciSignals>();
        Thread.ofPlatform().start(() -> {
            try {
                f.complete(listener.await(timeout));
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private static <T> T within(CompletableFuture<T> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    @StoneTest
    void changesToWatchedObjectsAreReported() {
        var obj = commitTestObject();
        try (var listener = new GciSignalListener(listenerSession)) {
            listener.watch(obj);
            assertTrue(listener.poll().isEmpty());
            change(42);
            var signals = pollUntil(listener, s -> !s.changedObjects().isEmpty());
            assertEquals(List.of(obj), signals.changedObjects());
            assertTrue(signals.messages().isEmpty());
            assertTrue(listener.poll().isEmpty()); // reported once
            // The listener aborted: it sees the committed state.
            assertEquals(42L, listener.session().evaluate("(UserGlobals at: #" + key + ") at: 1", Long.class));
            listener.unwatch(obj);
            change(43);
            sleep(300);
            assertTrue(listener.poll().isEmpty());
        }
    }

    @StoneTest
    void noSignalIsLost() {
        try (var listener = new GciSignalListener(listenerSession)) {
            var serial = w.evaluate("GsSession currentSession serialNumber", Long.class);
            var received = new ArrayList<GciSessionSignal>();
            // Several rounds: signals that arrive while the listener is idle surface as GCI errors at
            // the start of the next call, others inside the drain block; both must be delivered.
            for (var round = 0; round < 4; round++) {
                for (var i = 1; i <= 5; i++) w.sendSignal(listener.sessionId(), round * 10 + i, "Grüße " + (round * 10 + i));
                sleep(round * 100L);
                received.addAll(pollUntil(listener, s -> s.messages().size() >= 5).messages());
            }
            var expected = IntStream.range(0, 4).flatMap(r -> IntStream.rangeClosed(r * 10 + 1, r * 10 + 5)).mapToObj(i -> (long) i).toList();
            assertEquals(expected, received.stream().map(GciSessionSignal::value).toList());
            for (var s : received) {
                assertEquals("Grüße " + s.value(), s.message());
                assertEquals(serial, s.senderSerial());
            }
        }
    }

    @StoneTest
    void signalMessagesAreLatin1() {
        try (var listener = new GciSignalListener(listenerSession)) {
            assertThrows(IllegalArgumentException.class, () -> w.sendSignal(listener.sessionId(), 1, "5 €"));
            w.sendSignal(listener.sessionId(), 2, "Größe ½ ÿ");
            var messages = pollUntil(listener, s -> !s.messages().isEmpty()).messages();
            assertEquals(1, messages.size());
            assertEquals("Größe ½ ÿ", messages.get(0).message());
        }
    }

    @StoneTest
    void backgroundPollingCallsTheHandler() throws Exception {
        var obj = commitTestObject();
        try (var listener = new GciSignalListener(listenerSession)) {
            listener.watch(obj);
            var got = new java.util.concurrent.atomic.AtomicReference<>(new CompletableFuture<GciSignals>());
            listener.onSignals(s -> got.get().complete(s));
            listener.start(Duration.ofMillis(50));
            w.sendSignal(listener.sessionId(), 7, "hello");
            var first = within(got.get());
            assertEquals(1, first.messages().size());
            assertEquals(7L, first.messages().get(0).value());
            assertEquals("hello", first.messages().get(0).message());
            // The worker keeps working while the listener polls in the background.
            got.set(new CompletableFuture<>());
            change(1);
            assertEquals(List.of(obj), within(got.get()).changedObjects());
            listener.stop();
        }
    }

    @StoneTest
    void aWaitReturnsAsSoonAsSomethingArrives() throws Exception {
        var obj = commitTestObject();
        try (var listener = new GciSignalListener(listenerSession)) {
            listener.watch(obj);
            var start = System.nanoTime();
            assertTrue(listener.await(Duration.ofMillis(300)).isEmpty()); // nothing: times out
            var ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 250 && ms < 5000, "took " + ms);
            // The GCI is free while the listener waits: the worker commits meanwhile.
            var wait = awaitAsync(listener, Duration.ofSeconds(20));
            sleep(200);
            start = System.nanoTime();
            change(5);
            assertEquals(List.of(obj), within(wait).changedObjects());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000); // not the 20 s timeout
            wait = awaitAsync(listener, Duration.ofSeconds(20));
            sleep(200);
            w.sendSignal(listener.sessionId(), 3, "wake up");
            var m = within(wait).messages();
            assertEquals(1, m.size());
            assertEquals(3L, m.get(0).value());
            assertEquals("wake up", m.get(0).message());
        }
    }

    @StoneTest
    void noSignalIsLostWhileWaiting() throws Exception {
        try (var listener = new GciSignalListener(listenerSession)) {
            var received = new ArrayList<GciSessionSignal>();
            var all = new CompletableFuture<Boolean>();
            listener.onSignals(s -> {
                synchronized (received) {
                    received.addAll(s.messages());
                    if (received.size() >= 40) all.complete(true);
                }
            });
            listener.start();
            // Bursts and pauses: signals arrive while waiting, while reading and between two waits.
            for (var round = 0; round < 8; round++) {
                for (var i = 1; i <= 5; i++) w.sendSignal(listener.sessionId(), round * 10 + i, "s");
                sleep(round * 15L);
            }
            within(all);
            listener.stop();
            synchronized (received) {
                var expected = IntStream.range(0, 8).flatMap(r -> IntStream.rangeClosed(r * 10 + 1, r * 10 + 5)).mapToObj(i -> (long) i).toList();
                assertEquals(expected, received.stream().map(GciSessionSignal::value).toList());
            }
        }
    }

    @StoneTest
    void watchAndPollInterruptAWaitInTheBackground() throws Exception {
        var obj = commitTestObject();
        try (var listener = new GciSignalListener(listenerSession)) {
            listener.setMaxWait(Duration.ofSeconds(30));
            var got = new CompletableFuture<GciSignals>();
            listener.onSignals(got::complete);
            listener.start();
            sleep(300); // the background wait is in progress
            var start = System.nanoTime();
            listener.watch(obj); // interrupts it, does not wait 30 s
            assertTrue(listener.poll().isEmpty());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
            change(6);
            assertEquals(List.of(obj), within(got).changedObjects());
            start = System.nanoTime();
            listener.stop();
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000);
            assertTrue(listener.poll().isEmpty()); // usable afterwards
        }
    }

    /**
     * A signal and a commit notification both pending while the listener is idle: the next call
     * fails with one error, and reading that error must not run into the other.
     */
    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "true, true", "false, true"})
    void aSignalAndACommitArrivingTogether(boolean signalFirst, boolean waiting) throws Exception {
        var obj = commitTestObject();
        try (var listener = new GciSignalListener(listenerSession)) {
            listener.watch(obj);
            for (var round = 1; round <= 6; round++) {
                // Rounds 1-3: both arrive while the listener is idle; rounds 4-6 (waiting only): during a wait.
                var during = waiting && round > 3 ? awaitAsync(listener, Duration.ofSeconds(10)) : null;
                if (during != null) sleep(100);
                if (signalFirst) w.sendSignal(listener.sessionId(), round, "round " + round);
                change(round);
                if (!signalFirst) w.sendSignal(listener.sessionId(), round, "round " + round);
                sleep(200);
                var messages = new ArrayList<GciSessionSignal>();
                var changed = new ArrayList<Oop>();
                if (during != null) {
                    var first = within(during);
                    messages.addAll(first.messages());
                    changed.addAll(first.changedObjects());
                }
                if (messages.isEmpty() || changed.isEmpty()) {
                    var m = messages.size();
                    var c = changed.size();
                    var s = waiting && during == null ? listener.await(Duration.ofSeconds(5))
                            : pollUntil(listener, x -> x.messages().size() + m > 0 && x.changedObjects().size() + c > 0);
                    messages.addAll(s.messages());
                    changed.addAll(s.changedObjects());
                }
                assertEquals(1, messages.size(), "round " + round);
                assertEquals("round " + round, messages.get(0).message());
                assertEquals(List.of(obj), changed);
            }
        }
    }

    /**
     * The sender runs on its gem in parallel (executeAsync), so signals arrive at any point of the
     * listener's Smalltalk: during the abort, while reading the queues, while building the answer.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noSignalIsLostUnderLoad(boolean waiting) throws Exception {
        var count = 300;
        try (var listener = new GciSignalListener(listenerSession)) {
            var sender = w.executeAsync("1 to: " + count + " do: [:i | System sendSignal: i to: " + listener.sessionId()
                    + " withMessage: 's'. (Delay forMilliseconds: 1) wait]. true");
            var received = new ArrayList<Long>();
            var until = System.nanoTime() + 30_000_000_000L;
            while (received.size() < count && System.nanoTime() < until) {
                var s = waiting ? listener.await(Duration.ofMillis(500)) : listener.poll();
                for (var m : s.messages()) received.add(m.value());
            }
            sender.get(30, TimeUnit.SECONDS);
            assertEquals(IntStream.rangeClosed(1, count).mapToObj(i -> (long) i).toList(), received);
        }
    }

    /** In 3.7 the deprecated signalFromGemStoneSession fails for them (nil GsSession). */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signalsOfASenderThatHasLoggedOut(boolean waiting) throws Exception {
        try (var listener = new GciSignalListener(listenerSession)) {
            long serial;
            try (var gone = StoneEnv.login()) {
                serial = gone.evaluate("GsSession currentSession serialNumber", Long.class);
                gone.sendSignal(listener.sessionId(), 1, "bye");
            }
            w.sendSignal(listener.sessionId(), 2, "still here");
            sleep(300);
            var s = waiting ? listener.await(Duration.ofSeconds(5)) : listener.poll();
            assertEquals(2, s.messages().size());
            assertEquals(serial, s.messages().get(0).senderSerial());
            assertEquals("bye", s.messages().get(0).message());
            assertEquals(Long.valueOf(w.gemSessionId()), s.messages().get(1).senderSessionId());
            assertEquals("still here", s.messages().get(1).message());
        }
    }

    @StoneTest
    void stopInsideTheHandlerDoesNotHang() throws Exception {
        var listener = new GciSignalListener(listenerSession);
        var stopped = new CompletableFuture<Boolean>();
        listener.onSignals(s -> {
            listener.stop(); // runs on the loop itself
            listener.close();
            stopped.complete(true);
        });
        listener.start();
        var start = System.nanoTime();
        w.sendSignal(listener.sessionId(), 1, "stop");
        assertTrue(within(stopped));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
        sleep(100);
        assertEquals(7L, listenerSession.evaluate("3 + 4"));
    }

    @StoneTest
    void anInterruptedWaitKeepsWhatArrived() throws Exception {
        try (var listener = new GciSignalListener(listenerSession)) {
            var waiter = Thread.ofPlatform().start(() -> {
                try {
                    listener.await(Duration.ofSeconds(30));
                } catch (InterruptedException expected) {
                    // the wait ends; what arrived comes with the next poll
                }
            });
            sleep(300);
            waiter.interrupt();
            waiter.join(5000);
            assertTrue(!waiter.isAlive());
            w.sendSignal(listener.sessionId(), 1, "after");
            var messages = pollUntil(listener, s -> !s.messages().isEmpty()).messages();
            assertEquals(1, messages.size());
            assertEquals("after", messages.get(0).message());
        }
    }

    @GsClass("GsSignalTestAddress")
    static final class SignalTestAddress {
        @GsInstVar("street") String street;
        @GsInstVar("city") String city;
    }

    @StoneTest
    void reportedObjectsLeadBackToLoadedEntities() {
        // A worker that loaded an object refreshes it after the listener reported a change.
        w.execute("""
                | a | a := Object subclass: 'GsSignalTestAddress' instVarNames: #('street' 'city') classVars: #()
                    classInstVars: #() poolDictionaries: #() inDictionary: UserGlobals.
                UserGlobals at: #%s put: (a new instVarNamed: 'city' put: 'Bonn'; yourself). true
                """.formatted(key));
        w.commit();
        try (var ctx = new GsObjectContext(w)) {
            var address = ctx.load("UserGlobals at: #" + key, SignalTestAddress.class);
            var oop = ctx.oopFor(address);
            try (var listener = new GciSignalListener(listenerSession); var other = StoneEnv.login()) {
                listener.watch(oop);
                other.execute("(UserGlobals at: #" + key + ") instVarNamed: 'city' put: 'Köln'. true");
                other.commit();
                var changed = pollUntil(listener, s -> !s.changedObjects().isEmpty()).changedObjects();
                assertEquals(1, changed.size());
                assertSame(address, ctx.entityFor(changed.get(0)));
                assertEquals("Bonn", address.city);
                w.abort();
                assertEquals(List.of(address), ctx.refresh(changed));
                assertEquals("Köln", address.city);
            }
        } finally {
            w.abort();
            w.execute("UserGlobals removeKey: #GsSignalTestAddress ifAbsent: [nil]. true");
            w.commit();
        }
    }
}
