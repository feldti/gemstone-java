package net.gs64.gci;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

/**
 * One object report of a traversal ({@code GciObjRepSType}), copied into Java memory.
 *
 * <p>The OOPs of pointer objects and NSCs share {@code long[]} blocks with other reports of the
 * same traversal: a large load reports hundreds of thousands of objects, and an array of its own
 * per report is one more object for the GC to track. {@link #oops()} is a view.
 */
public final class GciObjectReport {
    private final Oop oop;
    private final Oop cls;
    private final GciObjectFormat format;
    private final int namedSize;
    private final long indexedSize;
    private final long firstOffset;
    private final boolean partial;
    private final boolean invariant;
    private final boolean clamped;
    private final boolean noReadAuthorization;
    private final long[] block;
    private final int start;
    private final int count;
    private final byte[] bytes;

    GciObjectReport(Oop oop, Oop cls, GciObjectFormat format, int namedSize, long indexedSize, long firstOffset,
                    boolean partial, boolean invariant, boolean clamped, boolean noReadAuthorization,
                    long[] block, int start, int count, byte[] bytes) {
        this.oop = oop;
        this.cls = cls;
        this.format = format;
        this.namedSize = namedSize;
        this.indexedSize = indexedSize;
        this.firstOffset = firstOffset;
        this.partial = partial;
        this.invariant = invariant;
        this.clamped = clamped;
        this.noReadAuthorization = noReadAuthorization;
        this.block = block;
        this.start = start;
        this.count = count;
        this.bytes = bytes;
    }

    public Oop oop() {
        return oop;
    }

    /** The class; nil if {@link #noReadAuthorization()}. */
    public Oop objectClass() {
        return cls;
    }

    public GciObjectFormat format() {
        return format;
    }

    public int namedSize() {
        return namedSize;
    }

    /** Number of indexed slots (or NSC members), even if {@link #isPartial()}. */
    public long indexedSize() {
        return indexedSize;
    }

    /** Offset of the first value in the object (1 = first named instVar). */
    public long firstOffset() {
        return firstOffset;
    }

    /** The value buffer does not hold the whole object. */
    public boolean isPartial() {
        return partial;
    }

    public boolean isInvariant() {
        return invariant;
    }

    public boolean isClamped() {
        return clamped;
    }

    /** No read authorization: no values, the class is nil. */
    public boolean noReadAuthorization() {
        return noReadAuthorization;
    }

    /** Number of values in {@link #oops()}. */
    public int oopCount() {
        return count;
    }

    /** Raw value {@code i} (0-based) of {@link #oops()}, without an {@link Oop} object. */
    public long oopAt(int i) {
        if (i < 0 || i >= count) throw new IndexOutOfBoundsException(i);
        return block[start + i];
    }

    /** Values of a pointer object or NSC: named instVars, then indexed slots / members. */
    public List<Oop> oops() {
        return new OopView(block, start, count);
    }

    /** Index in {@link #oops()} where the indexed slots begin. */
    public int indexedStart() {
        return (int) Math.min(count, Math.max(0, namedSize - firstOffset + 1));
    }

    /** Indexed slots / NSC members present in this report. */
    public List<Oop> indexed() {
        var skip = indexedStart();
        return new OopView(block, start + skip, count - skip);
    }

    /** Raw indexed slots, copied. */
    public long[] indexedValues() {
        var skip = indexedStart();
        return Arrays.copyOfRange(block, start + skip, start + count);
    }

    /** Named instance variable (1-based). */
    public Oop named(int index) {
        return new Oop(namedValue(index));
    }

    /** Raw named instance variable (1-based). */
    public long namedValue(int index) {
        if (index < 1 || index > namedSize) throw new IndexOutOfBoundsException("named " + index + " of " + namedSize);
        return block[start + (int) (index - firstOffset)];
    }

    /** Value buffer of a byte object, in client byte order (the GCI swizzles). Not copied: do not modify. */
    public byte[] bytes() {
        return bytes;
    }

    /** Appends a continuation report (same object, later firstOffset). */
    GciObjectReport mergeWith(GciObjectReport next) {
        var have = format == GciObjectFormat.BYTE ? bytes.length : count;
        if (next.firstOffset != firstOffset + have)
            throw new GciException("Unexpected continuation report for " + oop + ": offset " + next.firstOffset
                    + ", expected " + (firstOffset + have) + ".");
        var oops = new long[count + next.count];
        System.arraycopy(block, start, oops, 0, count);
        System.arraycopy(next.block, next.start, oops, count, next.count);
        var allBytes = new byte[bytes.length + next.bytes.length];
        System.arraycopy(bytes, 0, allBytes, 0, bytes.length);
        System.arraycopy(next.bytes, 0, allBytes, bytes.length, next.bytes.length);
        var total = format == GciObjectFormat.BYTE ? indexedSize : namedSize + indexedSize;
        var nowHave = format == GciObjectFormat.BYTE ? allBytes.length : oops.length;
        return new GciObjectReport(oop, cls, format, namedSize, indexedSize, firstOffset,
                firstOffset != 1 || nowHave < total, invariant, clamped, noReadAuthorization, oops, 0, oops.length, allBytes);
    }

    @Override
    public String toString() {
        return oop + " class " + cls + " " + format + " named " + namedSize + " indexed " + indexedSize;
    }

    private static final class OopView extends AbstractList<Oop> {
        private final long[] block;
        private final int start;
        private final int count;

        OopView(long[] block, int start, int count) {
            this.block = block;
            this.start = start;
            this.count = count;
        }

        @Override
        public Oop get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return new Oop(block[start + index]);
        }

        @Override
        public int size() {
            return count;
        }
    }
}
