package net.gs64.gci;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Result of an asynchronous GCI call. {@link #cancel(boolean)} sends a soft break to the running
 * Smalltalk code; the future then completes with a {@link CancellationException} once the server
 * has stopped, and the session stays usable. A call that cannot be interrupted (commit, abort)
 * ignores the cancellation and completes normally.
 *
 * @param <T> the result type
 */
public final class GciFuture<T> extends CompletableFuture<T> {
    private volatile boolean cancelRequested;
    private final GciFuture<?> source;

    GciFuture() {
        this.source = null;
    }

    private GciFuture(GciFuture<?> source) {
        this.source = source;
    }

    /** Requests a soft break; does not complete the future by itself. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) return false;
        if (source != null) return source.cancel(mayInterruptIfRunning);
        cancelRequested = true;
        return true;
    }

    boolean cancelRequested() {
        return cancelRequested;
    }

    void completeCancelled(Throwable cause) {
        var e = new CancellationException("The GemStone call was interrupted by a soft break.");
        e.initCause(cause);
        completeExceptionally(e);
    }

    /** A future of {@code fn(result)} whose cancellation interrupts this call. */
    <U> GciFuture<U> map(Function<T, U> fn) {
        var next = new GciFuture<U>(this);
        whenComplete((value, error) -> {
            if (error != null) {
                next.completeExceptionally(error instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null ? ce.getCause() : error);
                return;
            }
            try {
                next.complete(fn.apply(value));
            } catch (Throwable t) {
                next.completeExceptionally(t);
            }
        });
        return next;
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
        return new CompletableFuture<>();
    }
}
