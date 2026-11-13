package net.gs64.gci;

import java.util.List;

/**
 * A GemStone error reported through {@code GciErr}.
 *
 * <p>{@link #context()}, {@link #exceptionObj()} and {@link #args()} are informational: for
 * non-fatal errors the session has already cleared the stack and released these OOPs, so they
 * must not be used for further GCI calls.
 */
public class GciException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int number;
    private final boolean fatal;
    private final String reason;
    private final long context;
    private final long exceptionObj;
    private final long[] args;

    public GciException(String message) {
        super(message);
        number = 0;
        fatal = false;
        reason = "";
        context = 0;
        exceptionObj = 0;
        args = new long[0];
    }

    GciException(int number, boolean fatal, String message, String reason, long context, long exceptionObj, List<Long> args) {
        super("GemStone error " + number + ": " + message);
        this.number = number;
        this.fatal = fatal;
        this.reason = reason;
        this.context = context;
        this.exceptionObj = exceptionObj;
        this.args = args.stream().mapToLong(Long::longValue).toArray();
    }

    /** GemStone error number. */
    public int number() {
        return number;
    }

    /** The session is lost. */
    public boolean isFatal() {
        return fatal;
    }

    public String reason() {
        return reason;
    }

    /** The GsProcess of the failed execution (already cleared), or nil. */
    public long context() {
        return context;
    }

    /** The Smalltalk exception (already released), or nil. */
    public long exceptionObj() {
        return exceptionObj;
    }

    /** Arguments of the error text (already released). */
    public List<Long> args() {
        return java.util.Arrays.stream(args).boxed().toList();
    }
}
