package net.gs64.gci;

/** Storage format of an object ({@code GciFetchObjImpl}). */
public enum GciObjectFormat {
    /** Named and/or indexed OOP slots (Object, Array, OrderedCollection …). */
    POINTER,
    /** Bytes (String, ByteArray, Float …). */
    BYTE,
    /** Non-sequenceable collection (IdentitySet, IdentityBag …). */
    NSC,
    /** Immediate value (SmallInteger, Character, nil …). */
    SPECIAL;

    static GciObjectFormat of(int impl) {
        return switch (impl) {
            case 0 -> POINTER;
            case 1 -> BYTE;
            case 2 -> NSC;
            case 3 -> SPECIAL;
            default -> throw new GciException("Unknown object implementation " + impl);
        };
    }
}
