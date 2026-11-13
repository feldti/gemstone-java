package net.gs64.gci;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import net.gs64.gci.internal.GciNative;

/**
 * Receives notifications from the server on a session of its own: changes other sessions commit
 * to watched objects (the notify set), and signals other sessions send with
 * {@link GciSession#sendSignal}.
 *
 * <p>The server cannot call a client. The listener either waits on the server ({@link #await},
 * {@link #start()}): one call that returns as soon as something arrives, typically within a
 * millisecond of the commit or signal. Or it asks ({@link #poll()}, {@link #start(Duration)}): one
 * round trip that reads everything that arrived.
 *
 * <p>Use a session that does nothing else: every poll and every wait aborts it (so it never holds
 * an old view of the repository for long), and a wait occupies it. A notification is a hint, not
 * the data: to see a change, the session that works with the objects must abort (or commit) and
 * load them again.
 */
public final class GciSignalListener implements AutoCloseable {
    /**
     * {@code [:ms | …]}: aborts (fresh view, releases the old commit record), empties both queues
     * and, if nothing was there and {@code ms} &gt; 0, waits up to {@code ms} milliseconds for a
     * signal, then empties the queues again. Answers {@code {changed objects. {serial. id. value. text} …}}.
     *
     * <p>Signals always go into two queues ({@code _signalFromGemStoneSession}, {@code signaledObjects}).
     * Not {@code signalFromGemStoneSession} (deprecated): it turns the sender's serial into a
     * GsSession, which is nil in 3.7 once the sender has logged out. Only while waiting does the
     * listener let the VM raise them ({@code enableSignalling}), to wake the semaphore: a signal
     * raised while the session is idle comes back as error 6008/6010 from the next GCI call, and
     * that is not reliable (a session signal followed by a commit notification gives only the 6008
     * error, the session signal is gone; 3.6.5 and 3.7.6). A soft break (used to interrupt the
     * wait) is caught: what was read so far is still answered.
     */
    private static final String LISTEN_SOURCE = """
            [:ms | | objs sigs add drain wake |
             objs := IdentitySet new. sigs := OrderedCollection new. wake := Semaphore new.
             add := [:serial :int :msg | sigs add: {serial.
               [(GsSession sessionIdOfSerial: serial) ifNotNil: [:id | id = 0 ifFalse: [id]]] on: Error do: [:x | x return: nil].
               int. msg}].
             drain := [| s |
               objs addAll: System signaledObjects.
               [(s := System _signalFromGemStoneSession) notNil]
                 whileTrue: [add value: (s at: 1) value: (s at: 2) value: (s at: 3)]].
             [[System abortTransaction.
               drain value.
               (ms > 0 and: [objs isEmpty and: [sigs isEmpty]]) ifTrue: [
                 [InterSessionSignal enableSignalling. ObjectsCommittedNotification enableSignalling.
                  wake waitForMilliseconds: ms]
                   ensure: [InterSessionSignal disableSignalling. ObjectsCommittedNotification disableSignalling]].
               drain value]
                on: Break do: [:e | e return: nil].
              {objs asArray. sigs asArray}]
               on: InterSessionSignal, ObjectsCommittedNotification
               do: [:e | (e isKindOf: InterSessionSignal)
                     ifTrue: [add value: e sendingSessionSerial value: e sentInt value: e sentMessage].
                   wake signal.
                   e resume: nil]]
            """;

    private final GciSession session;
    private final int sessionId;
    private final Oop listenBlock;
    private final Object loopLock = new Object();
    /** One user of the session at a time: a wait, a poll or a change of the notify set. */
    private final ReentrantLock gate = new ReentrantLock(true);
    private final AtomicInteger waitingForGate = new AtomicInteger();
    /** The wait in progress, to interrupt it (a soft break the wait block catches). */
    private GciFuture<Long> currentWait;
    /** Signals caught outside a poll, delivered with the next one. */
    private final List<GciSessionSignal> pending = new ArrayList<>();
    /** Changed objects of an interrupted wait, delivered with the next poll (the server reports them only once). */
    private final List<Oop> pendingObjects = new ArrayList<>();
    private Thread loop;
    private volatile boolean stopping;
    private volatile boolean closed;
    private volatile Duration maxWait = Duration.ofSeconds(5);
    private volatile Consumer<GciSignals> onSignals;
    private volatile Consumer<Exception> onError;
    private volatile Executor executor;

    /** Prepares {@code session} for receiving signals. The caller still owns (and closes) the session. */
    public GciSignalListener(GciSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.sessionId = session.gemSessionId();
        var none = new ArrayList<GciSessionSignal>();
        // Raising stays off except while waiting (see LISTEN_SOURCE). Signals sent before this are
        // still captured if they surface as an error.
        release(session.callCapturingSignals(() -> GciNative.GciExecuteStr(
                "System disableSignaledObjectsError; disableSignaledGemStoneSessionError. true", Oop.NIL.value()), none));
        listenBlock = session.callCapturingSignals(() -> GciNative.GciExecuteStr(LISTEN_SOURCE, Oop.NIL.value()), none);
        pending.addAll(none);
    }

    /** The listener's session. */
    public GciSession session() {
        return session;
    }

    /** The address other sessions send signals to ({@link GciSession#sendSignal}). */
    public int sessionId() {
        return sessionId;
    }

    /**
     * The longest a single wait of {@link #start()} lasts before it aborts and waits again
     * (default 5 seconds). The listener's view of the repository is at most this old.
     */
    public void setMaxWait(Duration value) {
        if (value.toMillis() < 1) throw new IllegalArgumentException("maxWait must be at least one millisecond.");
        maxWait = value;
    }

    public Duration maxWait() {
        return maxWait;
    }

    /** Called by the background loop when something arrived. */
    public void onSignals(Consumer<GciSignals> handler) {
        onSignals = handler;
    }

    /** Called by the background loop when a wait or poll fails; after a fatal error the loop stops. */
    public void onError(Consumer<Exception> handler) {
        onError = handler;
    }

    /** Runs the handlers through {@code executor} (e.g. {@code SwingUtilities::invokeLater}); null: on the loop thread. */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * Reports changes other sessions commit to these objects ({@code System addAllToNotifySet:}).
     * Aborts the listener session first, so that it sees recently committed objects. Interrupts a
     * wait in progress, which then goes on.
     */
    public void watch(Oop... objects) {
        notifySet("addAllToNotifySet:", objects);
    }

    /** Stops reporting changes to these objects. */
    public void unwatch(Oop... objects) {
        notifySet("removeAllFromNotifySet:", objects);
    }

    /** Stops reporting changes to any object. */
    public void unwatchAll() {
        exclusive(() -> release(session.callCapturingSignals(
                () -> GciNative.GciExecuteStr("System clearNotifySet. true", Oop.NIL.value()), pending)));
    }

    private void notifySet(String selector, Oop[] objects) {
        if (objects.length == 0) return;
        exclusive(() -> GciSession.locked(() -> {
            session.abort(); // objects committed after the listener's last abort are not visible to it yet
            // Creating and filling the Array runs no Smalltalk, so no signal can interfere.
            var array = session.newObject(KnownClasses.ARRAY, objects.length);
            try {
                session.storeVaryingOops(array, 1, objects);
                var system = session.systemClass();
                release(session.callCapturingSignals(() -> {
                    try (var arena = java.lang.foreign.Arena.ofConfined()) {
                        return GciNative.GciPerform(system.value(), selector,
                                arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_LONG, array.value()), 1);
                    }
                }, pending));
            } finally {
                session.release(array);
            }
        }));
    }

    /** Runs {@code action} with the session, interrupting a wait in progress. */
    private void exclusive(Runnable action) {
        if (closed) throw new IllegalStateException("The listener is closed.");
        waitingForGate.incrementAndGet();
        synchronized (loopLock) {
            if (currentWait != null) currentWait.cancel(true);
        }
        gate.lock();
        waitingForGate.decrementAndGet();
        try {
            action.run();
        } finally {
            gate.unlock();
        }
    }

    private void release(Oop oop) {
        if (!oop.isSpecial()) session.release(oop);
    }

    /**
     * Everything that arrived since the last poll: changed watched objects and signals of other
     * sessions. Also aborts the listener session. One round trip (two if something arrived).
     */
    public GciSignals poll() {
        var result = new GciSignals[1];
        exclusive(() -> GciSession.locked(() -> {
            var received = takePending();
            var answer = session.callCapturingSignals(() -> {
                try (var arena = java.lang.foreign.Arena.ofConfined()) {
                    return GciNative.GciPerform(listenBlock.value(), "value:",
                            arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_LONG, Oop.fromSmallInteger(0).value()), 1);
                }
            }, received);
            result[0] = decode(answer, received);
        }));
        return result[0];
    }

    /**
     * Waits on the server until something arrives (then returns it, typically within a
     * millisecond of the commit or signal) or {@code timeout} has passed (then returns an empty
     * result). Aborts the listener session first. The GCI is free meanwhile: other sessions keep
     * working. Blocks the calling thread.
     *
     * @throws IllegalStateException the background loop is running
     * @throws InterruptedException the thread was interrupted; anything that arrived comes with the next poll or wait
     */
    public GciSignals await(Duration timeout) throws InterruptedException {
        if (closed) throw new IllegalStateException("The listener is closed.");
        synchronized (loopLock) {
            if (loop != null && Thread.currentThread() != loop) throw new IllegalStateException("The listener is already running in the background.");
        }
        return awaitCore(timeout);
    }

    private GciSignals awaitCore(Duration timeout) throws InterruptedException {
        var ms = Math.max(1, timeout.toMillis());
        gate.lockInterruptibly();
        var received = takePending();
        try {
            GciFuture<Long> call;
            synchronized (loopLock) {
                var wait = waitingForGate.get() > 0 || stopping || !received.isEmpty() || !pendingObjects.isEmpty() ? 0 : ms;
                call = session.performCapturingSignalsAsync(listenBlock, "value:", () -> new Oop[] {Oop.fromSmallInteger(wait)}, received);
                currentWait = call;
            }
            long answer;
            try {
                answer = call.get();
            } catch (CancellationException e) {
                // Interrupted before the block's handler was in place: nothing was read yet.
                return new GciSignals(List.of(), received);
            } catch (InterruptedException e) {
                call.cancel(true); // soft break; the block answers what it read
                try {
                    var read = new Oop(call.join());
                    keep(GciSession.locked(() -> decode(read, received)));
                } catch (java.util.concurrent.CompletionException | CancellationException ignored) {
                    keepMessages(received);
                }
                throw e;
            } catch (ExecutionException e) {
                keepMessages(received);
                if (e.getCause() instanceof RuntimeException r) throw r;
                throw new GciException(String.valueOf(e.getCause()));
            } finally {
                synchronized (loopLock) {
                    currentWait = null;
                }
            }
            return GciSession.locked(() -> decode(new Oop(answer), received));
        } finally {
            gate.unlock();
        }
    }

    private List<GciSessionSignal> takePending() {
        synchronized (loopLock) {
            var received = new ArrayList<>(pending);
            pending.clear();
            return received;
        }
    }

    private void keepMessages(List<GciSessionSignal> messages) {
        synchronized (loopLock) {
            pending.addAll(0, messages);
        }
    }

    /** Changed objects are reported again by the server only if changed again: keep them for the next call. */
    private void keep(GciSignals signals) {
        synchronized (loopLock) {
            pending.addAll(0, signals.messages());
            pendingObjects.addAll(0, signals.changedObjects());
        }
    }

    /** Reads the listen block's answer and releases it. Under the GCI lock. */
    private GciSignals decode(Oop result, List<GciSessionSignal> signals) {
        try {
            var t = session.traverse(result, 4); // result → {objs. sigs} → signal → text
            var parts = t.get(result).indexed();
            List<Oop> changed;
            synchronized (loopLock) {
                changed = new ArrayList<>(pendingObjects);
                pendingObjects.clear();
            }
            for (var o : t.get(parts.get(0)).indexed()) if (!changed.contains(o)) changed.add(o);
            for (var sig : t.get(parts.get(1)).indexed()) {
                var f = t.get(sig).indexed();
                signals.add(new GciSessionSignal(f.get(0).isSmallInteger() ? f.get(0).toSmallInteger() : 0,
                        f.get(1).isSmallInteger() ? f.get(1).toSmallInteger() : null,
                        f.get(2).isSmallInteger() ? f.get(2).toSmallInteger() : 0,
                        t.toValue(f.get(3)) instanceof String text ? text : ""));
            }
            return new GciSignals(changed, signals);
        } finally {
            release(result);
        }
    }

    /**
     * Waits for notifications in the background ({@link #await}, at most {@link #maxWait()} at a
     * time) until {@link #stop} or {@link #close}, and calls the {@link #onSignals} handler as soon
     * as something arrives. The recommended way: low latency, one round trip per maxWait while
     * nothing happens.
     */
    public void start() {
        startLoop(null);
    }

    /**
     * Polls every {@code interval} in the background ({@link #poll}) until {@link #stop} or
     * {@link #close}. Latency up to the interval; {@link #start()} is usually the better choice.
     */
    public void start(Duration interval) {
        if (interval.toMillis() < 1) throw new IllegalArgumentException("The interval must be at least one millisecond.");
        startLoop(interval);
    }

    private void startLoop(Duration interval) {
        if (closed) throw new IllegalStateException("The listener is closed.");
        synchronized (loopLock) {
            if (loop != null) throw new IllegalStateException("The listener is already running in the background.");
            stopping = false;
            loop = Thread.ofPlatform().daemon().name("gci-signal-listener-" + sessionId).unstarted(() -> runLoop(interval));
            loop.start();
        }
    }

    private void runLoop(Duration interval) {
        while (!stopping) {
            GciSignals signals;
            try {
                if (interval != null) {
                    Thread.sleep(interval);
                    if (stopping) return;
                    signals = poll();
                } else {
                    if (waitingForGate.get() > 0) Thread.sleep(1); // let watch etc. go first
                    signals = awaitCore(maxWait);
                }
            } catch (InterruptedException e) {
                return;
            } catch (GciException | IllegalStateException e) {
                if (stopping) return;
                var lost = !session.isLoggedIn() || (e instanceof GciException g && g.isFatal());
                deliver(onError, e);
                if (lost) return;
                try {
                    Thread.sleep(interval != null ? interval : Duration.ofMillis(100));
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            if (!signals.isEmpty()) {
                if (stopping) {
                    keep(signals); // stopped meanwhile: the next poll or wait gets them
                    return;
                }
                deliver(onSignals, signals);
            }
        }
    }

    private <T> void deliver(Consumer<T> handler, T value) {
        if (handler == null) return;
        var ex = executor;
        if (ex != null) ex.execute(() -> handler.accept(value));
        else handler.accept(value);
    }

    /**
     * Stops the background loop and waits for a running wait or poll to finish. Called from a
     * handler on the loop thread, it only ends the loop (waiting there would wait for itself).
     */
    public void stop() {
        Thread t;
        synchronized (loopLock) {
            t = loop;
            if (t == null) return;
            stopping = true;
            if (currentWait != null) currentWait.cancel(true);
            loop = null;
        }
        if (Thread.currentThread() == t) return;
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops the background loop and releases the listener's objects; the session stays logged in. */
    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
        try {
            if (session.isLoggedIn()) session.release(listenBlock);
        } catch (GciException | IllegalStateException e) {
            // the session is broken or still busy; the gem drops its export set at logout
        }
    }
}
