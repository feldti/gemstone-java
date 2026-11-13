package net.gs64.gci.mapping;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

import net.gs64.gci.GciChangeFeed;
import net.gs64.gci.GciException;
import net.gs64.gci.GciFuture;
import net.gs64.gci.GciSession;
import net.gs64.gci.GciSignalListener;
import net.gs64.gci.Oop;

/**
 * A read-only copy of an object graph in Java that follows the stone: it loads the graph once
 * (without change tracking) and then refreshes what writers publish to a {@link GciChangeFeed}.
 * Readers and updates are synchronized with a reader/writer lock.
 *
 * <p>The replica needs two sessions: the work session of the context (it loads, aborts, takes from
 * the feed and commits the take) and a listener session that does nothing else (its
 * {@link GciSignalListener} receives the feed's doorbell; the replica installs the listener's
 * signal handler). The feed object must use the work session.
 *
 * <p>An update: abort (see what the writers committed), take the published objects, refresh them
 * in one load, report them, and commit the take in the background (the next update waits for it),
 * so a change is visible without waiting for the stone's log write. If anything fails, it aborts,
 * and the objects stay in the queue. Updates run when the doorbell rings and, because signals are
 * not persistent, every {@link #setPollInterval poll interval}.
 *
 * <p>Read the graph only inside {@link #read}, which holds the read lock.
 *
 * @param <T> mapped class of the root
 */
public final class GsReplica<T> implements AutoCloseable {
    /**
     * What an update refreshed.
     *
     * @param entities the refreshed Java objects (owners of reported collections included)
     * @param published what the writers published (including objects the replica does not hold)
     */
    public record Change(List<Object> entities, List<Oop> published) {
    }

    private final GsObjectContext context;
    private final GciChangeFeed feed;
    private final String consumer;
    private final GciSignalListener listener;
    private final String rootSource;
    private final Class<T> type;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantLock updating = new ReentrantLock();
    private final GsLoadOptions options = GsLoadOptions.DEFAULT.withTrackChanges(false);
    private ScheduledExecutorService timer;
    private volatile Duration pollInterval = Duration.ofSeconds(5);
    private volatile Consumer<Change> onChanged;
    private volatile Consumer<Exception> onError;
    private T root;
    private volatile boolean closed;
    /** Commit of the last take, still running. */
    private GciFuture<Boolean> pendingCommit;

    /**
     * @param context context that holds the replica; its session is the work session; owned by the caller
     * @param feed change feed on the same session
     * @param consumer key of this replica in the feed, stable across restarts (one per running replica)
     * @param listener listener on a separate session
     * @param rootSource Smalltalk expression that answers the root
     * @param type mapped class of the root
     */
    public GsReplica(GsObjectContext context, GciChangeFeed feed, String consumer, GciSignalListener listener, String rootSource, Class<T> type) {
        this.context = Objects.requireNonNull(context);
        this.feed = Objects.requireNonNull(feed);
        this.consumer = Objects.requireNonNull(consumer);
        this.listener = Objects.requireNonNull(listener);
        this.rootSource = Objects.requireNonNull(rootSource);
        this.type = Objects.requireNonNull(type);
    }

    /** Fallback interval for updates without a doorbell (signals are not persistent). Set before {@link #start}. */
    public void setPollInterval(Duration interval) {
        pollInterval = interval;
    }

    /** Called after an update changed objects, outside the locks, on the thread that ran the update. */
    public void onChanged(Consumer<Change> handler) {
        onChanged = handler;
    }

    /** Called when an update failed; the published objects stay in the queue. */
    public void onError(Consumer<Exception> handler) {
        onError = handler;
    }

    /**
     * Registers the consumer, loads the graph and starts listening. Changes committed after the
     * registration are refreshed by the next update (some objects may be refreshed twice).
     */
    public void start() {
        if (closed) throw new IllegalStateException("The replica is closed.");
        if (feed.session() != context.session()) throw new IllegalStateException("The change feed must use the context's session.");
        var session = context.session();
        feed.register(consumer, listener.sessionId());
        session.commit(); // the queue collects from here on
        lock.writeLock().lock();
        try {
            root = context.load(rootSource, type, options);
        } finally {
            lock.writeLock().unlock();
        }
        listener.onSignals(signals -> {
            if (signals.messages().stream().anyMatch(m -> m.value() == GciChangeFeed.SIGNAL_VALUE)) safeUpdate();
        });
        listener.start();
        timer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("gs-replica-" + consumer).unstarted(r));
        var ms = pollInterval.toMillis();
        timer.scheduleWithFixedDelay(this::safeUpdate, ms, ms, TimeUnit.MILLISECONDS);
        safeUpdate(); // what was committed during the load
    }

    /** Runs {@code read} on the root under the read lock. */
    public <R> R read(Function<T, R> read) {
        lock.readLock().lock();
        try {
            if (root == null) throw new IllegalStateException("Call start first.");
            return read.apply(root);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Runs {@code read} on the root under the read lock. */
    public void readWith(Consumer<T> read) {
        read(r -> {
            read.accept(r);
            return null;
        });
    }

    /**
     * Takes what was published for this replica and refreshes it now; answers the refreshed
     * objects. Normally the doorbell and the timer call it.
     */
    public List<Object> update() {
        if (closed) throw new IllegalStateException("The replica is closed.");
        var session = context.session();
        List<Object> refreshed;
        Oop[] oops;
        updating.lock();
        try {
            endTransaction(session);
            oops = feed.take(consumer);
            if (oops.length == 0) return List.of();
            lock.writeLock().lock();
            try {
                refreshed = context.refresh(Arrays.asList(oops), options);
            } catch (RuntimeException e) {
                session.abort(); // the taken objects go back into the queue
                throw e;
            } finally {
                lock.writeLock().unlock();
            }
            // Make the take permanent in the background: the commit waits for the stone's log write
            // (several milliseconds), which would otherwise delay every change. The next update waits for it.
            pendingCommit = session.tryCommitAsync();
        } finally {
            updating.unlock();
        }
        var handler = onChanged;
        if (!refreshed.isEmpty() && handler != null) handler.accept(new Change(List.copyOf(refreshed), List.of(oops)));
        return refreshed;
    }

    /** Waits for the commit of the last take (if any), then aborts for the latest view. */
    private void endTransaction(GciSession session) {
        var commit = pendingCommit;
        if (commit != null) {
            pendingCommit = null;
            try {
                commit.join(); // false or an error: those objects come again
            } catch (RuntimeException e) {
                // ignored
            }
        }
        session.abort(); // the commit's view is already old
    }

    private void safeUpdate() {
        if (closed) return;
        try {
            update();
        } catch (GciException | IllegalStateException | GsMappingException e) {
            if (closed) return;
            var handler = onError;
            if (handler != null) handler.accept(e);
        }
    }

    /** Stops listening and updating. The consumer stays registered; see {@link GciChangeFeed#unregister}. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        listener.onSignals(null);
        listener.stop();
        if (timer != null) timer.shutdownNow();
        updating.lock(); // let a running update finish
        try {
            if (pendingCommit != null && context.session().isLoggedIn()) endTransaction(context.session());
        } catch (GciException e) {
            // Not committed: those objects are delivered again to the next replica with this key.
        } finally {
            updating.unlock();
        }
    }
}
