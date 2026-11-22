package net.gs64.gci;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.gs64.gci.internal.GciNative;

/**
 * A GemStone user session via the classic GCI. Close to log out.
 *
 * <p>Several sessions may be logged in at once (each {@link #login} creates a new, independent
 * gem session). The classic GCI is not thread-safe and talks to one "current session" at a time;
 * every call therefore runs under the process-wide {@link #SYNC} lock and first makes its own
 * session current ({@code GciSetSessionId}). Sessions can be used from different threads, but
 * their calls are serialized. The asynchronous variants ({@code …Async}) release the lock while
 * the gem works, so N sessions can execute on their gems in parallel.
 *
 * <p>OOPs of temporary objects belong to the session that created them; only persistent objects
 * (committed, or kernel classes) have the same OOP in every session.
 *
 * <p>Ownership: every result of {@code new…}, {@link #fromValue}, {@link #execute},
 * {@link #perform} and {@link #resolveSymbol} that is not a special object sits in the
 * PureExportSet until passed to {@link #release}. {@link #evaluate} and {@link #send} release
 * everything they create themselves, except a result they return unconverted as {@link Oop}.
 */
public final class GciSession implements AutoCloseable {
    private static final int INVALID_SESSION_ID = 0; // GCI_INVALID_SESSION_ID
    private static final int ERR_STRUCT_SIZE = 2168; // sizeof(GciErrSType), see GciErrReport
    private static final int FETCH_CHUNK_SIZE = 64 * 1024;
    private static final int OOP_CHUNK_SIZE = 8192;
    private static final long SMALLTALK_DATE_BASE = LocalDate.of(1901, 1, 1).toEpochDay(); // Date fromDays: 0

    /** The process-wide GCI lock. Reentrant. */
    static final ReentrantLock SYNC = new ReentrantLock();
    private static boolean initialized;
    private static int currentSessionId = INVALID_SESSION_ID; // mirrors GciGetSessionId(), guarded by SYNC

    private final int sessionId;
    private boolean loggedIn;
    boolean nbInProgress; // guarded by SYNC
    private long callCount;
    private Oop dateAndTimeClass, dateClass, timeClass;
    private final List<Consumer<Boolean>> transactionEnded = new CopyOnWriteArrayList<>();
    private final Map<String, Object> attachments = new HashMap<>();

    static {
        net.gs64.gci.internal.SessionAccess.install(new net.gs64.gci.internal.SessionAccess() {
            @Override
            public <T> T locked(Supplier<T> body) {
                return GciSession.locked(body);
            }

            @Override
            public void addTransactionListener(GciSession session, Consumer<Boolean> listener) {
                session.addTransactionListener(listener);
            }

            @Override
            public void removeTransactionListener(GciSession session, Consumer<Boolean> listener) {
                session.removeTransactionListener(listener);
            }

            @Override
            public Map<String, Object> attachments(GciSession session) {
                return session.attachments();
            }

            @Override
            public GciTraversal traverse(GciSession session, Oop[] roots, int level, GciTraversalOptions options,
                                         java.util.function.BiConsumer<List<GciObjectReport>, Integer> onReports) {
                return session.traverse(roots, level, options, onReports::accept);
            }

            @Override
            public long traversalWaitNanos(GciSession session) {
                return locked(() -> session.traversalWaitNanos);
            }

            @Override
            public boolean holdsLock() {
                return SYNC.isHeldByCurrentThread();
            }
        });
    }

    private GciSession(int sessionId) {
        this.sessionId = sessionId;
        this.loggedIn = true;
    }

    // =========================================================================================
    // Login, lock, errors
    // =========================================================================================

    /** False after {@link #close} or after a fatal error dropped the connection. */
    public boolean isLoggedIn() {
        return locked(() -> loggedIn);
    }

    /** The GCI session id ({@code GciGetSessionId}), unique within this process. */
    public int sessionId() {
        return sessionId;
    }

    private Integer gemSessionId;

    /**
     * The session number on the server ({@code System session}): the address for
     * {@link #sendSignal}. Valid while the session is logged in; the server reuses the number
     * afterwards.
     */
    public int gemSessionId() {
        return locked(() -> gemSessionId != null ? gemSessionId : (gemSessionId = evaluate("System session", Integer.class)));
    }

    /** Calls {@code GciInit} once per process. Called implicitly by {@link #login}. */
    public static void ensureInitialized() {
        SYNC.lock();
        try {
            if (initialized) return;
            var arch = System.getProperty("os.arch", "");
            if (!arch.contains("64"))
                throw new UnsupportedOperationException(
                        "GemStone/S 64 Bit client libraries require a 64-bit process (x64, or arm64 on Linux and macOS).");
            if ((arch.equals("aarch64") || arch.equals("arm64")) && System.getProperty("os.name", "").toLowerCase().contains("win"))
                throw new UnsupportedOperationException("GemStone/S 64 Bit has no arm64 client libraries for Windows; use an x64 JVM.");
            if (!GciNative.GciInit()) throw new GciException("GciInit failed");
            initialized = true;
        } finally {
            SYNC.unlock();
        }
    }

    /** GciSetNet + GciLoginEx with {@link GciLoginFlags#QUIET}. */
    public static GciSession login(GciNetParameters net, String userName, String password) {
        return login(net, userName, password, GciLoginFlags.QUIET, -1);
    }

    /**
     * GciSetNet + GciLoginEx.
     *
     * @param flags {@link GciLoginFlags}; QUIET suppresses the banner the library prints to stdout
     * @param haltOnErrNum -1 keeps the GEM_HALT_ON_ERROR config setting, 0 never halts
     * @throws GciException on failure
     */
    public static GciSession login(GciNetParameters net, String userName, String password, int flags, int haltOnErrNum) {
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(userName, "userName");
        Objects.requireNonNull(password, "password");
        SYNC.lock();
        try {
            ensureInitialized();
            GciNative.GciSetNet(net.stoneName(), net.hostUserId(), net.hostPassword(), net.gemService());
            if (!GciNative.GciLoginEx(userName, password, flags, haltOnErrNum)) {
                currentSessionId = GciNative.GciGetSessionId(); // a failed login leaves the previous session current
                var error = lastError();
                throw error != null ? error : new GciException("GciLoginEx failed without error report");
            }
            currentSessionId = GciNative.GciGetSessionId();
            return new GciSession(currentSessionId);
        } finally {
            SYNC.unlock();
        }
    }

    /** Logs out (also interrupts a pending asynchronous call). */
    @Override
    public void close() {
        SYNC.lock();
        try {
            awaitSessionFree(); // a call of this session waiting with the lock released: let it finish
            if (!loggedIn) return;
            switchTo();
            if (nbInProgress) finishPendingNonblockingCall();
            loggedIn = false;
            GciNative.GciLogout();
            currentSessionId = INVALID_SESSION_ID;
            lastError(); // nothing left to report to; don't leak it into the next call
        } finally {
            SYNC.unlock();
        }
    }

    /**
     * Interrupts Smalltalk code this session is currently running; the running call then fails
     * with a {@link GciException} (RT_ERR_SOFT_BREAK). Meant to be called from another thread, so
     * it takes no lock. Does nothing if another session is current.
     */
    public void softBreak() {
        if (GciNative.GciGetSessionId() == sessionId) GciNative.GciSoftBreak();
    }

    /** Number of GCI calls made through this session (roughly: round trips), for tests and benchmarks. */
    public long callCount() {
        return locked(() -> callCount);
    }

    static <T> T locked(Supplier<T> body) {
        SYNC.lock();
        try {
            return body.get();
        } finally {
            SYNC.unlock();
        }
    }

    static void locked(Runnable body) {
        SYNC.lock();
        try {
            body.run();
        } finally {
            SYNC.unlock();
        }
    }

    /**
     * Guard for every native call (caller holds {@link #SYNC}): throws if logged out or an
     * asynchronous call is pending, then makes this session the GCI's current session.
     */
    void activate() {
        callCount++;
        awaitSessionFree(); // another thread waits for a call of this session with the lock released
        switchTo();
        if (nbInProgress)
            throw new IllegalStateException(
                    "An asynchronous operation is in progress on this session; wait for it before making further calls.");
    }

    /** Makes this session current without the in-progress check (polling, close). */
    void switchTo() {
        if (!loggedIn) throw new IllegalStateException("Session is logged out or was lost after a fatal error.");
        if (currentSessionId == sessionId) return;
        GciNative.GciSetSessionId(sessionId);
        currentSessionId = sessionId;
    }

    /** Fetches and clears the pending GCI error, or null if none. */
    static GciException lastError() {
        try (var arena = Arena.ofConfined()) {
            var err = arena.allocate(ERR_STRUCT_SIZE, 8);
            if (!GciNative.GciErr(err)) return null;
            // GciErrSType (gci.ht): category 0, context 8, exceptionObj 16, args[10] 24, number 104,
            // argCount 108, fatal 112, message[1025] 113, reason[1025] 1138; 2168 bytes.
            var argCount = Math.clamp(err.get(JAVA_INT, 108), 0, 10);
            var args = new ArrayList<Long>(argCount);
            for (var i = 0; i < argCount; i++) args.add(err.get(JAVA_LONG, 24 + 8L * i));
            return new GciException(err.get(JAVA_INT, 104), err.get(JAVA_BYTE, 112) != 0,
                    cString(err, 113, 1025), cString(err, 1138, 1025), err.get(JAVA_LONG, 8), err.get(JAVA_LONG, 16), args);
        }
    }

    private static String cString(MemorySegment s, long offset, int capacity) {
        var len = 0;
        while (len < capacity && s.get(JAVA_BYTE, offset + len) != 0) len++;
        return new String(s.asSlice(offset, len).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /** OOP_NIL is both a valid result and the error marker; disambiguate via GciErr. */
    Oop checkResult(long result) {
        if (result == Oop.NIL.value()) throwIfError();
        return new Oop(result);
    }

    void throwIfError() {
        var error = lastError();
        if (error != null) {
            discardError(error);
            throw error;
        }
    }

    /**
     * After an error: marks the session as lost if fatal; otherwise clears the suspended stack and
     * releases the error's OOPs (GciErr adds them to the PureExportSet, manual 1.4).
     */
    void discardError(GciException error) {
        if (error.isFatal()) {
            loggedIn = false; // connection is gone; GciLogout would be invalid
            currentSessionId = GciNative.GciGetSessionId();
            return;
        }
        if (error.context() != Oop.NIL.value() && error.context() != 0) GciNative.GciClearStack(error.context());
        var oops = new ArrayList<Long>();
        oops.add(error.context());
        oops.add(error.exceptionObj());
        oops.addAll(error.args());
        var heap = oops.stream().mapToLong(Long::longValue).filter(o -> o != 0 && !new Oop(o).isSpecial()).toArray();
        if (heap.length == 0) return;
        try (var arena = Arena.ofConfined()) {
            GciNative.GciReleaseOops(arena.allocateFrom(JAVA_LONG, heap), heap.length);
        }
        lastError(); // a failure here must not mask the original error
    }

    /** Per-session state of other parts of the library (mapping, clamp specifications). Guarded by SYNC. */
    Map<String, Object> attachments() {
        return attachments;
    }

    // =========================================================================================
    // Execute, perform, export set
    // =========================================================================================

    /** {@link #execute(String, Oop)} with the user's symbol list. */
    public Oop execute(String source) {
        return execute(source, null);
    }

    /**
     * Executes Smalltalk source and returns the result OOP (in the export set).
     *
     * <p>{@code GciExecuteStr} compiles its C string as an 8-bit String, which would turn UTF-8
     * multi-byte sequences into garbage. Non-ASCII source is therefore sent as a Unicode string
     * ({@code GciNewUtf8String_} + {@code GciExecute}); not as Utf8, which the compilers of 3.6.x
     * and 3.7.2 cannot handle once the source contains a block.
     *
     * @param symbolList symbol list to resolve names in; null uses the user's
     * @throws GciException the code raised an error
     */
    public Oop execute(String source, Oop symbolList) {
        Objects.requireNonNull(source, "source");
        var symList = symbolList == null ? Oop.NIL.value() : symbolList.value();
        return locked(() -> {
            activate();
            if (isAscii(source)) return checkResult(GciNative.GciExecuteStr(source, symList));
            var sourceObj = newUnicode(source);
            try {
                activate();
                return checkResult(GciNative.GciExecute(sourceObj.value(), symList));
            } finally {
                release(sourceObj);
            }
        });
    }

    /**
     * Sends {@code selector} to {@code receiver} ({@code GciPerform}).
     *
     * @param selector keywords concatenated, e.g. {@code "at:put:"}
     * @throws GciException the message send raised an error
     */
    public Oop perform(Oop receiver, String selector, Oop... args) {
        Objects.requireNonNull(selector, "selector");
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                // The C API wants a valid pointer even for zero arguments ("use a dummy OOP").
                var argv = args.length == 0 ? arena.allocateFrom(JAVA_LONG, Oop.NIL.value()) : oops(arena, args);
                return checkResult(GciNative.GciPerform(receiver.value(), selector, argv, args.length));
            }
        });
    }

    /**
     * Removes OOPs from the PureExportSet. Special objects are never in an export set and are
     * skipped; null entries are ignored.
     */
    public void release(Oop... oops) {
        var heap = Arrays.stream(oops).filter(o -> o != null && !o.isSpecial() && !o.equals(Oop.ILLEGAL))
                .mapToLong(Oop::value).toArray();
        if (heap.length == 0) return;
        locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                GciNative.GciReleaseOops(arena.allocateFrom(JAVA_LONG, heap), heap.length);
            }
            throwIfError();
        });
    }

    /**
     * Adds OOPs to the PureExportSet (GciSaveObjs), so that the gem does not garbage-collect them
     * while the client holds them. Undo with {@link #release}; one release cancels any number of
     * retains.
     */
    public void retain(Oop... oops) {
        var heap = Arrays.stream(oops).filter(o -> o != null && !o.isSpecial() && !o.equals(Oop.ILLEGAL))
                .mapToLong(Oop::value).toArray();
        if (heap.length == 0) return;
        locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                GciNative.GciSaveObjs(arena.allocateFrom(JAVA_LONG, heap), heap.length);
            }
            throwIfError();
        });
    }

    /** Size of an object (bytes for byte objects, elements for collections). */
    public long fetchSize(Oop obj) {
        return locked(() -> {
            activate();
            var size = GciNative.GciFetchSize_(obj.value());
            throwIfError();
            return size;
        });
    }

    /** All bytes of a byte-format object (String, Utf8, ByteArray …). */
    public byte[] fetchBytes(Oop obj) {
        return locked(() -> {
            var size = fetchSize(obj);
            if (size == 0) return new byte[0];
            try (var arena = Arena.ofConfined()) {
                var buf = arena.allocate(size);
                activate();
                var n = GciNative.GciFetchBytes_(obj.value(), 1, buf, size);
                throwIfError();
                return buf.asSlice(0, n).toArray(JAVA_BYTE);
            }
        });
    }

    /** The object's {@code printString}, transferred as UTF-8. Intermediate objects are released. */
    public String printString(Oop obj) {
        return locked(() -> {
            var str = perform(obj, "printString");
            try {
                return toStringValue(str);
            } finally {
                release(str);
            }
        });
    }

    // =========================================================================================
    // Transactions
    // =========================================================================================

    /**
     * Commits the current transaction (GciCommit).
     *
     * @throws GciTransactionConflictException concurrency conflict; call {@link #abort} before retrying
     */
    public void commit() {
        locked(() -> {
            if (!tryCommit()) throw new GciTransactionConflictException(tryGetConflictReport());
        });
    }

    /** Commits the current transaction; false on a concurrency conflict. */
    public boolean tryCommit() {
        return locked(() -> {
            activate();
            if (GciNative.GciCommit()) {
                onTransactionEnded(true);
                return true;
            }
            throwIfError(); // FALSE with an error report = real error
            return false;   // FALSE without = transaction conflict
        });
    }

    /** Discards all uncommitted changes and moves to the latest committed state (GciAbort). */
    public void abort() {
        locked(() -> {
            activate();
            try {
                GciNative.GciAbort();
                throwIfError();
            } finally {
                onTransactionEnded(false);
            }
        });
    }

    /** Begins a transaction (GciBegin); a transaction in progress is aborted first. */
    public void begin() {
        locked(() -> {
            activate();
            try {
                GciNative.GciBegin();
                throwIfError();
            } finally {
                onTransactionEnded(false);
            }
        });
    }

    /** Whether the session is currently in a transaction ({@code System inTransaction}). */
    public boolean inTransaction() {
        return evaluate("System inTransaction", Boolean.class);
    }

    /** Whether there are uncommitted changes ({@code System needsCommit}). */
    public boolean needsCommit() {
        return evaluate("System needsCommit", Boolean.class);
    }

    public GciTransactionMode transactionMode() {
        return GciTransactionMode.of(evaluate("System transactionMode", String.class));
    }

    public void setTransactionMode(GciTransactionMode mode) {
        release(execute("System transactionMode: #" + mode.symbol));
    }

    /**
     * Runs {@code work} and commits; on a conflict aborts and retries, up to {@code maxAttempts}
     * times. Any other exception aborts and is rethrown. The work must be repeatable: after an
     * abort it sees the newly committed state of other sessions. In manual-begin mode a
     * transaction is begun before every attempt.
     */
    public <T> T runInTransaction(Function<GciSession, T> work, int maxAttempts) {
        Objects.requireNonNull(work, "work");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1.");
        return locked(() -> {
            var manual = transactionMode() == GciTransactionMode.MANUAL_BEGIN;
            for (var attempt = 1; ; attempt++) {
                if (manual) begin();
                T result;
                try {
                    result = work.apply(this);
                } catch (RuntimeException | Error e) {
                    if (loggedIn) abort();
                    throw e;
                }
                if (tryCommit()) return result;
                var report = tryGetConflictReport();
                abort();
                if (attempt >= maxAttempts) throw new GciTransactionConflictException(report);
            }
        });
    }

    /** {@link #runInTransaction(Function, int)} with 3 attempts. */
    public <T> T runInTransaction(Function<GciSession, T> work) {
        return runInTransaction(work, 3);
    }

    /**
     * {@link #runInTransaction(Function, int)} for work without a result (own name: with an
     * implicitly typed lambda, overloads on Function and Consumer would be ambiguous).
     */
    public void doInTransaction(Consumer<GciSession> work, int maxAttempts) {
        runInTransaction(s -> {
            work.accept(s);
            return null;
        }, maxAttempts);
    }

    /**
     * Listeners called after a commit (true) or an abort (false, also {@link #begin}) through this
     * API, under the GCI lock. The object mapper uses it to take back what it saved but was not
     * committed. A commit or abort in Smalltalk code is not seen.
     */
    void addTransactionListener(Consumer<Boolean> listener) {
        transactionEnded.add(listener);
    }

    void removeTransactionListener(Consumer<Boolean> listener) {
        transactionEnded.remove(listener);
    }

    void onTransactionEnded(boolean committed) {
        for (var l : transactionEnded) l.accept(committed);
    }

    String tryGetConflictReport() {
        try {
            return evaluate("System transactionConflicts printString", String.class);
        } catch (GciException e) {
            return null;
        }
    }

    // =========================================================================================
    // Values
    // =========================================================================================

    /** SmallInteger, or a new LargeInteger outside the SmallInteger range (GciI64ToOop). */
    public Oop newInteger(long value) {
        if (Oop.fitsSmallInteger(value)) return Oop.fromSmallInteger(value); // no round trip
        return locked(() -> {
            activate();
            var oop = GciNative.GciI64ToOop(value);
            throwIfError();
            return new Oop(oop);
        });
    }

    /** New Integer of any size (an integer literal beyond int64). */
    public Oop newInteger(BigInteger value) {
        if (value.bitLength() < 64) return newInteger(value.longValue());
        return execute(value.toString());
    }

    /**
     * Integer value (SmallInteger or LargeInteger within int64) via GciOopToI64_.
     *
     * @throws ClassCastException not an Integer, or outside the int64 range
     */
    public long toLong(Oop obj) {
        if (obj.isSmallInteger()) return obj.toSmallInteger();
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var error = arena.allocate(JAVA_INT);
                var value = GciNative.GciOopToI64_(obj.value(), error);
                throwIfError();
                if (error.get(JAVA_INT, 0) != 0)
                    throw new ClassCastException(obj + " is not an Integer within the long range.");
                return value;
            }
        });
    }

    /** Any GemStone Integer, including LargeIntegers beyond int64. */
    public BigInteger toBigInteger(Oop obj) {
        if (obj.isSmallInteger()) return BigInteger.valueOf(obj.toSmallInteger());
        return locked(() -> {
            if (!isKindOf(obj, KnownClasses.INTEGER)) throw new ClassCastException(obj + " is not an Integer.");
            try {
                return BigInteger.valueOf(toLong(obj));
            } catch (ClassCastException e) {
                return new BigInteger(printString(obj)); // Integer printString is plain decimal digits
            }
        });
    }

    /** SmallDouble, or a new Float (GciFltToOop). */
    public Oop newFloat(double value) {
        var small = Oop.tryFromSmallDouble(value);
        if (small != null) return small; // no round trip
        return locked(() -> {
            activate();
            return checkResult(GciNative.GciFltToOop(value));
        });
    }

    /**
     * Value of a SmallDouble, SmallFloat or Float via GciOopToFlt_ (GciOopToFlt on 3.6).
     *
     * @throws ClassCastException not a binary float
     */
    public double toDouble(Oop obj) {
        if (obj.isSmallDouble()) return obj.toSmallDouble();
        return locked(() -> {
            activate();
            if (!GciNative.hasOopToFltWithStatus()) {
                var value = GciNative.GciOopToFlt(obj.value());
                try {
                    throwIfError();
                } catch (GciException e) {
                    if (e.isFatal()) throw e;
                    throw new ClassCastException(obj + " is not a Float: " + e.getMessage());
                }
                return value;
            }
            try (var arena = Arena.ofConfined()) {
                var result = arena.allocate(JAVA_DOUBLE);
                var ok = GciNative.GciOopToFlt_(obj.value(), result);
                throwIfError();
                if (!ok) throw new ClassCastException(obj + " is not a Float.");
                return result.get(JAVA_DOUBLE, 0);
            }
        });
    }

    /**
     * New ScaledDecimal, e.g. 12.34 → {@code 12.34s2}. Compiled from a literal, so independent of
     * the gem's locale.
     */
    public Oop newScaledDecimal(BigDecimal value) {
        var v = value.scale() < 0 ? value.setScale(0) : value;
        return execute(v.toPlainString() + "s" + v.scale());
    }

    /**
     * Value of a ScaledDecimal. Computed as {@code (self * 10^scale) rounded} and {@code scale} on
     * the server, because its printString depends on the gem's locale (e.g. {@code 0,333}).
     */
    public BigDecimal toBigDecimal(Oop obj) {
        return locked(() -> {
            var scale = toLong(perform(obj, "scale"));
            var factor = newInteger(BigInteger.TEN.pow((int) scale));
            Oop scaled = Oop.NIL, rounded = Oop.NIL;
            try {
                scaled = perform(obj, "*", factor);
                rounded = perform(scaled, "rounded");
                return new BigDecimal(toBigInteger(rounded), (int) scale);
            } finally {
                release(factor, scaled, rounded);
            }
        });
    }

    /**
     * New string: an 8-bit String for pure ASCII (GciNewString), otherwise the smallest of
     * Unicode7/16/32 that holds all characters (GciNewUtf8String_).
     */
    public Oop newString(String value) {
        Objects.requireNonNull(value, "value");
        return locked(() -> {
            if (!isAscii(value)) return newUnicode(value);
            activate();
            var oop = GciNative.GciNewString(value);
            throwIfError();
            return new Oop(oop);
        });
    }

    /** Unicode7/16/32 for any text (GciNewUtf8String_ with utf8OrUnicode = TRUE). */
    Oop newUnicode(String value) {
        return locked(() -> {
            activate();
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            try (var arena = Arena.ofConfined()) {
                var oop = GciNative.GciNewUtf8String_(arena.allocateFrom(JAVA_BYTE, bytes), bytes.length, true);
                throwIfError();
                return new Oop(oop);
            }
        });
    }

    /** Symbol (GciNewSymbol for ASCII, otherwise {@code asSymbol} on a Unicode string). */
    public Oop newSymbol(String value) {
        Objects.requireNonNull(value, "value");
        return locked(() -> {
            if (isAscii(value)) {
                activate();
                var oop = GciNative.GciNewSymbol(value);
                throwIfError();
                return new Oop(oop);
            }
            var str = newString(value);
            try {
                return perform(str, "asSymbol");
            } finally {
                release(str);
            }
        });
    }

    /**
     * Contents of any CharacterCollection (String, Symbol, Unicode*, DoubleByteString, Utf8 …) —
     * not its printString. Uses GciFetchUtf8Bytes_, fetching large strings in chunks.
     *
     * @throws GciException not a CharacterCollection
     */
    public String toStringValue(Oop obj) {
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var buf = arena.allocate(FETCH_CHUNK_SIZE);
                var utf8Out = arena.allocate(JAVA_LONG);
                var n = GciNative.GciFetchUtf8Bytes_(obj.value(), 1, buf, FETCH_CHUNK_SIZE, utf8Out, 0);
                throwIfError();
                var utf8 = utf8Out.get(JAVA_LONG, 0);
                try {
                    if (n < FETCH_CHUNK_SIZE) return new String(buf.asSlice(0, n).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                    // More data: the rest comes from the UTF-8 encoded object, whose bytes continue at n.
                    var source = utf8 != 0 ? new Oop(utf8) : obj;
                    var total = fetchSize(source);
                    var all = arena.allocate(total);
                    all.copyFrom(buf.asSlice(0, n));
                    activate();
                    var rest = GciNative.GciFetchBytes_(source.value(), n + 1, all.asSlice(n), total - n);
                    throwIfError();
                    return new String(all.asSlice(0, n + rest).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
                } finally {
                    if (utf8 != 0 && utf8 != obj.value()) release(new Oop(utf8));
                }
            }
        });
    }

    /** New DateAndTime with the same instant and UTC offset (GciNewDateAndTimePosix), microsecond precision. */
    public Oop newDateAndTime(OffsetDateTime value) {
        var instant = value.toInstant();
        var micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
        return locked(() -> {
            activate();
            var oop = GciNative.GciNewDateAndTimePosix(micros, value.getOffset().getTotalSeconds());
            throwIfError();
            if (oop == Oop.ILLEGAL.value()) throw new GciException("GciNewDateAndTimePosix failed");
            return new Oop(oop);
        });
    }

    /** Value of a DateAndTime (GciFetchDateAndTimePosix); the offset keeps its seconds. */
    public OffsetDateTime toDateTime(Oop obj) {
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var result = arena.allocate(16, 8); // GciPosixDateAndTime { int64 microseconds; int offset; }
                var ok = GciNative.GciFetchDateAndTimePosix(obj.value(), result);
                throwIfError();
                if (!ok) throw new ClassCastException(obj + " is not a DateAndTime.");
                return fromPosix(result.get(JAVA_LONG, 0), result.get(JAVA_INT, 8));
            }
        });
    }

    static OffsetDateTime fromPosix(long micros, int offsetSeconds) {
        var instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
        return OffsetDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(offsetSeconds));
    }

    /** New Date ({@code Date fromDays:}, days since 1901-01-01; proleptic Gregorian like java.time). */
    public Oop newDate(LocalDate value) {
        return perform(dateClass(), "fromDays:", Oop.fromSmallInteger(value.toEpochDay() - SMALLTALK_DATE_BASE));
    }

    /** Value of a Date ({@code asDays}). */
    public LocalDate toLocalDate(Oop obj) {
        return LocalDate.ofEpochDay(toLong(perform(obj, "asDays")) + SMALLTALK_DATE_BASE); // SmallInteger: nothing to release
    }

    /** New Time ({@code Time fromMicroseconds:}); sub-microsecond nanos are truncated. */
    public Oop newTime(LocalTime value) {
        return perform(timeClass(), "fromMicroseconds:", Oop.fromSmallInteger(value.toNanoOfDay() / 1000));
    }

    /** Value of a Time ({@code asMicroseconds}). */
    public LocalTime toLocalTime(Oop obj) {
        return LocalTime.ofNanoOfDay(toLong(perform(obj, "asMicroseconds")) * 1000);
    }

    /** Class of an object (GciFetchClass). */
    public Oop fetchClass(Oop obj) {
        return locked(() -> {
            activate();
            return checkResult(GciNative.GciFetchClass(obj.value()));
        });
    }

    /** True if the object's class is {@code cls} or a subclass (GciIsKindOfClass). */
    public boolean isKindOf(Oop obj, Oop cls) {
        return locked(() -> {
            activate();
            var result = GciNative.GciIsKindOfClass(obj.value(), cls.value());
            throwIfError();
            return result;
        });
    }

    /**
     * Maps a GemStone object to the matching Java value: nil → {@code null}, Boolean →
     * {@link Boolean}, Integer → {@link Long} (or {@link BigInteger} beyond long), Float/SmallDouble
     * → {@link Double}, ScaledDecimal → {@link BigDecimal}, Character → {@link CodePoint},
     * CharacterCollection → {@link String}, ByteArray → {@code byte[]}, DateAndTime →
     * {@link OffsetDateTime}, Date → {@link LocalDate}, Time → {@link LocalTime}. Anything else
     * (Array, Fraction, own classes …) is returned as {@link Oop}.
     */
    public Object toValue(Oop obj) {
        if (obj.isNil()) return null;
        if (obj.isBoolean()) return obj.toBoolean();
        if (obj.isSmallInteger()) return obj.toSmallInteger();
        if (obj.isCharacter()) return new CodePoint(obj.toCharacter());
        if (obj.isSmallDouble()) return obj.toSmallDouble();
        if (obj.isSmallDateAndTime()) return toDateTime(obj);
        if (obj.isSmallDate()) return toLocalDate(obj);
        if (obj.isSmallTime()) return toLocalTime(obj);
        return locked(() -> {
            var cls = fetchClass(obj);
            if (cls.equals(KnownClasses.SMALL_SCALED_DECIMAL) || cls.equals(KnownClasses.SCALED_DECIMAL)) return toBigDecimal(obj);
            if (obj.isSpecial()) return obj; // SmallFraction …
            if (cls.equals(KnownClasses.STRING) || cls.equals(KnownClasses.SYMBOL) || cls.equals(KnownClasses.UNICODE7)
                    || cls.equals(KnownClasses.UNICODE16) || cls.equals(KnownClasses.UNICODE32) || cls.equals(KnownClasses.UTF8)
                    || cls.equals(KnownClasses.DOUBLE_BYTE_STRING) || cls.equals(KnownClasses.DOUBLE_BYTE_SYMBOL)
                    || cls.equals(KnownClasses.QUAD_BYTE_SYMBOL))
                return toStringValue(obj);
            if (cls.equals(KnownClasses.LARGE_INTEGER)) return narrow(toBigInteger(obj));
            if (cls.equals(KnownClasses.FLOAT) || cls.equals(KnownClasses.SMALL_FLOAT)) return toDouble(obj);
            if (cls.equals(KnownClasses.BYTE_ARRAY)) return fetchBytes(obj);
            // Subclasses of the kernel classes.
            if (isKindOf(obj, KnownClasses.CHARACTER_COLLECTION)) return toStringValue(obj);
            if (isKindOf(obj, KnownClasses.INTEGER)) return narrow(toBigInteger(obj));
            if (isKindOf(obj, dateAndTimeClass())) return toDateTime(obj);
            if (isKindOf(obj, dateClass())) return toLocalDate(obj);
            if (isKindOf(obj, timeClass())) return toLocalTime(obj);
            return obj;
        });
    }

    private static Object narrow(BigInteger value) {
        return value.bitLength() < 64 ? (Object) value.longValue() : value;
    }

    // Server side of toValues: one tagged Array per object, readable offline from a traversal.
    // Same arithmetic as the single-value conversions; nothing is parsed from locale-dependent
    // printString output (Integer printString is plain digits).
    private static final String TO_VALUES_BLOCK =
            "[:objs | objs collect: [:x |"
                    + " (x isKindOf: Integer) ifTrue: [{1. x printString}] ifFalse: ["
                    + " (x isKindOf: ScaledDecimal) ifTrue: [{2. (x * (10 raisedTo: x scale)) rounded printString. x scale}] ifFalse: ["
                    + " (x isKindOf: DateAndTime) ifTrue: [{3. (x asPosixSeconds * 1000000) rounded printString. x offset asSeconds}] ifFalse: ["
                    + " (x isKindOf: Date) ifTrue: [{4. x asDays}] ifFalse: ["
                    + " (x isKindOf: Time) ifTrue: [{5. x asMicroseconds}] ifFalse: ["
                    + " (x isKindOf: CharacterCollection) ifTrue: [{6. x asUnicodeString}] ifFalse: ["
                    + " (x isKindOf: BinaryFloat) ifTrue: [{7. x asFloat}] ifFalse: ["
                    + " (x isKindOf: Utf8) ifTrue: [{6. x decodeToUnicode}] ifFalse: [" // Utf8 is a ByteArray, not a CharacterCollection
                    + " (x isKindOf: ByteArray) ifTrue: [{8. ByteArray withAll: x}] ifFalse: [0]]]]]]]]]]]";

    /**
     * {@link #toValue} for many objects in one round trip (plus a few fixed ones), instead of one
     * or more per object. Immediate values are decoded locally; objects without a Java
     * counterpart are returned as their {@link Oop}.
     */
    public Object[] toValues(List<Oop> objects) {
        var result = new Object[objects.size()];
        var pending = new ArrayList<Integer>();
        for (var i = 0; i < objects.size(); i++) {
            var o = objects.get(i);
            if (o.isNil()) result[i] = null;
            else if (o.isBoolean()) result[i] = o.toBoolean();
            else if (o.isSmallInteger()) result[i] = o.toSmallInteger();
            else if (o.isSmallDouble()) result[i] = o.toSmallDouble();
            else if (o.isCharacter()) result[i] = new CodePoint(o.toCharacter());
            else pending.add(i);
        }
        if (pending.isEmpty()) return result;
        return locked(() -> {
            var array = newObject(KnownClasses.ARRAY, pending.size());
            var block = Oop.NIL;
            try {
                storeVaryingOops(array, 1, pending.stream().map(objects::get).toArray(Oop[]::new));
                block = execute(TO_VALUES_BLOCK);
                var t = performTraverse(block, "value:", 3, new Oop[] {array}, null);
                var tagged = t.get(t.result()).indexedValues();
                for (var k = 0; k < pending.size(); k++) {
                    int i = pending.get(k);
                    var tag = new Oop(tagged[k]);
                    result[i] = tag.isSpecial() ? objects.get(i) : untag(t, t.get(tag).indexedValues(), objects.get(i));
                }
                return result;
            } finally {
                release(array, block);
            }
        });
    }

    private static Object untag(GciTraversal t, long[] items, Oop original) {
        Function<Integer, String> text = i -> {
            if (t.toValue(new Oop(items[i])) instanceof String s) return s;
            throw new GciException("Unexpected conversion result for " + original + ".");
        };
        Function<Integer, Long> small = i -> new Oop(items[i]).toSmallInteger();
        return switch ((int) (long) small.apply(0)) {
            case 1 -> narrow(new BigInteger(text.apply(1)));
            case 2 -> new BigDecimal(new BigInteger(text.apply(1)), (int) (long) small.apply(2));
            case 3 -> fromPosix(Long.parseLong(text.apply(1)), (int) (long) small.apply(2));
            case 4 -> LocalDate.ofEpochDay(small.apply(1) + SMALLTALK_DATE_BASE);
            case 5 -> LocalTime.ofNanoOfDay(small.apply(1) * 1000);
            case 6 -> text.apply(1);
            case 7 -> {
                if (t.toValue(new Oop(items[1])) instanceof Double d) yield d;
                throw new GciException("Unexpected conversion result for " + original + ".");
            }
            case 8 -> {
                if (t.toValue(new Oop(items[1])) instanceof byte[] b) yield b;
                throw new GciException("Unexpected conversion result for " + original + ".");
            }
            default -> original;
        };
    }

    /**
     * Creates the GemStone counterpart of a Java value: {@code null}, {@link Boolean}, integer
     * types, {@link BigInteger}, {@link Float}/{@link Double}, {@link BigDecimal} (ScaledDecimal),
     * {@link Character}/{@link CodePoint} (Character), {@link String}, {@link OffsetDateTime},
     * {@link ZonedDateTime}, {@link Instant} (UTC) and {@link LocalDateTime} (system time zone)
     * (DateAndTime), {@link LocalDate} (Date), {@link LocalTime} (Time), {@code byte[]}
     * (ByteArray) and {@link Oop} (as is). Release non-special results with {@link #release}.
     *
     * @throws IllegalArgumentException unsupported type
     */
    public Oop fromValue(Object value) {
        return switch (value) {
            case null -> Oop.NIL;
            case Oop oop -> oop;
            case Boolean b -> Oop.fromBoolean(b);
            case CodePoint c -> Oop.fromCharacter(c.value());
            case Character c -> {
                if (Character.isSurrogate(c))
                    throw new IllegalArgumentException("A lone surrogate char is not a Unicode code point; pass a CodePoint.");
                yield Oop.fromCharacter(c);
            }
            case Byte b -> Oop.fromSmallInteger(b);
            case Short s -> Oop.fromSmallInteger(s);
            case Integer i -> Oop.fromSmallInteger(i);
            case Long l -> newInteger(l);
            case BigInteger big -> newInteger(big);
            case Float f -> newFloat(f);
            case Double d -> newFloat(d);
            case BigDecimal m -> newScaledDecimal(m);
            case String s -> newString(s);
            case OffsetDateTime dt -> newDateAndTime(dt);
            case ZonedDateTime dt -> newDateAndTime(dt.toOffsetDateTime());
            case Instant i -> newDateAndTime(i.atOffset(ZoneOffset.UTC));
            case LocalDateTime dt -> newDateAndTime(dt.atZone(ZoneId.systemDefault()).toOffsetDateTime());
            case LocalDate d -> newDate(d);
            case LocalTime t -> newTime(t);
            case byte[] bytes -> newByteArray(bytes);
            default -> throw new IllegalArgumentException("No GemStone mapping for " + value.getClass().getName() + ".");
        };
    }

    /** Executes Smalltalk and converts the result with {@link #toValue}; converted results are released. */
    public Object evaluate(String source) {
        return locked(() -> convertAndRelease(execute(source)));
    }

    /** {@link #evaluate(String)} with a typed result; numbers are converted (e.g. Long → Integer, range-checked). */
    public <T> T evaluate(String source, Class<T> type) {
        return castTo(evaluate(source), type);
    }

    /**
     * Sends a message with Java arguments (converted with {@link #fromValue}) and converts the
     * result with {@link #toValue}. Everything created here is released, unless the result is
     * returned as {@link Oop}.
     */
    public Object send(Oop receiver, String selector, Object... args) {
        return locked(() -> {
            var argOops = new Oop[args.length];
            try {
                for (var i = 0; i < args.length; i++) argOops[i] = fromValue(args[i]);
                return convertAndRelease(perform(receiver, selector, argOops));
            } finally {
                releaseCreated(args, argOops);
            }
        });
    }

    /** {@link #send} with a typed result. */
    public <T> T send(Class<T> type, Oop receiver, String selector, Object... args) {
        return castTo(send(receiver, selector, args), type);
    }

    /** Releases what {@link #fromValue} created for {@code args}; caller-supplied OOPs stay. */
    void releaseCreated(Object[] args, Oop[] argOops) {
        var created = new ArrayList<Oop>();
        for (var i = 0; i < args.length; i++)
            if (argOops[i] != null && !(args[i] instanceof Oop)) created.add(argOops[i]);
        release(created.toArray(Oop[]::new));
    }

    Object convertAndRelease(Oop result) {
        Object value;
        try {
            value = toValue(result);
        } catch (RuntimeException e) {
            release(result);
            throw e;
        }
        if (!(value instanceof Oop)) release(result);
        return value;
    }

    /** Only numeric widening/narrowing (range-checked); strings are never parsed. */
    static <T> T castTo(Object value, Class<T> type) {
        if (value == null) {
            if (type.isPrimitive()) throw new ClassCastException("nil cannot be converted to " + type.getName() + ".");
            return null;
        }
        var target = boxed(type);
        if (target.isInstance(value)) {
            @SuppressWarnings("unchecked") var t = (T) value;
            return t;
        }
        if (value instanceof Number n) {
            Object converted = convertNumber(n, target);
            if (converted != null) {
                @SuppressWarnings("unchecked") var t = (T) converted;
                return t;
            }
        }
        throw new ClassCastException(value.getClass().getName() + " cannot be converted to " + type.getName() + ".");
    }

    private static Object convertNumber(Number n, Class<?> target) {
        if (target == Double.class) return n.doubleValue();
        if (target == Float.class) return n.floatValue();
        if (target == BigDecimal.class)
            return n instanceof BigInteger b ? new BigDecimal(b) : n instanceof Long || n instanceof Integer ? BigDecimal.valueOf(n.longValue()) : BigDecimal.valueOf(n.doubleValue());
        if (target == BigInteger.class && (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte))
            return BigInteger.valueOf(n.longValue());
        if (n instanceof Double || n instanceof Float || n instanceof BigDecimal) return null;
        var big = n instanceof BigInteger b ? b : BigInteger.valueOf(n.longValue());
        try {
            if (target == Long.class) return big.longValueExact();
            if (target == Integer.class) return big.intValueExact();
            if (target == Short.class) return big.shortValueExact();
            if (target == Byte.class) return big.byteValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException(n + " does not fit " + target.getSimpleName() + ".");
        }
        return null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    // Classes without a fixed OOP in gcioop.ht, resolved once per session (kept in the export set).
    Oop dateAndTimeClass() {
        return locked(() -> dateAndTimeClass != null ? dateAndTimeClass : (dateAndTimeClass = resolveSymbol("DateAndTime")));
    }

    Oop dateClass() {
        return locked(() -> dateClass != null ? dateClass : (dateClass = resolveSymbol("Date")));
    }

    Oop timeClass() {
        return locked(() -> timeClass != null ? timeClass : (timeClass = resolveSymbol("Time")));
    }

    // =========================================================================================
    // Structural object access (1-based indices, as in Smalltalk)
    // =========================================================================================

    /** New instance of {@code cls} with {@code indexedSize} indexed slots (GciNewOops). */
    public Oop newObject(Oop cls, long indexedSize) {
        if (indexedSize < 0) throw new IllegalArgumentException("indexedSize must not be negative.");
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var result = arena.allocate(JAVA_LONG);
                GciNative.GciNewOops(1, arena.allocateFrom(JAVA_LONG, cls.value()), arena.allocateFrom(JAVA_LONG, indexedSize), result);
                throwIfError();
                var oop = new Oop(result.get(JAVA_LONG, 0));
                if (oop.isNil()) throw new IllegalArgumentException(cls + " has a special implementation and cannot be instantiated.");
                return oop;
            }
        });
    }

    /** New instance without indexed slots. */
    public Oop newObject(Oop cls) {
        return newObject(cls, 0);
    }

    /** New ByteArray with the given contents (GciNewByteObj). */
    public Oop newByteArray(byte[] bytes) {
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var oop = GciNative.GciNewByteObj(KnownClasses.BYTE_ARRAY.value(),
                        bytes.length == 0 ? arena.allocate(1) : arena.allocateFrom(JAVA_BYTE, bytes), bytes.length);
                throwIfError();
                return new Oop(oop);
            }
        });
    }

    /**
     * Looks up a global (class, symbol-dictionary entry …) by name in the user's symbol list.
     *
     * @throws java.util.NoSuchElementException no such name
     */
    public Oop resolveSymbol(String name) {
        var oop = tryResolveSymbol(name, null);
        if (oop == null) throw new java.util.NoSuchElementException("'" + name + "' is not defined in the symbol list.");
        return oop;
    }

    /**
     * Like {@link #resolveSymbol}; null if the name is not defined (GciResolveSymbolObj answers
     * OOP_ILLEGAL without an error report). The result is in the export set.
     *
     * @param symbolList null uses the user's symbol list
     */
    public Oop tryResolveSymbol(String name, Oop symbolList) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name must not be empty.");
        return locked(() -> {
            // Not GciResolveSymbol: it leaves a String with the name in the PureExportSet on every
            // call (3.6.5–3.7.5), even for unknown names. Here the name object is our own.
            var str = newString(name);
            try {
                activate();
                var value = GciNative.GciResolveSymbolObj(str.value(), symbolList == null ? Oop.NIL.value() : symbolList.value());
                throwIfError();
                return value == Oop.ILLEGAL.value() ? null : new Oop(value);
            } finally {
                release(str);
            }
        });
    }

    /** Storage format of an object (GciFetchObjImpl). */
    public GciObjectFormat fetchFormat(Oop obj) {
        return locked(() -> {
            activate();
            var impl = GciNative.GciFetchObjImpl(obj.value());
            throwIfError();
            return GciObjectFormat.of(impl);
        });
    }

    /** Number of named instance variables (GciFetchNamedSize). */
    public int fetchNamedSize(Oop obj) {
        return locked(() -> {
            activate();
            var n = GciNative.GciFetchNamedSize(obj.value());
            throwIfError();
            return n;
        });
    }

    /** Number of indexed (unnamed) slots, or NSC members (GciFetchVaryingSize_). */
    public long fetchVaryingSize(Oop obj) {
        return locked(() -> {
            activate();
            var n = GciNative.GciFetchVaryingSize_(obj.value());
            throwIfError();
            return n;
        });
    }

    /** 1-based index of a named instance variable of {@code cls} (GciIvNameToIdx). */
    public int instVarIndex(Oop cls, String name) {
        return locked(() -> {
            activate();
            var index = GciNative.GciIvNameToIdx(cls.value(), name);
            throwIfError();
            if (index <= 0) throw new IllegalArgumentException("No instance variable '" + name + "' in " + cls + ".");
            return index;
        });
    }

    /** All named instance variables, in declaration order (GciFetchNamedOops). */
    public Oop[] fetchNamedOops(Oop obj) {
        return locked(() -> {
            var size = fetchNamedSize(obj);
            if (size == 0) return new Oop[0];
            try (var arena = Arena.ofConfined()) {
                var buf = arena.allocate(JAVA_LONG, size);
                activate();
                var n = GciNative.GciFetchNamedOops(obj.value(), 1, buf, size);
                throwIfError();
                return toOops(buf, n);
            }
        });
    }

    /** Named instance variable at a 1-based index. */
    public Oop fetchNamedOop(Oop obj, int index) {
        if (index < 1) throw new IndexOutOfBoundsException(index);
        return locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                var buf = arena.allocate(JAVA_LONG);
                var n = GciNative.GciFetchNamedOops(obj.value(), index, buf, 1);
                throwIfError();
                if (n != 1) throw new IndexOutOfBoundsException(obj + " has no named instance variable " + index + ".");
                return new Oop(buf.get(JAVA_LONG, 0));
            }
        });
    }

    /** Named instance variable by name (resolves the index via the object's class). */
    public Oop fetchNamedOop(Oop obj, String name) {
        return locked(() -> fetchNamedOop(obj, instVarIndex(fetchClass(obj), name)));
    }

    /** Stores into a named instance variable at a 1-based index (GciStoreNamedOops). */
    public void storeNamedOop(Oop obj, int index, Oop value) {
        if (index < 1) throw new IndexOutOfBoundsException(index);
        locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                GciNative.GciStoreNamedOops(obj.value(), index, arena.allocateFrom(JAVA_LONG, value.value()), 1);
            }
            throwIfError();
        });
    }

    /** Stores into a named instance variable by name. */
    public void storeNamedOop(Oop obj, String name, Oop value) {
        locked(() -> storeNamedOop(obj, instVarIndex(fetchClass(obj), name), value));
    }

    /** Named instance variable converted with {@link #toValue}. */
    public Object getInstVar(Oop obj, String name) {
        return toValue(fetchNamedOop(obj, name));
    }

    /**
     * Stores a Java value (converted with {@link #fromValue}) into a named instance variable. The
     * created object is released afterwards — the instance variable now references it.
     */
    public void setInstVar(Oop obj, String name, Object value) {
        locked(() -> {
            var oop = fromValue(value);
            try {
                storeNamedOop(obj, name, oop);
            } finally {
                if (!(value instanceof Oop)) release(oop);
            }
        });
    }

    /**
     * Indexed slots {@code start} … {@code start + count - 1} (GciFetchVaryingOops, in chunks);
     * {@code count} = -1 reads to the end. For an NSC the order is unspecified.
     */
    public Oop[] fetchVaryingOops(Oop obj, long start, long count) {
        if (start < 1) throw new IndexOutOfBoundsException("start " + start);
        return locked(() -> {
            var available = Math.max(0, fetchVaryingSize(obj) - start + 1);
            var total = (int) (count < 0 ? available : Math.min(count, available));
            var result = new ArrayList<Oop>(total);
            try (var arena = Arena.ofConfined()) {
                var buf = arena.allocate(JAVA_LONG, Math.max(1, Math.min(OOP_CHUNK_SIZE, total)));
                if (total > 0) activate(); // one round trip per chunk, counted once like the C# wrapper
                while (result.size() < total) {
                    var chunk = Math.min(OOP_CHUNK_SIZE, total - result.size());
                    var n = GciNative.GciFetchVaryingOops(obj.value(), start + result.size(), buf, chunk);
                    throwIfError();
                    if (n <= 0) break;
                    for (var i = 0; i < n; i++) result.add(new Oop(buf.getAtIndex(JAVA_LONG, i)));
                }
            }
            return result.toArray(Oop[]::new);
        });
    }

    /** All indexed slots. */
    public Oop[] fetchVaryingOops(Oop obj) {
        return fetchVaryingOops(obj, 1, -1);
    }

    /** Indexed slot at a 1-based index. */
    public Oop fetchVaryingOop(Oop obj, long index) {
        var oops = fetchVaryingOops(obj, index, 1);
        if (oops.length != 1) throw new IndexOutOfBoundsException(obj + " has no indexed slot " + index + ".");
        return oops[0];
    }

    /** Stores into indexed slots from {@code start} on; grows the object if needed (GciStoreIdxOops). */
    public void storeVaryingOops(Oop obj, long start, Oop... values) {
        if (start < 1) throw new IndexOutOfBoundsException("start " + start);
        locked(() -> {
            try (var arena = Arena.ofConfined()) {
                if (values.length > 0) activate();
                for (var done = 0; done < values.length; ) {
                    var chunk = Math.min(OOP_CHUNK_SIZE, values.length - done);
                    GciNative.GciStoreIdxOops(obj.value(), start + done, oops(arena, Arrays.copyOfRange(values, done, done + chunk)), chunk);
                    throwIfError();
                    done += chunk;
                }
            }
        });
    }

    /** Appends to the indexed slots (GciAppendOops). */
    public void appendOops(Oop obj, Oop... values) {
        locked(() -> {
            try (var arena = Arena.ofConfined()) {
                if (values.length > 0) activate();
                for (var done = 0; done < values.length; ) {
                    var chunk = Math.min(OOP_CHUNK_SIZE, values.length - done);
                    GciNative.GciAppendOops(obj.value(), chunk, oops(arena, Arrays.copyOfRange(values, done, done + chunk)));
                    throwIfError();
                    done += chunk;
                }
            }
        });
    }

    /** All indexed slots converted with {@link #toValue} (one level, no recursion). */
    public List<Object> toList(Oop obj) {
        return locked(() -> {
            var result = new ArrayList<Object>();
            for (var o : fetchVaryingOops(obj)) result.add(toValue(o));
            return result;
        });
    }

    /**
     * Overwrites bytes from a 1-based {@code start} on, growing the object if needed
     * (GciStoreBytes). Multi-byte objects (Unicode16 …) expect client byte order.
     */
    public void storeBytes(Oop obj, long start, byte[] bytes) {
        if (start < 1) throw new IndexOutOfBoundsException("start " + start);
        locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                GciNative.GciStoreBytesInstanceOf(Oop.NIL.value(), obj.value(), start,
                        bytes.length == 0 ? arena.allocate(1) : arena.allocateFrom(JAVA_BYTE, bytes), bytes.length);
            }
            throwIfError();
        });
    }

    /** Appends bytes to a byte object (GciAppendBytes). */
    public void appendBytes(Oop obj, byte[] bytes) {
        locked(() -> {
            activate();
            try (var arena = Arena.ofConfined()) {
                GciNative.GciAppendBytes(obj.value(), bytes.length, bytes.length == 0 ? arena.allocate(1) : arena.allocateFrom(JAVA_BYTE, bytes));
            }
            throwIfError();
        });
    }

    /** Adds an object to an NSC (GciAddOopToNsc). */
    public void addToNsc(Oop nsc, Oop value) {
        locked(() -> {
            activate();
            GciNative.GciAddOopToNsc(nsc.value(), value.value());
            throwIfError();
        });
    }

    /** Removes an object from an NSC; false if it was not a member (GciRemoveOopFromNsc). */
    public boolean removeFromNsc(Oop nsc, Oop value) {
        return locked(() -> {
            activate();
            var removed = GciNative.GciRemoveOopFromNsc(nsc.value(), value.value());
            throwIfError();
            return removed;
        });
    }

    /** Identity membership test (GciNscIncludesOop). */
    public boolean nscIncludes(Oop nsc, Oop value) {
        return locked(() -> {
            activate();
            var included = GciNative.GciNscIncludesOop(nsc.value(), value.value());
            throwIfError();
            return included;
        });
    }

    // =========================================================================================
    // Traversal
    // =========================================================================================

    /**
     * Traverses the graph below {@code root} (GciTraverseObjs + GciMoreTraversal).
     *
     * @param level 1 = only the root, 2 = also its instance variables, …; 0 = unlimited (beware:
     *     may fetch large parts of the repository)
     * @param options null = defaults
     */
    public GciTraversal traverse(Oop root, int level, GciTraversalOptions options) {
        return traverse(new Oop[] {root}, level, options);
    }

    /** {@link #traverse(Oop, int, GciTraversalOptions)} with default options. */
    public GciTraversal traverse(Oop root, int level) {
        return traverse(new Oop[] {root}, level, null);
    }

    /** {@link #traverse(Oop, int, GciTraversalOptions)} for several roots. */
    public GciTraversal traverse(Oop[] roots, int level, GciTraversalOptions options) {
        return traverse(roots, level, options, null);
    }

    /**
     * {@link #traverse(Oop[], int, GciTraversalOptions)} that hands the reports of each buffer to
     * {@code onReports} (the list and the index of the first new report) while the gem fills the
     * next buffer. Nonblocking: the GCI lock is free while the gem works.
     */
    GciTraversal traverse(Oop[] roots, int level, GciTraversalOptions options, ReportsListener onReports) {
        if (level < 0) throw new IllegalArgumentException("level must not be negative.");
        var opts = options == null ? GciTraversalOptions.DEFAULT : options;
        return locked(() -> {
            try (var arena = Arena.ofConfined(); var buffer = new TravBuffer(opts.bufferSize())) {
                var rootSeg = oops(arena, roots);
                activate();
                var args = clampedArgs(arena, buffer.segment(), level);
                if (opts.clampSpec() != null) {
                    args.set(JAVA_LONG, 0, opts.clampSpec().value());
                    GciNative.GciNbClampedTrav(rootSeg, roots.length, args);
                } else {
                    GciNative.GciNbTraverseObjs(rootSeg, roots.length, buffer.segment(), level);
                }
                var done = endNonblockingBool();
                return new GciTraversal(Arrays.asList(roots), null, collectAll(buffer, done, onReports));
            }
        });
    }

    /** Receives the reports of one traversal buffer: {@code reports} from index {@code first} on are new. */
    @FunctionalInterface
    interface ReportsListener {
        void accept(List<GciObjectReport> reports, int first);
    }

    /**
     * Executes Smalltalk and traverses the result ({@link #execute} + {@link #traverse}).
     * Deliberately not GciExecuteStrTrav: up to 3.7.5 it leaves the source String in the
     * PureExportSet with no way to release it. Costs one extra round trip.
     */
    public GciTraversal executeTraverse(String source, int level, GciTraversalOptions options) {
        var opts = options == null ? GciTraversalOptions.DEFAULT : options;
        return locked(() -> {
            var result = execute(source);
            try {
                var traversal = traverse(result, level, opts);
                return new GciTraversal(List.of(result), result, traversal.reports());
            } finally {
                if (!opts.keepResult()) release(result);
            }
        });
    }

    /** Sends a message and traverses the result in the same round trip (GciPerformTrav). */
    public GciTraversal performTraverse(Oop receiver, String selector, int level, Oop[] args, GciTraversalOptions options) {
        if (level < 0) throw new IllegalArgumentException("level must not be negative.");
        var opts = options == null ? GciTraversalOptions.DEFAULT : options;
        return locked(() -> {
            try (var arena = Arena.ofConfined(); var buffer = new TravBuffer(opts.bufferSize())) {
                var travArgs = clampedArgs(arena, buffer.segment(), level);
                var argv = args.length == 0 ? arena.allocateFrom(JAVA_LONG, Oop.NIL.value()) : oops(arena, args);
                activate();
                GciNative.GciNbPerformTrav(receiver.value(), selector, argv, args.length, travArgs);
                var done = endNonblockingBool();
                var result = new Oop(travArgs.get(JAVA_LONG, 8));
                var traversal = new GciTraversal(List.of(result), result, collectAll(buffer, done, null));
                if (!opts.keepResult()) release(result); // GciPerformTrav exports the result
                return traversal;
            }
        });
    }

    /**
     * Fetches instance-variable paths from many objects (GciFetchPaths). Each path is a sequence
     * of 1-based steps: {@code +n} = named instVar n, {@code -n} = indexed slot n. Result
     * {@code [i][j]} is path j applied to object i; a path that cannot be followed yields
     * {@link Oop#ILLEGAL}.
     *
     * <p>GemStone 3.6.5–3.7.6 crashes the gem when a path steps <em>from</em> a special object;
     * paths are therefore resolved one step at a time, never stepping from a special object.
     * Round trips = length of the longest path.
     */
    public Oop[][] fetchPaths(Oop[] objects, int[]... paths) {
        for (var path : paths)
            if (path == null || path.length == 0 || Arrays.stream(path).anyMatch(s -> s == 0))
                throw new IllegalArgumentException("Paths must be non-empty and must not contain 0.");
        var results = new Oop[objects.length][paths.length];
        var current = new Oop[objects.length][paths.length];
        var live = new ArrayList<int[]>();
        for (var i = 0; i < objects.length; i++)
            for (var j = 0; j < paths.length; j++) {
                current[i][j] = objects[i];
                if (canStepFrom(objects[i])) live.add(new int[] {i, j});
                else results[i][j] = Oop.ILLEGAL;
            }
        locked(() -> {
            var todo = live;
            for (var depth = 0; !todo.isEmpty(); depth++) {
                var d = depth;
                var fromIndex = new LinkedHashMap<Oop, Integer>();
                var stepIndex = new LinkedHashMap<Integer, Integer>();
                for (var p : todo) {
                    fromIndex.putIfAbsent(current[p[0]][p[1]], fromIndex.size());
                    stepIndex.putIfAbsent(paths[p[1]][d], stepIndex.size());
                }
                var fetched = fetchSingleSteps(fromIndex.keySet().toArray(Oop[]::new),
                        stepIndex.keySet().stream().mapToInt(Integer::intValue).toArray());
                var next = new ArrayList<int[]>();
                for (var p : todo) {
                    int i = p[0], j = p[1];
                    var value = fetched[fromIndex.get(current[i][j]) * stepIndex.size() + stepIndex.get(paths[j][d])];
                    if (d == paths[j].length - 1) results[i][j] = value;
                    else if (!canStepFrom(value)) results[i][j] = Oop.ILLEGAL;
                    else {
                        current[i][j] = value;
                        next.add(p);
                    }
                }
                todo = next;
            }
        });
        return results;
    }

    private static boolean canStepFrom(Oop o) {
        return !o.isSpecial() && !o.equals(Oop.ILLEGAL);
    }

    /** GciFetchPaths with one-step paths; results[from * steps.length + step]. */
    private Oop[] fetchSingleSteps(Oop[] froms, int[] steps) {
        try (var arena = Arena.ofConfined()) {
            var sizes = new int[steps.length];
            Arrays.fill(sizes, 1);
            var results = arena.allocate(JAVA_LONG, (long) froms.length * steps.length);
            activate();
            // FALSE just means at least one step failed (OOP_ILLEGAL in results); a real error is reported via GciErr.
            GciNative.GciFetchPaths(oops(arena, froms), froms.length, arena.allocateFrom(JAVA_INT, steps),
                    arena.allocateFrom(JAVA_INT, sizes), steps.length, results);
            throwIfError();
            return toOops(results, froms.length * steps.length);
        }
    }

    /** {@code GciClampedTravArgsSType}: clampSpec 0, resultOop 8, travBuff 16, level 24, retrievalFlags 28, isRpc 32. */
    private static MemorySegment clampedArgs(Arena arena, MemorySegment buffer, int level) {
        var args = arena.allocate(40, 8);
        args.set(JAVA_LONG, 0, Oop.NIL.value());
        args.set(JAVA_LONG, 8, Oop.NIL.value());
        args.set(java.lang.foreign.ValueLayout.ADDRESS, 16, buffer);
        args.set(JAVA_INT, 24, level);
        args.set(JAVA_INT, 28, 0);
        args.set(JAVA_INT, 32, 1); // the C++ constructor sets isRpc = 1
        return args;
    }

    /**
     * Copies the reports of the current buffer, then continues until done. Double-buffered:
     * GciNbMoreTraversal lets the gem fill a second buffer while the reports of the first are
     * copied (and handed to {@code onReports}), so the copying overlaps with the server's work.
     */
    List<GciObjectReport> collectAll(TravBuffer buffer, boolean done, ReportsListener onReports) {
        var reports = new ArrayList<GciObjectReport>();
        if (done) {
            buffer.readReports(reports);
            if (onReports != null) onReports.accept(reports, 0);
            return reports;
        }
        try (var second = new TravBuffer(buffer.bodySize())) {
            var current = buffer;
            var next = second;
            while (!done) {
                activate();
                GciNative.GciNbMoreTraversal(next.segment());
                try {
                    var first = reports.size();
                    current.readReports(reports);
                    if (onReports != null) onReports.accept(reports, first);
                } catch (Throwable t) {
                    drainNonblocking(); // the gem is still writing into the buffer
                    throw t;
                }
                done = endNonblockingBool();
                var swap = current;
                current = next;
                next = swap;
            }
            var last = reports.size();
            current.readReports(reports);
            if (onReports != null) onReports.accept(reports, last);
            return reports;
        }
    }

    /** Nanoseconds this session's traversals waited for the gem (guarded by SYNC). */
    long traversalWaitNanos;

    /** The thread waiting in {@link #waitReleasingLock} for this session's call (guarded by SYNC). */
    private Thread waitingThread;

    /** Condition for threads that want this session while another one waits for it with the lock released. */
    private static final java.util.concurrent.locks.Condition SESSION_FREE = SYNC.newCondition();

    /**
     * Waits for a nonblocking call that answers a BoolType (under the lock, session current;
     * returns the same way). The GCI lock is free while the gem works.
     */
    private boolean endNonblockingBool() {
        return endNonblocking() != 0;
    }

    /** Waits for a nonblocking call and answers its raw result (BoolType, OOP); errors of the call are thrown. */
    private long endNonblocking() {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_LONG);
            var start = System.nanoTime();
            var status = waitReleasingLock(out);
            traversalWaitNanos += System.nanoTime() - start;
            var value = out.get(JAVA_LONG, 0);
            if (status == GciNative.NB_HAS_ERROR || value == 0 || value == Oop.NIL.value()) throwIfError();
            if (status == GciNative.NB_HAS_ERROR) throw new GciException("The nonblocking call failed without an error report.");
            return value;
        }
    }

    /**
     * Waits for this session's running nonblocking call without holding the GCI lock: other
     * sessions (on other threads) can make calls while the gem works. Called under the lock with
     * the session current; the lock is released completely (also if the caller holds it several
     * times) and taken back as often, with the session current again. Meanwhile, other threads
     * that want this session wait in {@link #activate} instead of failing.
     */
    private int waitReleasingLock(MemorySegment out) {
        var status = GciNative.GciNbEndPoll(out, 0);
        if (status != GciNative.NB_NOT_READY && status != GciNative.NB_PROGRESSED) return status;
        var handle = GciNative.GciNbGetNotifyHandle();
        waitingThread = Thread.currentThread();
        try {
            while (true) {
                var held = SYNC.getHoldCount();
                for (var i = 0; i < held; i++) SYNC.unlock();
                try {
                    if (handle < 0 || GciNative.GciPollSocketForRead(handle, SOCKET_WAIT_SLICE_MS) < 0) {
                        handle = -1; // no handle, or polling failed: wait a little
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                } finally {
                    for (var i = 0; i < held; i++) SYNC.lock();
                }
                switchTo();
                status = GciNative.GciNbEndPoll(out, 0);
                if (status != GciNative.NB_NOT_READY && status != GciNative.NB_PROGRESSED) return status;
            }
        } finally {
            waitingThread = null;
            SESSION_FREE.signalAll();
        }
    }

    /** Lets a started nonblocking call finish after a client-side failure; its result and error are dropped. */
    private void drainNonblocking() {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_LONG);
            int status;
            do {
                status = GciNative.GciNbEndPoll(out, 1000);
            } while (status == GciNative.NB_NOT_READY || status == GciNative.NB_PROGRESSED);
        }
        lastError();
    }

    /** Waits (under the lock) while another thread waits for a call of this session with the lock released. */
    private void awaitSessionFree() {
        while (waitingThread != null && waitingThread != Thread.currentThread()) SESSION_FREE.awaitUninterruptibly();
    }

    /**
     * Round trip below which the stone counts as local (same machine): measured (C#) 0.015 ms on
     * loopback, 0.064 ms across a gigabit LAN, 0.28 ms between servers in a data centre. 0.05 ms,
     * not 0.04 ms as in C#: a fresh JVM measures 0.034–0.036 ms on loopback (see {@link #roundTrip}).
     */
    public static final java.time.Duration LOCAL_ROUND_TRIP_LIMIT = java.time.Duration.ofNanos(50_000);

    private java.time.Duration roundTrip;
    private int preferredBufferSize;

    /**
     * The shortest of some GCI round trips ({@code execute("nil")}), measured once per session on
     * first use: for 5 ms (at least 5, at most 200 calls). Not just 5 calls as in C#: in a fresh JVM
     * the first calls run interpreted and take about 50 µs on loopback (later 16–20 µs), which looked
     * like a network.
     */
    public java.time.Duration roundTrip() {
        return locked(() -> {
            if (roundTrip == null) {
                var best = Long.MAX_VALUE;
                var until = System.nanoTime() + 5_000_000;
                for (var i = 0; i < 200 && (i < 5 || System.nanoTime() < until); i++) {
                    var start = System.nanoTime();
                    execute("nil");
                    best = Math.min(best, System.nanoTime() - start);
                }
                roundTrip = java.time.Duration.ofNanos(best);
            }
            return roundTrip;
        });
    }

    /**
     * Traversal buffer size for this session: {@link GciTraversalOptions#DEFAULT_BUFFER_SIZE}
     * (64 KB) if the stone is local ({@link #roundTrip} below {@link #LOCAL_ROUND_TRIP_LIMIT}),
     * otherwise {@link GciTraversalOptions#NETWORK_BUFFER_SIZE} (1 MB). On one machine, traversals
     * with buffers of 128 KB or more stall about 40 ms per buffer (probably TCP delayed ACK on
     * loopback); across a network the larger buffer saves round trips. Used by object-mapper loads
     * unless their buffer size is set.
     */
    public int preferredTraversalBufferSize() {
        return locked(() -> {
            if (preferredBufferSize == 0)
                preferredBufferSize = roundTrip().compareTo(LOCAL_ROUND_TRIP_LIMIT) < 0
                        ? GciTraversalOptions.DEFAULT_BUFFER_SIZE : GciTraversalOptions.NETWORK_BUFFER_SIZE;
            return preferredBufferSize;
        });
    }

    /** A GciTravBufType in native memory: header {uint allocatedBytes; uint usedBytes;} + body. */
    static final class TravBuffer implements AutoCloseable {
        private static final int HEADER = 8;
        private static final int MIN_BODY = 2048; // GCI_MIN_TRAV_BUFF_SIZE
        private static final int REPORT_HEADER = 40; // GciObjRepHdrSType
        /** OOPs per shared block: 64K slots = 512 KB. */
        private static final int OOP_BLOCK_SIZE = 65_536;

        private final Arena arena = Arena.ofConfined();
        private final int bodySize;
        private final MemorySegment segment;
        private long[] block = new long[0];
        private int used;

        TravBuffer(int bodySize) {
            this.bodySize = Math.max(MIN_BODY, (bodySize + 7) & ~7);
            segment = arena.allocate(HEADER + this.bodySize, 8);
            reset();
        }

        MemorySegment segment() {
            return segment;
        }

        int bodySize() {
            return bodySize;
        }

        private void reset() {
            segment.set(JAVA_INT, 0, bodySize);
            segment.set(JAVA_INT, 4, 0);
        }

        void readReports(List<GciObjectReport> into) {
            var usedBytes = Integer.toUnsignedLong(segment.get(JAVA_INT, 4));
            if (usedBytes > bodySize) throw new GciException("Traversal buffer reports " + usedBytes + " used bytes of " + bodySize + ".");
            for (long offset = 0; offset + REPORT_HEADER <= usedBytes; ) {
                var h = HEADER + offset;
                // GciObjRepHdrSType: valueBuffSize 0 (int), namedSize 4 (short), securityPolicy 6,
                // objId 8, oclass 16, firstOffset 24, idxSizeBits 32 (size << 24 | flags).
                var valueBuffSize = segment.get(JAVA_INT, h);
                var namedSize = segment.get(java.lang.foreign.ValueLayout.JAVA_SHORT, h + 4);
                var objId = segment.get(JAVA_LONG, h + 8);
                var oclass = segment.get(JAVA_LONG, h + 16);
                var firstOffset = segment.get(JAVA_LONG, h + 24);
                var bits = segment.get(JAVA_LONG, h + 32);
                var impl = (int) (bits & 0x03);
                var values = h + REPORT_HEADER;
                long[] oopBlock = block;
                int start = 0, count = 0;
                byte[] bytes = EMPTY;
                if (impl == 1) {
                    bytes = segment.asSlice(values, valueBuffSize).toArray(JAVA_BYTE);
                } else if (impl == 0 || impl == 2) {
                    count = valueBuffSize / 8;
                    if (block.length - used < count) {
                        block = new long[Math.max(OOP_BLOCK_SIZE, count)];
                        used = 0;
                    }
                    MemorySegment.copy(segment, JAVA_LONG, values, block, used, count);
                    oopBlock = block;
                    start = used;
                    used += count;
                }
                into.add(new GciObjectReport(new Oop(objId), new Oop(oclass), GciObjectFormat.of(impl), namedSize,
                        bits >>> 24, firstOffset, (bits & 0x10) != 0, (bits & 0x08) != 0, (bits & 0x80) != 0,
                        (bits & 0x40) != 0, oopBlock, start, count, bytes));
                offset += REPORT_HEADER + ((valueBuffSize + 7) & ~7);
            }
            reset(); // GciMoreTraversal refills from the start
        }

        private static final byte[] EMPTY = new byte[0];

        @Override
        public void close() {
            arena.close();
        }
    }

    // =========================================================================================
    // Store traversal: create many objects and run code with them in two round trips
    // =========================================================================================

    private static final int GCI_STORE_TRAV_CREATE = 0x2; // gci.ht

    /**
     * GciGetFreeOops hands out at most this many OOPs per call; the rest of the result array is
     * silently left unchanged (3.6.5–3.7.6; not in the manual or the header).
     */
    static final int MAX_FREE_OOPS_PER_CALL = 100_000;

    /**
     * The Windows client library (3.7.6) fetches free OOPs in blocks of this size and fails on a
     * partial last block (error 2166 "lgcUncompress failed …"). Counts up to one block and whole
     * multiples work; the Linux library has no such problem.
     */
    static final int FREE_OOP_BLOCK = 2_000;

    /** Free OOPs fetched but not handed out yet (they stay allocated to the session until logout). */
    private final java.util.ArrayDeque<Long> freeOopPool = new java.util.ArrayDeque<>();

    /**
     * Allocates OOPs for objects that a store traversal creates (GciGetFreeOops). Counts above
     * 2,000 are rounded up to whole blocks; the extra OOPs are kept for the next call.
     */
    public Oop[] getFreeOops(int count) {
        if (count < 0) throw new IllegalArgumentException("count must not be negative.");
        var oops = new Oop[count];
        if (count == 0) return oops;
        return locked(() -> {
            var have = 0;
            while (have < count && !freeOopPool.isEmpty()) oops[have++] = new Oop(freeOopPool.pop());
            var need = count - have;
            if (need > 0) {
                var request = need <= FREE_OOP_BLOCK ? need : (need + FREE_OOP_BLOCK - 1) / FREE_OOP_BLOCK * FREE_OOP_BLOCK;
                try (var arena = Arena.ofConfined()) {
                    var fetched = arena.allocate(JAVA_LONG, request); // zeroed: a missing OOP stays 0
                    for (var done = 0; done < request; done += MAX_FREE_OOPS_PER_CALL) { // a whole number of blocks
                        var chunk = Math.min(MAX_FREE_OOPS_PER_CALL, request - done);
                        activate();
                        GciNative.GciGetFreeOops(chunk, fetched.asSlice(8L * done));
                        throwIfError();
                    }
                    for (var i = 0; i < request; i++)
                        if (fetched.getAtIndex(JAVA_LONG, i) == 0)
                            throw new GciException("GciGetFreeOops returned no OOP for index " + i + " of " + request + ".");
                    for (var i = 0; i < need; i++) oops[have + i] = new Oop(fetched.getAtIndex(JAVA_LONG, i));
                    for (var i = request - 1; i >= need; i--) freeOopPool.push(fetched.getAtIndex(JAVA_LONG, i));
                }
            }
            return oops;
        });
    }

    /**
     * Creates {@code objects} (GciStoreTrav with GCI_STORE_TRAV_CREATE) and then executes
     * {@code source} with {@code context} as {@code self} (GciExecuteStrFromContext): two round
     * trips, however many objects. The created objects and the result are in the PureExportSet:
     * the caller releases them.
     *
     * <p>Objects may reference each other in any order (the OOPs are allocated beforehand). A
     * store traversal replaces <em>all</em> instance variables of an existing object, so it is
     * only used to create new objects; the code does the rest. Not GciStoreTravDo_ (one round
     * trip less): it requires dirty tracking for the whole session (error 2086 otherwise).
     *
     * @param source ASCII Smalltalk code
     */
    public Oop storeAndExecute(List<GciNewObject> objects, String source, Oop context) {
        if (!isAscii(source)) throw new IllegalArgumentException("Source must be ASCII.");
        long body = 0;
        for (var o : objects) body += o.reportBytes();
        if (body > Integer.MAX_VALUE - 8) throw new IllegalArgumentException("Too much data for one store traversal.");
        var bodySize = Math.max(2048, (int) body);
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(8 + bodySize, 8); // zeroed
            buffer.set(JAVA_INT, 0, bodySize);
            buffer.set(JAVA_INT, 4, (int) body);
            long at = 8;
            for (var o : objects) {
                writeReport(buffer, at, o);
                at += o.reportBytes();
            }
            // Nonblocking, so that the GCI lock is free while the gem works (a large batch takes seconds).
            return locked(() -> {
                activate();
                GciNative.GciNbStoreTrav(buffer, GCI_STORE_TRAV_CREATE);
                endNonblocking();
                throwIfError();
                activate();
                GciNative.GciNbExecuteStrFromContext(source, context.value(), Oop.NIL.value());
                return checkResult(endNonblocking());
            });
        }
    }

    private static void writeReport(MemorySegment buf, long at, GciNewObject o) {
        long size = o.format() == GciObjectFormat.BYTE ? o.bytes().length : o.oops().length;
        long bits = (o.format() == GciObjectFormat.BYTE ? 1 : 0) | (o.indexable() ? 0x04L : 0) | ((long) o.swizzle() << 8);
        // GciObjRepHdrSType: valueBuffSize, namedSize, securityPolicyId, objId, oclass, firstOffset, idxSizeBits
        buf.set(JAVA_INT, at, o.valueBytes());
        buf.set(java.lang.foreign.ValueLayout.JAVA_SHORT, at + 4, (short) 0);
        buf.set(java.lang.foreign.ValueLayout.JAVA_SHORT, at + 6, (short) 0xFFFF); // UNDEFINED_ObjectSecurityPolicyId: the default policy
        buf.set(JAVA_LONG, at + 8, o.oop().value());
        buf.set(JAVA_LONG, at + 16, o.objectClass().value());
        buf.set(JAVA_LONG, at + 24, 1);
        buf.set(JAVA_LONG, at + 32, (o.indexable() ? size << 24 : 0) | bits);
        var values = at + 40;
        if (o.format() == GciObjectFormat.BYTE) MemorySegment.copy(o.bytes(), 0, buf, JAVA_BYTE, values, o.bytes().length);
        else MemorySegment.copy(o.oops(), 0, buf, JAVA_LONG, values, o.oops().length);
    }

    // =========================================================================================
    // Signals between sessions
    // =========================================================================================

    /** RT_ERR_SIGNAL_COMMIT: a member of the notify set was committed. */
    static final int SIGNAL_COMMIT_ERROR = 6008;
    /** RT_ERR_SIGNAL_GEMSTONE_SESSION: args serial, int, message, numPending. */
    static final int SIGNAL_GEMSTONE_SESSION_ERROR = 6010;
    private Oop systemClass;

    /**
     * Sends a signal to another session ({@code System sendSignal:to:withMessage:}), e.g. to a
     * {@link GciSignalListener}. The target receives it only if it has enabled session signals; it
     * is queued there until read.
     *
     * @param toSessionId the target's {@link #gemSessionId()}
     * @param value a number the receiver gets with the signal (a SmallInteger)
     * @param message a text the receiver gets with the signal; GemStone sends it as a byte String,
     *     so only characters up to U+00FF (Latin-1) are possible
     */
    public void sendSignal(long toSessionId, long value, String message) {
        Objects.requireNonNull(message, "message");
        if (!Oop.fitsSmallInteger(value)) throw new IllegalArgumentException("The signal value must be a SmallInteger.");
        if (message.chars().anyMatch(c -> c > 0xFF))
            throw new IllegalArgumentException("A signal message is a byte String on the server: only characters up to U+00FF (Latin-1).");
        locked(() -> {
            // The primitive accepts a String only, not a Unicode string (which newString makes of non-ASCII text).
            var text = newString(message);
            var latin1 = Oop.NIL;
            try {
                if (!isAscii(message)) latin1 = perform(KnownClasses.STRING, "withAll:", text);
                perform(systemClass(), "sendSignal:to:withMessage:", Oop.fromSmallInteger(value), Oop.fromSmallInteger(toSessionId),
                        latin1.isNil() ? text : latin1);
            } finally {
                release(text, latin1);
            }
        });
    }

    Oop systemClass() {
        return locked(() -> {
            if (systemClass == null) {
                systemClass = resolveSymbol("System");
                release(systemClass); // a kernel class: persistent
            }
            return systemClass;
        });
    }

    /**
     * Runs a GCI call that executes Smalltalk, on a session with signals enabled. A signal that
     * reached the session while it was idle surfaces as error 6008/6010 at the start of the call,
     * before any Smalltalk ran: it is recorded in {@code signals} and the call is repeated.
     */
    Oop callCapturingSignals(java.util.function.LongSupplier call, List<GciSessionSignal> signals) {
        return locked(() -> {
            for (var attempt = 1; ; attempt++) {
                activate();
                var result = new Oop(call.getAsLong());
                if (!result.isNil()) return result;
                var error = lastError();
                if (error == null) return result;
                if (attempt > 1000 || !tryCaptureSignal(error, signals)) {
                    discardError(error);
                    throw error;
                }
            }
        });
    }

    /**
     * If {@code error} is a signal (6008/6010), records it (6010 with its sender, value and text,
     * read before the error's OOPs are released), cleans up after the error and answers true.
     */
    boolean tryCaptureSignal(GciException error, List<GciSessionSignal> signals) {
        if (error.number() != SIGNAL_COMMIT_ERROR && error.number() != SIGNAL_GEMSTONE_SESSION_ERROR) return false;
        var args = error.args();
        if (error.number() == SIGNAL_GEMSTONE_SESSION_ERROR && args.size() >= 3) {
            var serial = new Oop(args.get(0));
            var value = new Oop(args.get(1));
            var text = new Oop(args.get(2));
            signals.add(new GciSessionSignal(serial.isSmallInteger() ? serial.toSmallInteger() : 0, null,
                    value.isSmallInteger() ? value.toSmallInteger() : 0, text.isNil() ? "" : toStringValue(text)));
        }
        // 6008 carries nothing: the objects stay in "System signaledObjects".
        discardError(error);
        return true;
    }

    /** Starts {@code receiver selector arg} without blocking; captured signals repeat the call (with fresh arguments). */
    GciFuture<Long> performCapturingSignalsAsync(Oop receiver, String selector, Supplier<Oop[]> args, List<GciSessionSignal> signals) {
        return startNonblocking(() -> startPerform(receiver, selector, args.get()), ResultKind.OOP,
                error -> tryCaptureSignal(error, signals), true);
    }

    // =========================================================================================
    // Asynchronous calls (GciNb* + GciNbEndPoll)
    // =========================================================================================
    //
    // The call is started on the calling thread (so a synchronous call right after it fails
    // deterministically), then a poller thread polls under the lock and sleeps without it
    // (1 → 32 ms), so other sessions can make calls meanwhile and N sessions run on their gems in
    // parallel. Each session supports one call at a time.

    /** Upper bound of the polling interval while waiting for a nonblocking call, in ms. */
    public static final int MAX_POLL_DELAY_MS = 32;
    /** How long one wait for the socket lasts before cancellation is checked again, in ms. */
    private static final int SOCKET_WAIT_SLICE_MS = 50;

    /**
     * Platform threads that wait for sockets (a native wait would pin a virtual thread's carrier);
     * kept for a minute, so that a series of short asynchronous calls does not start a thread each.
     */
    private static final java.util.concurrent.ExecutorService WAITERS = new java.util.concurrent.ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("gci-wait-", 0).factory());

    enum ResultKind { OOP, BOOL, VOID }

    /** {@link #execute(String)} without blocking the GCI; {@link GciFuture#cancel} sends a soft break. */
    public GciFuture<Oop> executeAsync(String source) {
        Objects.requireNonNull(source, "source");
        if (isAscii(source))
            return startNonblocking(() -> GciNative.GciNbExecuteStr(source, Oop.NIL.value()), ResultKind.OOP, null, true).map(Oop::new);
        var sourceObj = newUnicode(source);
        var future = startNonblocking(() -> GciNative.GciNbExecute(sourceObj.value(), Oop.NIL.value()), ResultKind.OOP, null, true);
        future.whenComplete((r, e) -> {
            if (isLoggedIn()) release(sourceObj);
        });
        return future.map(Oop::new);
    }

    /** {@link #perform} without blocking the GCI; cancellation as for {@link #executeAsync}. */
    public GciFuture<Oop> performAsync(Oop receiver, String selector, Oop... args) {
        Objects.requireNonNull(selector, "selector");
        var copy = args.clone();
        return startNonblocking(() -> startPerform(receiver, selector, copy), ResultKind.OOP, null, true).map(Oop::new);
    }

    /** {@link #evaluate(String)} asynchronously. */
    public GciFuture<Object> evaluateAsync(String source) {
        return executeAsync(source).map(this::convertAndRelease);
    }

    /** {@link #evaluate(String, Class)} asynchronously. */
    public <T> GciFuture<T> evaluateAsync(String source, Class<T> type) {
        return evaluateAsync(source).map(v -> castTo(v, type));
    }

    /** {@link #send} asynchronously. */
    public GciFuture<Object> sendAsync(Oop receiver, String selector, Object... args) {
        var argOops = locked(() -> {
            var a = new Oop[args.length];
            for (var i = 0; i < args.length; i++) a[i] = fromValue(args[i]);
            return a;
        });
        GciFuture<Oop> call;
        try {
            call = performAsync(receiver, selector, argOops);
        } catch (RuntimeException e) {
            releaseCreated(args, argOops);
            throw e;
        }
        return call.map(result -> {
            try {
                return convertAndRelease(result);
            } finally {
                releaseCreated(args, argOops);
            }
        });
    }

    /** {@link #tryCommit} asynchronously; false on a conflict. Not cancellable once started. */
    public GciFuture<Boolean> tryCommitAsync() {
        return startNonblocking(GciNative::GciNbCommit, ResultKind.BOOL, null, true).map(v -> {
            if (v == 0) return false;
            locked(() -> onTransactionEnded(true));
            return true;
        });
    }

    /** {@link #commit} asynchronously; fails with {@link GciTransactionConflictException} on a conflict. */
    public GciFuture<Void> commitAsync() {
        return tryCommitAsync().map(ok -> {
            if (!ok) throw new GciTransactionConflictException(tryGetConflictReport());
            return null;
        });
    }

    /** {@link #abort} asynchronously. */
    public GciFuture<Void> abortAsync() {
        return transactionEndAsync(GciNative::GciNbAbort);
    }

    /** {@link #begin} asynchronously. */
    public GciFuture<Void> beginAsync() {
        return transactionEndAsync(GciNative::GciNbBegin);
    }

    private GciFuture<Void> transactionEndAsync(Runnable start) {
        var future = startNonblocking(start, ResultKind.VOID, null, true);
        var result = new GciFuture<Void>(); // not cancellable: an abort is not interrupted
        // The listeners run before the result completes (as for the synchronous abort).
        future.whenComplete((v, e) -> {
            try {
                locked(() -> onTransactionEnded(false));
            } finally {
                if (e != null) result.completeExceptionally(e);
                else result.complete(null);
            }
        });
        return result;
    }

    private static void startPerform(Oop receiver, String selector, Oop[] args) {
        try (var arena = Arena.ofConfined()) {
            var argv = args.length == 0 ? arena.allocateFrom(JAVA_LONG, Oop.NIL.value()) : oops(arena, args);
            GciNative.GciNbPerform(receiver.value(), selector, argv, args.length);
        }
    }

    /**
     * Retries a call that failed because a signal arrived first: answers true if
     * {@code error} was such a signal and has been captured.
     */
    interface SignalCapture {
        boolean tryCapture(GciException error);
    }

    /**
     * Starts a nonblocking call on the calling thread and completes the future from a poller
     * thread. The raw result is the OOP or BoolType.
     *
     * @param capture if set, signals that make the call fail (6008/6010) are captured and the call repeated
     * @param waitOnSocket wait for the session's socket (GciNbGetNotifyHandle + GciPollSocketForRead,
     *     outside the lock) instead of polling with a growing delay: no CPU while waiting, and the
     *     result is picked up as soon as it arrives (a short call costs about as much as its
     *     synchronous form). All asynchronous calls do this. The poller is then a platform thread
     *     from {@link #WAITERS} (the native wait would pin a virtual thread's carrier).
     */
    GciFuture<Long> startNonblocking(Runnable start, ResultKind kind, SignalCapture capture, boolean waitOnSocket) {
        var future = new GciFuture<Long>();
        var handle = locked(() -> {
            activate();
            start.run();
            nbInProgress = true;
            return waitOnSocket ? GciNative.GciNbGetNotifyHandle() : -1;
        });
        Runnable poll = () -> {
            try {
                future.complete(pollUntilDone(start, kind, capture, handle, future));
            } catch (CancelledCall c) {
                future.completeCancelled(c.getCause());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (waitOnSocket) WAITERS.execute(poll);
        else Thread.ofVirtual().name("gci-poll-" + sessionId).start(poll);
        return future;
    }

    private static final class CancelledCall extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CancelledCall(GciException cause) {
            super(cause);
        }
    }

    private long pollUntilDone(Runnable start, ResultKind kind, SignalCapture capture, int firstHandle, GciFuture<?> future) throws InterruptedException {
        var handle = firstHandle;
        for (var attempt = 1; ; attempt++) {
            if (attempt > 1) {
                handle = locked(() -> {
                    activate();
                    start.run();
                    nbInProgress = true;
                    return firstHandle >= 0 ? GciNative.GciNbGetNotifyHandle() : -1;
                });
            }
            var breakSent = false;
            var delay = 1;
            while (true) {
                var state = tryFinish(kind, breakSent, capture, attempt);
                if (state.done) return state.value;
                if (state.retry) break;
                if (!breakSent && future.cancelRequested() && kind == ResultKind.OOP) {
                    locked(() -> {
                        if (nbInProgress) {
                            switchTo();
                            GciNative.GciSoftBreak(); // the call then completes with RT_ERR_SOFT_BREAK
                        }
                    });
                    breakSent = true;
                    continue;
                }
                if (handle >= 0) {
                    if (GciNative.GciPollSocketForRead(handle, SOCKET_WAIT_SLICE_MS) < 0) handle = -1; // fall back to timed polling
                    continue;
                }
                Thread.sleep(delay);
                if (delay < MAX_POLL_DELAY_MS) delay = Math.min(delay * 2, MAX_POLL_DELAY_MS);
            }
        }
    }

    private record PollState(boolean done, boolean retry, long value) {
        static final PollState PENDING = new PollState(false, false, 0);
        static final PollState RETRY = new PollState(false, true, 0);
    }

    /** One poll under the lock. */
    private PollState tryFinish(ResultKind kind, boolean cancelled, SignalCapture capture, int attempt) {
        return locked(() -> {
            switchTo(); // throws if the session was closed meanwhile
            try (var arena = Arena.ofConfined()) {
                var out = arena.allocate(JAVA_LONG);
                var status = GciNative.GciNbEndPoll(out, 0);
                if (status == GciNative.NB_NOT_READY || status == GciNative.NB_PROGRESSED) return PollState.PENDING;
                nbInProgress = false;
                var value = out.get(JAVA_LONG, 0);
                if (status == GciNative.NB_HAS_ERROR || kind == ResultKind.VOID
                        || (kind == ResultKind.OOP && value == Oop.NIL.value()) || (kind == ResultKind.BOOL && value == 0)) {
                    // nil / FALSE is also a valid result: only an error if GciErr has one
                    var error = lastError();
                    if (error != null) {
                        if (capture != null && attempt <= 1000 && capture.tryCapture(error)) return PollState.RETRY;
                        discardError(error);
                        if (cancelled) throw new CancelledCall(error);
                        throw error;
                    }
                }
                return new PollState(true, false, value);
            }
        });
    }

    /** Called from close (under the lock): interrupt and drain a pending nonblocking call before logging out. */
    private void finishPendingNonblockingCall() {
        GciNative.GciSoftBreak();
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(JAVA_LONG);
            var deadline = System.nanoTime() + 30_000_000_000L;
            int status;
            do {
                status = GciNative.GciNbEndPoll(out, 100);
            } while ((status == GciNative.NB_NOT_READY || status == GciNative.NB_PROGRESSED) && System.nanoTime() < deadline);
        }
        nbInProgress = false;
        lastError();
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    static boolean isAscii(String s) {
        for (var i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    static MemorySegment oops(Arena arena, Oop[] oops) {
        var seg = arena.allocate(JAVA_LONG, Math.max(1, oops.length));
        for (var i = 0; i < oops.length; i++) seg.setAtIndex(JAVA_LONG, i, oops[i].value());
        return seg;
    }

    static Oop[] toOops(MemorySegment seg, int count) {
        var result = new Oop[count];
        for (var i = 0; i < count; i++) result[i] = new Oop(seg.getAtIndex(JAVA_LONG, i));
        return result;
    }
}
