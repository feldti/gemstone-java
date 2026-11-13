package net.gs64.gci.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry points of the (non thread-safe) GemBuilder for C RPC library, called through
 * {@link Linker} downcall handles resolved by name on first use of this class. Signatures follow
 * {@code $GEMSTONE/include/gci.hf}: {@code OopType} and {@code int64} are {@code long},
 * {@code BoolType} is a C {@code int}, {@code const char[]} arguments are null-terminated UTF-8.
 *
 * <p>Pointers are {@link MemorySegment}s owned by the caller. String arguments are copied into a
 * confined arena per call. x64 and arm64 each have a single calling convention for non-variadic
 * functions (no GCI function used here is variadic), and the struct mirrors are the same on
 * both (LP64, little-endian).
 *
 * <p>Callers hold the process-wide GCI lock (except {@link #GciSoftBreak},
 * {@link #GciGetSessionId} and {@link #GciPollSocketForRead}).
 */
public final class GciNative {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final List<String> NAMES = new ArrayList<>();

    private GciNative() {
    }

    @SuppressWarnings("restricted") // the GCI is native code by nature
    private static MethodHandle fn(String name, FunctionDescriptor descriptor) {
        NAMES.add(name);
        return LINKER.downcallHandle(GciLibrary.export(name), descriptor);
    }

    @SuppressWarnings("restricted")
    private static MethodHandle optional(String name, FunctionDescriptor descriptor) {
        return GciLibrary.tryExport(name).map(a -> LINKER.downcallHandle(a, descriptor)).orElse(null);
    }

    private static FunctionDescriptor f(java.lang.foreign.MemoryLayout result, java.lang.foreign.MemoryLayout... args) {
        return FunctionDescriptor.of(result, args);
    }

    private static FunctionDescriptor v(java.lang.foreign.MemoryLayout... args) {
        return FunctionDescriptor.ofVoid(args);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    private static MemorySegment utf8(Arena arena, String s) {
        return s == null ? MemorySegment.NULL : arena.allocateFrom(s);
    }

    /** Names of all required entry points, for the export test. */
    public static List<String> entryPointNames() {
        return List.copyOf(NAMES);
    }

    // ---- Session ------------------------------------------------------------------------------

    private static final MethodHandle GciInit = fn("GciInit", f(JAVA_INT));
    private static final MethodHandle GciSetNet = fn("GciSetNet", v(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GciLoginEx = fn("GciLoginEx", f(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle GciGetSessionId = fn("GciGetSessionId", f(JAVA_INT));
    private static final MethodHandle GciSetSessionId = fn("GciSetSessionId", v(JAVA_INT));
    private static final MethodHandle GciLogout = fn("GciLogout", v());
    private static final MethodHandle GciErr = fn("GciErr", f(JAVA_INT, ADDRESS));

    /** BoolType GciInit(); — must be called once before any other GCI call. */
    public static boolean GciInit() {
        try {
            return (int) GciInit.invokeExact() != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciSetNet(stoneNameNrs, HostUserId, HostPassword, gemServiceNrs); */
    public static void GciSetNet(String stone, String hostUser, String hostPassword, String gemService) {
        try (var a = Arena.ofConfined()) {
            GciSetNet.invokeExact(utf8(a, stone), utf8(a, hostUser), utf8(a, hostPassword), utf8(a, gemService));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * BoolType GciLoginEx(user, password, unsigned int loginFlags, int haltOnErrNum);
     * haltOnErrNum: -1 keeps the GEM_HALT_ON_ERROR config setting, 0 never halts.
     */
    public static boolean GciLoginEx(String user, String password, int flags, int haltOnErrNum) {
        try (var a = Arena.ofConfined()) {
            return (int) GciLoginEx.invokeExact(utf8(a, user), utf8(a, password), flags, haltOnErrNum) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** GciSessionIdType (int) GciGetSessionId(); — 0 (GCI_INVALID_SESSION_ID) if not logged in. */
    public static int GciGetSessionId() {
        try {
            return (int) GciGetSessionId.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciSetSessionId(GciSessionIdType sessionId); — makes another logged-in session current. */
    public static void GciSetSessionId(int sessionId) {
        try {
            GciSetSessionId.invokeExact(sessionId);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciLogout(); */
    public static void GciLogout() {
        try {
            GciLogout.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciErr(GciErrSType *errorReport); — true if an error was pending. */
    public static boolean GciErr(MemorySegment report) {
        try {
            return (int) GciErr.invokeExact(report) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Execution ----------------------------------------------------------------------------

    private static final MethodHandle GciExecuteStr = fn("GciExecuteStr", f(JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle GciExecute = fn("GciExecute", f(JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciExecuteStrFromContext = fn("GciExecuteStrFromContext", f(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciNewUtf8String_ = fn("GciNewUtf8String_", f(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle GciPerform = fn("GciPerform", f(JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GciClearStack = fn("GciClearStack", v(JAVA_LONG));
    private static final MethodHandle GciSoftBreak = fn("GciSoftBreak", v());

    /** OopType GciExecuteStr(const char source[], OopType symbolList); — OOP_NIL on error. */
    public static long GciExecuteStr(String source, long symbolList) {
        try (var a = Arena.ofConfined()) {
            return (long) GciExecuteStr.invokeExact(utf8(a, source), symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciExecute(OopType source, OopType symbolList); — source is a String, Unicode or Utf8. */
    public static long GciExecute(long source, long symbolList) {
        try {
            return (long) GciExecute.invokeExact(source, symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciExecuteStrFromContext(const char source[], OopType contextObject, OopType symbolList); */
    public static long GciExecuteStrFromContext(String source, long context, long symbolList) {
        try (var a = Arena.ofConfined()) {
            return (long) GciExecuteStrFromContext.invokeExact(utf8(a, source), context, symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * OopType GciNewUtf8String_(const char* utf8data, size_t nBytes, BoolType utf8OrUnicode);
     * utf8OrUnicode false → instance of Utf8; true → Unicode7/16/32 (smallest that fits).
     */
    public static long GciNewUtf8String_(MemorySegment bytes, long nBytes, boolean unicode) {
        try {
            return (long) GciNewUtf8String_.invokeExact(bytes, nBytes, unicode ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciPerform(OopType receiver, const char selector[], const OopType args[], int numArgs); */
    public static long GciPerform(long receiver, String selector, MemorySegment args, int numArgs) {
        try (var a = Arena.ofConfined()) {
            return (long) GciPerform.invokeExact(receiver, utf8(a, selector), args, numArgs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciClearStack(OopType gsProcess); — abandon a suspended execution. */
    public static void GciClearStack(long process) {
        try {
            GciClearStack.invokeExact(process);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciSoftBreak(); — may be called from another thread while a call is in progress. */
    public static void GciSoftBreak() {
        try {
            GciSoftBreak.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Export set ---------------------------------------------------------------------------

    private static final MethodHandle GciReleaseOops = fn("GciReleaseOops", v(ADDRESS, JAVA_INT));
    private static final MethodHandle GciSaveObjs = fn("GciSaveObjs", v(ADDRESS, JAVA_INT));

    /** void GciReleaseOops(const OopType theOops[], int numOops); — remove from the PureExportSet. */
    public static void GciReleaseOops(MemorySegment oops, int count) {
        try {
            GciReleaseOops.invokeExact(oops, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciSaveObjs(const OopType theOops[], int numOops); — add to the PureExportSet. */
    public static void GciSaveObjs(MemorySegment oops, int count) {
        try {
            GciSaveObjs.invokeExact(oops, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Transactions -------------------------------------------------------------------------

    private static final MethodHandle GciCommit = fn("GciCommit", f(JAVA_INT));
    private static final MethodHandle GciAbort = fn("GciAbort", v());
    private static final MethodHandle GciBegin = fn("GciBegin", v());

    /** BoolType GciCommit(); — FALSE without a pending GciErr means a transaction conflict. */
    public static boolean GciCommit() {
        try {
            return (int) GciCommit.invokeExact() != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciAbort(); */
    public static void GciAbort() {
        try {
            GciAbort.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciBegin(); — aborts a transaction in progress, then begins a new one. */
    public static void GciBegin() {
        try {
            GciBegin.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Values -------------------------------------------------------------------------------

    private static final MethodHandle GciOopToI64_ = fn("GciOopToI64_", f(JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle GciI64ToOop = fn("GciI64ToOop", f(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciOopToFlt_ = optional("GciOopToFlt_", f(JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle GciOopToFlt = fn("GciOopToFlt", f(JAVA_DOUBLE, JAVA_LONG));
    private static final MethodHandle GciFltToOop = fn("GciFltToOop", f(JAVA_LONG, JAVA_DOUBLE));
    private static final MethodHandle GciNewString = fn("GciNewString", f(JAVA_LONG, ADDRESS));
    private static final MethodHandle GciNewSymbol = fn("GciNewSymbol", f(JAVA_LONG, ADDRESS));
    private static final MethodHandle GciFetchUtf8Bytes_ = fn("GciFetchUtf8Bytes_", f(JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle GciFetchClass = fn("GciFetchClass", f(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciIsKindOfClass = fn("GciIsKindOfClass", f(JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciNewDateAndTimePosix = fn("GciNewDateAndTimePosix", f(JAVA_LONG, JAVA_LONG, JAVA_INT));
    private static final MethodHandle GciFetchDateAndTimePosix = fn("GciFetchDateAndTimePosix", f(JAVA_INT, JAVA_LONG, ADDRESS));

    /** int64 GciOopToI64_(OopType arg, BoolType *error); */
    public static long GciOopToI64_(long oop, MemorySegment error) {
        try {
            return (long) GciOopToI64_.invokeExact(oop, error);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciI64ToOop(int64 arg); — SmallInteger or new LargeInteger. */
    public static long GciI64ToOop(long value) {
        try {
            return (long) GciI64ToOop.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** GciOopToFlt_ exists from GemStone 3.7 on. */
    public static boolean hasOopToFltWithStatus() {
        return GciOopToFlt_ != null;
    }

    /** BoolType GciOopToFlt_(OopType theObject, double* result); */
    public static boolean GciOopToFlt_(long oop, MemorySegment result) {
        try {
            return (int) GciOopToFlt_.invokeExact(oop, result) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** double GciOopToFlt(OopType theObject); — errors via GciErr; fallback for 3.6. */
    public static double GciOopToFlt(long oop) {
        try {
            return (double) GciOopToFlt.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciFltToOop(double aReal); — SmallDouble or new Float; OOP_NIL on error. */
    public static long GciFltToOop(double value) {
        try {
            return (long) GciFltToOop.invokeExact(value);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciNewString(const char *cString); — 8-bit String, pass ASCII only. */
    public static long GciNewString(String s) {
        try (var a = Arena.ofConfined()) {
            return (long) GciNewString.invokeExact(utf8(a, s));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciNewSymbol(const char *cString); — pass ASCII only. */
    public static long GciNewSymbol(String s) {
        try (var a = Arena.ofConfined()) {
            return (long) GciNewSymbol.invokeExact(utf8(a, s));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * int64 GciFetchUtf8Bytes_(OopType aString, int64 startIndex, ByteType *buf, int64 bufSize,
     * OopType *utf8String, int flags); — if the result == bufSize, fetch the rest from
     * *utf8String; release *utf8String afterwards if it differs from aString.
     */
    public static long GciFetchUtf8Bytes_(long string, long start, MemorySegment buf, long size, MemorySegment utf8, int flags) {
        try {
            return (long) GciFetchUtf8Bytes_.invokeExact(string, start, buf, size, utf8, flags);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciFetchClass(OopType theObject); — OOP_NIL on error. */
    public static long GciFetchClass(long oop) {
        try {
            return (long) GciFetchClass.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciIsKindOfClass(OopType anObj, OopType aClass); */
    public static boolean GciIsKindOfClass(long oop, long cls) {
        try {
            return (int) GciIsKindOfClass.invokeExact(oop, cls) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciNewDateAndTimePosix(int64 microseconds, int offsetSeconds); — Unix epoch (verified). */
    public static long GciNewDateAndTimePosix(long micros, int offsetSeconds) {
        try {
            return (long) GciNewDateAndTimePosix.invokeExact(micros, offsetSeconds);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciFetchDateAndTimePosix(OopType dtObj, GciPosixDateAndTime *result); — {int64 micros; int offset;} 16 bytes. */
    public static boolean GciFetchDateAndTimePosix(long oop, MemorySegment result) {
        try {
            return (int) GciFetchDateAndTimePosix.invokeExact(oop, result) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Objects ------------------------------------------------------------------------------
    // Signatures checked against gci.hf (3.7.5): note the int vs int64 index/count parameters.

    private static final MethodHandle GciFetchSize_ = fn("GciFetchSize_", f(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciFetchBytes_ = fn("GciFetchBytes_", f(JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle GciNewByteObj = fn("GciNewByteObj", f(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle GciNewOops = fn("GciNewOops", v(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle GciFetchNamedSize = fn("GciFetchNamedSize", f(JAVA_INT, JAVA_LONG));
    private static final MethodHandle GciFetchVaryingSize_ = fn("GciFetchVaryingSize_", f(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciFetchObjImpl = fn("GciFetchObjImpl", f(JAVA_INT, JAVA_LONG));
    private static final MethodHandle GciFetchNamedOops = fn("GciFetchNamedOops", f(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GciFetchVaryingOops = fn("GciFetchVaryingOops", f(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle GciStoreNamedOops = fn("GciStoreNamedOops", v(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle GciStoreIdxOops = fn("GciStoreIdxOops", v(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle GciAppendOops = fn("GciAppendOops", v(JAVA_LONG, JAVA_INT, ADDRESS));
    private static final MethodHandle GciStoreBytesInstanceOf = fn("GciStoreBytesInstanceOf", v(JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle GciAppendBytes = fn("GciAppendBytes", v(JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle GciResolveSymbolObj = fn("GciResolveSymbolObj", f(JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciIvNameToIdx = fn("GciIvNameToIdx", f(JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle GciAddOopToNsc = fn("GciAddOopToNsc", v(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciRemoveOopFromNsc = fn("GciRemoveOopFromNsc", f(JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciNscIncludesOop = fn("GciNscIncludesOop", f(JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciFetchPaths = fn("GciFetchPaths", f(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /** int64 GciFetchSize_(OopType theObject); */
    public static long GciFetchSize_(long oop) {
        try {
            return (long) GciFetchSize_.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int64 GciFetchBytes_(OopType theObject, int64 startIndex (1-based), ByteType theBytes[], int64 numBytes); */
    public static long GciFetchBytes_(long oop, long start, MemorySegment bytes, long count) {
        try {
            return (long) GciFetchBytes_.invokeExact(oop, start, bytes, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** OopType GciNewByteObj(OopType aClass, const ByteType *value, int64 valueSize); */
    public static long GciNewByteObj(long cls, MemorySegment bytes, long size) {
        try {
            return (long) GciNewByteObj.invokeExact(cls, bytes, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNewOops(int numOops, const OopType oclass[], const int64 idxSize[], OopType result[]); */
    public static void GciNewOops(int count, MemorySegment classes, MemorySegment idxSizes, MemorySegment result) {
        try {
            GciNewOops.invokeExact(count, classes, idxSizes, result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciFetchNamedSize(OopType theObject); */
    public static int GciFetchNamedSize(long oop) {
        try {
            return (int) GciFetchNamedSize.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int64 GciFetchVaryingSize_(OopType theObject); */
    public static long GciFetchVaryingSize_(long oop) {
        try {
            return (long) GciFetchVaryingSize_.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciFetchObjImpl(OopType theObject); — 0 pointer, 1 byte, 2 NSC, 3 special, -1 error. */
    public static int GciFetchObjImpl(long oop) {
        try {
            return (int) GciFetchObjImpl.invokeExact(oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciFetchNamedOops(OopType theObject, int startIndex, OopType theOops[], int numOops); */
    public static int GciFetchNamedOops(long oop, int start, MemorySegment result, int count) {
        try {
            return (int) GciFetchNamedOops.invokeExact(oop, start, result, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciFetchVaryingOops(OopType theObject, int64 startIndex, OopType theOops[], int numOops); */
    public static int GciFetchVaryingOops(long oop, long start, MemorySegment result, int count) {
        try {
            return (int) GciFetchVaryingOops.invokeExact(oop, start, result, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciStoreNamedOops(OopType theObject, int64 startIndex, const OopType theOops[], int numOops); */
    public static void GciStoreNamedOops(long oop, long start, MemorySegment values, int count) {
        try {
            GciStoreNamedOops.invokeExact(oop, start, values, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciStoreIdxOops(OopType theObject, int64 startIndex, const OopType theOops[], int numOops); */
    public static void GciStoreIdxOops(long oop, long start, MemorySegment values, int count) {
        try {
            GciStoreIdxOops.invokeExact(oop, start, values, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciAppendOops(OopType theObject, int numOops, const OopType *theOops); */
    public static void GciAppendOops(long oop, int count, MemorySegment values) {
        try {
            GciAppendOops.invokeExact(oop, count, values);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciStoreBytesInstanceOf(OopType theClass, OopType theObject, int64 startIndex, const ByteType theBytes[], int64 numBytes); */
    public static void GciStoreBytesInstanceOf(long cls, long oop, long start, MemorySegment bytes, long count) {
        try {
            GciStoreBytesInstanceOf.invokeExact(cls, oop, start, bytes, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciAppendBytes(OopType theObject, int64 numBytes, const ByteType *theBytes); */
    public static void GciAppendBytes(long oop, long count, MemorySegment bytes) {
        try {
            GciAppendBytes.invokeExact(oop, count, bytes);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * OopType GciResolveSymbolObj(OopType aString, OopType symbolList); — OOP_ILLEGAL without an
     * error if unknown. Not GciResolveSymbol: up to 3.7.5 it leaks a String in the export set.
     */
    public static long GciResolveSymbolObj(long name, long symbolList) {
        try {
            return (long) GciResolveSymbolObj.invokeExact(name, symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciIvNameToIdx(OopType oclass, const char instVarName[]); — 1-based, 0 if not found. */
    public static int GciIvNameToIdx(long cls, String name) {
        try (var a = Arena.ofConfined()) {
            return (int) GciIvNameToIdx.invokeExact(cls, utf8(a, name));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciAddOopToNsc(OopType theNsc, OopType theOop); */
    public static void GciAddOopToNsc(long nsc, long oop) {
        try {
            GciAddOopToNsc.invokeExact(nsc, oop);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciRemoveOopFromNsc(OopType theNsc, OopType theOop); */
    public static boolean GciRemoveOopFromNsc(long nsc, long oop) {
        try {
            return (int) GciRemoveOopFromNsc.invokeExact(nsc, oop) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciNscIncludesOop(OopType theNsc, OopType theOop); */
    public static boolean GciNscIncludesOop(long nsc, long oop) {
        try {
            return (int) GciNscIncludesOop.invokeExact(nsc, oop) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * BoolType GciFetchPaths(const OopType theOops[], int numOops, const int paths[], const int
     * pathSizes[], int numPaths, OopType results[]); — crashes the gem (3.6.5–3.7.6) if a step
     * starts at a special object: only call with single-step paths.
     */
    public static boolean GciFetchPaths(MemorySegment oops, int numOops, MemorySegment paths, MemorySegment sizes, int numPaths, MemorySegment results) {
        try {
            return (int) GciFetchPaths.invokeExact(oops, numOops, paths, sizes, numPaths, results) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Nonblocking calls --------------------------------------------------------------------

    /** GciNbProgressEType: GCI_RESULT_NOT_READY. */
    public static final int NB_NOT_READY = 0;
    /** GCI_RESULT_PROGRESSED. */
    public static final int NB_PROGRESSED = 1;
    /** GCI_RESULT_READY. */
    public static final int NB_READY = 2;
    /** GCI_RESULT_HAS_ERROR (only in gci.hf). */
    public static final int NB_HAS_ERROR = 3;

    private static final MethodHandle GciNbEndPoll = fn("GciNbEndPoll", f(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GciNbGetNotifyHandle = fn("GciNbGetNotifyHandle", f(JAVA_INT));
    private static final MethodHandle GciPollSocketForRead = fn("GciPollSocketForRead", f(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle GciNbExecuteStr = fn("GciNbExecuteStr", v(ADDRESS, JAVA_LONG));
    private static final MethodHandle GciNbExecute = fn("GciNbExecute", v(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GciNbPerform = fn("GciNbPerform", v(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GciNbCommit = fn("GciNbCommit", v());
    private static final MethodHandle GciNbAbort = fn("GciNbAbort", v());
    private static final MethodHandle GciNbBegin = fn("GciNbBegin", v());

    /** GciNbProgressEType GciNbEndPoll(int64 *vOut, int timeoutMs); — timeoutMs 0 just checks. */
    public static int GciNbEndPoll(MemorySegment result, int timeoutMs) {
        try {
            return (int) GciNbEndPoll.invokeExact(result, timeoutMs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciNbGetNotifyHandle(void); — descriptor of the current session; -1 if not available. */
    public static int GciNbGetNotifyHandle() {
        try {
            return (int) GciNbGetNotifyHandle.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** int GciPollSocketForRead(int socketFd, int timeoutMs); — thread-safe, no GCI lock needed. */
    public static int GciPollSocketForRead(int fd, int timeoutMs) {
        try {
            return (int) GciPollSocketForRead.invokeExact(fd, timeoutMs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbExecuteStr(const char source[], OopType symbolList); */
    public static void GciNbExecuteStr(String source, long symbolList) {
        try (var a = Arena.ofConfined()) {
            GciNbExecuteStr.invokeExact(utf8(a, source), symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbExecute(OopType source, OopType symbolList); */
    public static void GciNbExecute(long source, long symbolList) {
        try {
            GciNbExecute.invokeExact(source, symbolList);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbPerform(OopType receiver, const char selector[], const OopType args[], int numArgs); */
    public static void GciNbPerform(long receiver, String selector, MemorySegment args, int numArgs) {
        try (var a = Arena.ofConfined()) {
            GciNbPerform.invokeExact(receiver, utf8(a, selector), args, numArgs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbCommit(); */
    public static void GciNbCommit() {
        try {
            GciNbCommit.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbAbort(); */
    public static void GciNbAbort() {
        try {
            GciNbAbort.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciNbBegin(); */
    public static void GciNbBegin() {
        try {
            GciNbBegin.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- Traversal ----------------------------------------------------------------------------
    // All return TRUE when complete, FALSE if more reports follow (GciMoreTraversal) — or on
    // error, so GciErr must be checked after every call.

    private static final MethodHandle GciGetFreeOops = fn("GciGetFreeOops", v(JAVA_INT, ADDRESS));
    private static final MethodHandle GciStoreTrav = fn("GciStoreTrav", v(ADDRESS, JAVA_INT));
    private static final MethodHandle GciTraverseObjs = fn("GciTraverseObjs", f(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GciClampedTrav = fn("GciClampedTrav", f(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle GciMoreTraversal = fn("GciMoreTraversal", f(JAVA_INT, ADDRESS));
    private static final MethodHandle GciPerformTrav = fn("GciPerformTrav", f(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    /**
     * void GciGetFreeOops(int count, OopType *resultOops); — at most 100,000 per call, the rest of
     * the array is left unchanged; the Windows client fails for counts above 2,000 that are not
     * a multiple of 2,000.
     */
    public static void GciGetFreeOops(int count, MemorySegment result) {
        try {
            GciGetFreeOops.invokeExact(count, result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** void GciStoreTrav(GciTravBufType *travBuff, int flag); */
    public static void GciStoreTrav(MemorySegment buffer, int flag) {
        try {
            GciStoreTrav.invokeExact(buffer, flag);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciTraverseObjs(const OopType theOops[], int numOops, GciTravBufType *travBuff, int level); */
    public static boolean GciTraverseObjs(MemorySegment oops, int count, MemorySegment buffer, int level) {
        try {
            return (int) GciTraverseObjs.invokeExact(oops, count, buffer, level) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciClampedTrav(const OopType *theOops, int numOops, GciClampedTravArgsSType *args); */
    public static boolean GciClampedTrav(MemorySegment oops, int count, MemorySegment args) {
        try {
            return (int) GciClampedTrav.invokeExact(oops, count, args) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciMoreTraversal(GciTravBufType *travBuff); */
    public static boolean GciMoreTraversal(MemorySegment buffer) {
        try {
            return (int) GciMoreTraversal.invokeExact(buffer) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** BoolType GciPerformTrav(OopType receiver, const char *selector, const OopType *args, int numArgs, GciClampedTravArgsSType *travArgs); */
    public static boolean GciPerformTrav(long receiver, String selector, MemorySegment args, int numArgs, MemorySegment travArgs) {
        try (var a = Arena.ofConfined()) {
            return (int) GciPerformTrav.invokeExact(receiver, utf8(a, selector), args, numArgs, travArgs) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
