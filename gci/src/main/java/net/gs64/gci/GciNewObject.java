package net.gs64.gci;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A new object for {@link GciSession#storeAndExecute}: pointer objects (Array …) with their
 * indexed slots, byte objects (String, Utf8, ByteArray, Float) with their bytes. Its OOP comes
 * from {@link GciSession#getFreeOops}.
 *
 * @param oop the pre-allocated OOP
 * @param objectClass the class
 * @param format POINTER or BYTE
 * @param oops indexed slots of a pointer object (raw OOP values)
 * @param bytes contents of a byte object
 * @param indexable whether the size goes into the report (false only for Float)
 * @param swizzle GciByteSwizEType: 0 = none, 3 = 8-byte values (Float)
 */
public record GciNewObject(Oop oop, Oop objectClass, GciObjectFormat format, long[] oops, byte[] bytes, boolean indexable, int swizzle) {
    private static final long[] NO_OOPS = new long[0];
    private static final byte[] NO_BYTES = new byte[0];

    /** A pointer object with indexed slots. */
    public static GciNewObject pointers(Oop oop, Oop cls, Oop... slots) {
        var raw = new long[slots.length];
        for (var i = 0; i < slots.length; i++) raw[i] = slots[i].value();
        return new GciNewObject(oop, cls, GciObjectFormat.POINTER, raw, NO_BYTES, true, 0);
    }

    /** A pointer object with indexed slots given as raw OOP values (no Oop objects for large batches). */
    public static GciNewObject pointers(Oop oop, Oop cls, long[] slots) {
        return new GciNewObject(oop, cls, GciObjectFormat.POINTER, slots, NO_BYTES, true, 0);
    }

    /** A byte object (String, Utf8, ByteArray …). */
    public static GciNewObject byteObject(Oop oop, Oop cls, byte[] bytes) {
        return new GciNewObject(oop, cls, GciObjectFormat.BYTE, NO_OOPS, bytes, true, 0);
    }

    /** A Float (8 bytes, not indexable, swizzled as one 8-byte value). */
    public static GciNewObject ofFloat(Oop oop, double value) {
        var b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array();
        return new GciNewObject(oop, KnownClasses.FLOAT, GciObjectFormat.BYTE, NO_OOPS, b, false, 3);
    }

    int valueBytes() {
        return format == GciObjectFormat.BYTE ? bytes.length : oops.length * 8;
    }

    int reportBytes() {
        return 40 + ((valueBytes() + 7) & ~7);
    }
}
