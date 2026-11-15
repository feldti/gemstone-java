package net.gs64.gci.mapping;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.gs64.gci.GciChangeFeed;
import net.gs64.gci.GciException;
import net.gs64.gci.Oop;

/**
 * Keeps a working {@link GsObjectContext} up to date with what other clients commit, with <b>one
 * session per client</b>: no listener session, no second context. Other clients publish their
 * changes to a {@link GciChangeFeed} ({@link GsObjectContext#setChangeFeed}); this client takes
 * them while it is idle and refreshes the same Java instances it works with.
 *
 * <p>A {@link #poll} first asks the server whether the doorbell rang: one cheap round trip that
 * reads the session's signal queue, without aborting and without touching the view. Only if it
 * rang (or every {@link #setFullCheckInterval full-check interval}, because signals are not
 * persistent) does it abort, take the published objects, refresh them in one load and commit the
 * take. Many commits of other clients between two polls cost one refresh.
 *
 * <p><b>Poll only while the client is idle</b>: no open transaction and no unsaved changes in the
 * context, because it aborts and commits the session. {@link #start} runs polls on a background
 * thread behind a gate the application provides. If a refreshed object has unsaved changes anyway,
 * the poll throws {@link GsUnsavedChangesException}, aborts, and the objects stay in the queue.
 *
 * <p>The consumer is registered with {@code skipOwnPublishes}: what this client commits itself does
 * not come back to it. The sync reads the session's whole signal queue; do not use session signals
 * on this session for anything else.
 */
public final class GsChangeSync implements AutoCloseable {
    private static final String DRAIN_SOURCE =
            "| n s | n := 0. [(s := System _signalFromGemStoneSession) notNil] whileTrue: [(s at: 2) = "
            + GciChangeFeed.SIGNAL_VALUE + " ifTrue: [n := n + 1]]. n";

    /**
     * What a poll took and refreshed.
     *
     * @param rang the doorbell rang (false: a full check or a forced poll)
     * @param published what the other clients published (also objects this context does not hold)
     * @param refreshed the Java objects that were loaded again (the same instances)
     * @param added mapped objects now in a collection of a refreshed object that were not before
     * @param removed mapped objects that were in a collection of a refreshed object and are no
     *     longer: candidates for deletion in application caches
     * @param reloadRequired more than the reload threshold was published: nothing was refreshed, reload
     * @param takeTime abort and take
     * @param refreshTime the refresh
     * @param commitTime committing the take
     */
    public record Result(boolean rang, List<Oop> published, List<Object> refreshed, List<Object> added, List<Object> removed,
            boolean reloadRequired, Duration takeTime, Duration refreshTime, Duration commitTime) {
        static final Result NOTHING = new Result(false, List.of(), List.of(), List.of(), List.of(), false, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        static final Result RANG_FOR_NOTHING = new Result(true, List.of(), List.of(), List.of(), List.of(), false, Duration.ZERO, Duration.ZERO, Duration.ZERO);

        /** Whether this poll took anything. */
        public boolean isEmpty() {
            return published.isEmpty();
        }
    }

    private final GsObjectContext context;
    private final GciChangeFeed feed;
    private final String consumer;
    private final Object polling = new Object();
    private long lastFullCheck = System.nanoTime();
    private volatile Duration fullCheckInterval = Duration.ofSeconds(30);
    private volatile int reloadThreshold = 50_000;
    private volatile GsLoadOptions refreshOptions = GsLoadOptions.DEFAULT.withStopAtLoadedObjects(true).withDepth(2);
    private volatile Consumer<Result> onChanged;
    private volatile Consumer<Exception> onError;
    private boolean retake;                 // a take was not committed (failure): take again without a doorbell
    private long polls, takes, takeCommitFailures;
    private Thread thread;
    private volatile boolean stopping;
    private final Object wake = new Object();
    private boolean wakeRequested;
    private volatile boolean closed;

    /**
     * @param context the client's working context; its session is used for everything
     * @param feed change feed on the same session
     * @param consumer key of this client in the feed, stable across restarts (e.g. machine + database)
     */
    public GsChangeSync(GsObjectContext context, GciChangeFeed feed, String consumer) {
        this.context = Objects.requireNonNull(context);
        this.feed = Objects.requireNonNull(feed);
        this.consumer = Objects.requireNonNull(consumer);
        if (feed.session() != context.session()) throw new IllegalArgumentException("The change feed must use the context's session.");
    }

    /** Even without a doorbell, take at least this often (signals are not persistent). Default 30 s. */
    public void setFullCheckInterval(Duration interval) {
        fullCheckInterval = Objects.requireNonNull(interval);
    }

    /** More published objects than this in one take: {@link Result#reloadRequired} instead of a refresh. Default 50,000. */
    public void setReloadThreshold(int threshold) {
        reloadThreshold = threshold;
    }

    /**
     * How the published objects are loaded again. Default: {@link GsLoadOptions#stopAtLoadedObjects}
     * (only the published objects and objects new to the context are read; references to objects
     * the context holds are linked) and traversal depth 2. Every client publishes each object it
     * changed, so nothing beyond them has to be read.
     */
    public void setRefreshOptions(GsLoadOptions options) {
        refreshOptions = Objects.requireNonNull(options);
    }

    /**
     * Called by a poll that took something, on the polling thread, while the gate of
     * {@link #start} is still held. Do not wait for the UI thread in the handler.
     */
    public void onChanged(Consumer<Result> handler) {
        onChanged = handler;
    }

    /** Called when a background poll failed; the published objects stay in the queue. */
    public void onError(Consumer<Exception> handler) {
        onError = handler;
    }

    /** Polls in total. */
    public synchronized long polls() {
        return polls;
    }

    /** Polls that took from the feed (doorbell, full check, forced). */
    public synchronized long takes() {
        return takes;
    }

    /** Takes whose commit failed (the objects came again with the next poll). */
    public synchronized long takeCommitFailures() {
        return takeCommitFailures;
    }

    /**
     * Registers this client in the feed (its own session gets the doorbell, its own publishes are
     * skipped), commits, and rings the other clients once, so idle ones renew their view and see
     * the new consumer. Call it once after login, while idle, before loading. A client that is in
     * a transaction right now does not see the new consumer until that transaction ends: its next
     * commit (only the first time this key registers) misses this client. Keep keys registered.
     */
    public void register() {
        var session = context.session();
        feed.register(consumer, session.gemSessionId(), true);
        session.commit();
        try {
            feed.ring();
        } catch (GciException e) {
            // Only a courtesy; the others renew their view at their next full check.
        }
    }

    /** {@link #poll(boolean)} without forcing. */
    public Result poll() {
        return poll(false);
    }

    /**
     * Takes and refreshes what other clients published, if the doorbell rang or a full check is
     * due. Call it only while idle.
     *
     * @param force take from the feed even if the doorbell did not ring
     * @throws GsUnsavedChangesException a refreshed object has unsaved changes; the session was
     *     aborted and the objects stay in the queue
     */
    public Result poll(boolean force) {
        if (closed) throw new IllegalStateException("The sync is closed.");
        synchronized (polling) {
            synchronized (this) {
                polls++;
            }
            var session = context.session();
            var rang = session.evaluate(DRAIN_SOURCE, Long.class) > 0;
            if (!rang && !force && !retake && System.nanoTime() - lastFullCheck < fullCheckInterval.toNanos()) return Result.NOTHING;
            synchronized (this) {
                takes++;
            }
            retake = true;                              // until this take is committed
            lastFullCheck = System.nanoTime();
            var t0 = System.nanoTime();
            session.abort();                            // see what the others committed
            var oops = feed.take(consumer);
            var t1 = System.nanoTime();
            if (oops.length == 0) {
                retake = false;
                return rang ? Result.RANG_FOR_NOTHING : Result.NOTHING;
            }
            var published = List.of(oops);
            List<Object> refreshed = List.of(), added = List.of(), removed = List.of();
            var reload = oops.length > reloadThreshold;
            if (!reload) {
                try {
                    var owners = context.refreshTargets(published);
                    var before = context.collectionElements(owners);
                    refreshed = context.refresh(published, refreshOptions);
                    var after = context.collectionElements(refreshed);
                    var a = new ArrayList<Object>();
                    for (var e : after) if (!before.contains(e)) a.add(e);
                    var r = new ArrayList<Object>();
                    for (var e : before) if (!after.contains(e)) r.add(e);
                    added = a;
                    removed = r;
                } catch (RuntimeException e) {
                    session.abort();                    // the taken objects go back into the queue; the next poll takes them
                    throw e;
                }
            }
            var t2 = System.nanoTime();
            // Make the take permanent. If it fails, the objects come again with the next poll.
            if (session.tryCommit()) retake = false;
            else {
                synchronized (this) {
                    takeCommitFailures++;
                }
                session.abort();
            }
            var result = new Result(rang, published, refreshed, added, removed, reload,
                    Duration.ofNanos(t1 - t0), Duration.ofNanos(t2 - t1), Duration.ofNanos(System.nanoTime() - t2));
            var handler = onChanged;
            if (handler != null) handler.accept(result);
            return result;
        }
    }

    /**
     * Polls in the background every {@code interval}, each time only if {@code tryEnterIdle}
     * answers true (the client is idle; it then holds the gate until {@code exitIdle}).
     */
    public synchronized void start(Duration interval, BooleanSupplier tryEnterIdle, Runnable exitIdle) {
        if (closed) throw new IllegalStateException("The sync is closed.");
        Objects.requireNonNull(tryEnterIdle);
        Objects.requireNonNull(exitIdle);
        if (thread != null) throw new IllegalStateException("Already started.");
        var ms = Math.max(1, interval.toMillis());
        thread = Thread.ofPlatform().daemon().name("gs-change-sync-" + consumer).start(() -> {
            while (!stopping) {
                synchronized (wake) {
                    if (!wakeRequested) {
                        try {
                            wake.wait(ms);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    wakeRequested = false;
                }
                if (stopping) break;
                if (!tryEnterIdle.getAsBoolean()) continue;
                try {
                    poll();
                } catch (GciException | IllegalStateException | GsMappingException e) {
                    var handler = onError;
                    if (!stopping && handler != null) handler.accept(e);
                } finally {
                    exitIdle.run();
                }
            }
        });
    }

    /** Asks the background thread to poll now instead of at the end of the interval. */
    public void pollSoon() {
        synchronized (wake) {
            wakeRequested = true;
            wake.notifyAll();
        }
    }

    /** Stops the background polling (waits for a running poll). The consumer stays registered. */
    public void stop() {
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
        }
        if (t == null) return;
        stopping = true;
        pollSoon();
        if (t != Thread.currentThread()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopping = false;
    }

    /** Stops polling. The consumer stays registered; see {@link GciChangeFeed#unregister}. */
    @Override
    public void close() {
        if (closed) return;
        stop();
        closed = true;
    }

    @Override
    public String toString() {
        return "GsChangeSync[" + consumer + ", polls " + Arrays.asList(polls(), takes()) + "]";
    }
}
