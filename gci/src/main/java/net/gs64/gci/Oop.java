package net.gs64.gci;

/**
 * A GemStone object-oriented pointer ({@code OopType}, 64 bit in GemStone/S 64 Bit).
 *
 * <p>Special objects (SmallInteger, SmallDouble, Character, Boolean, nil …) are immediate values
 * encoded in the OOP itself, so equal values compare equal as OOPs and need no round trip. The
 * value is an unsigned 64-bit number held in a {@code long}.
 *
 * <p>Constants and the tag-bit encoding are ported from {@code $GEMSTONE/include/gcioop.ht} and
 * the {@code GCI_*} macros in {@code gcicmn.ht} (3.7.5); the manual names them but does not list
 * values.
 *
 * @param value the raw OOP bits
 */
public record Oop(long value) {
    // ---- Tag bits (gcioop.ht) ----------------------------------------------------------------
    private static final long RAM_TAG_MASK = 0x7;      // OOP_RAM_TAG_MASK
    private static final long TAG_POM_OOP = 0x1;       // OOP_TAG_POM_OOP: object in the object table
    private static final long TAG_SMALL_INT = 0x2;     // OOP_TAG_SMALLINT
    private static final long TAG_SPECIAL = 0x4;       // OOP_TAG_SPECIAL
    private static final long TAG_SMALL_DOUBLE = 0x6;  // OOP_TAG_SMALLDOUBLE
    private static final int NUM_TAG_BITS = 3;         // OOP_NUM_TAG_BITS
    private static final long ASCII_NUL = 0x1C;        // OOP_ASCII_NUL: Character with value 0
    private static final int SPECIAL_VALUE_SHIFT = 8;  // GCI_OOP_SPECIAL_VALUE_SHIFT
    private static final int MAX_CODE_POINT = 0x10FFFF;
    private static final long SPECIAL_CLASS_MASK = 0xFE;             // OOP_SPECIAL_CLASS_MASK
    private static final long SMALL_FRACTION_CLASS_FIELD = 40;       // OOP_SPECIAL_SmallFractionCLASS_FIELD
    private static final long SMALL_SCALED_DECIMAL_CLASS_FIELD = 48; // OOP_SPECIAL_SmallScaledDecimalCLASS_FIELD
    private static final long SMALL_DATE_AND_TIME_CLASS_FIELD = 56;  // OOP_SPECIAL_SmallDateAndTimeCLASS_FIELD
    private static final long SMALL_TIME_CLASS_FIELD = 64;           // OOP_SPECIAL_SmallTimeCLASS_FIELD
    private static final long SMALL_DATE_CLASS_FIELD = 72;           // OOP_SPECIAL_SmallDateCLASS_FIELD
    private static final long SMALL_DOUBLE_EXPONENT_BASE = 0x380;

    /** {@code OOP_ILLEGAL} — never refers to any object. */
    public static final Oop ILLEGAL = new Oop(0x01);
    /** {@code OOP_NIL}. */
    public static final Oop NIL = new Oop(0x14);
    /** {@code OOP_FALSE}. */
    public static final Oop FALSE = new Oop(0x0C);
    /** {@code OOP_TRUE}. */
    public static final Oop TRUE = new Oop(0x10C);

    /** Smallest SmallInteger (61-bit two's complement). */
    public static final long SMALL_INTEGER_MIN = -(1L << 60);
    /** Largest SmallInteger. */
    public static final long SMALL_INTEGER_MAX = (1L << 60) - 1;

    public boolean isNil() {
        return value == NIL.value;
    }

    /** {@code GCI_OOP_IS_SPECIAL} — immediate value, not a heap object. */
    public boolean isSpecial() {
        return (value & RAM_TAG_MASK) != 0 && (value & TAG_POM_OOP) == 0;
    }

    /** {@code GCI_OOP_IS_SMALL_INT}. */
    public boolean isSmallInteger() {
        return (value & RAM_TAG_MASK) == TAG_SMALL_INT;
    }

    /** {@code GCI_OOP_IS_SMALL_DOUBLE}. */
    public boolean isSmallDouble() {
        return (value & RAM_TAG_MASK) == TAG_SMALL_DOUBLE;
    }

    /** {@code GCI_OOP_IS_BOOL}. */
    public boolean isBoolean() {
        return (value & 0xFF) == FALSE.value;
    }

    /** {@code GCI_OOP_IS_CHAR}. */
    public boolean isCharacter() {
        return (value & 0xFF) == ASCII_NUL;
    }

    /** {@code GCI_OOP_IS_SmallDateAndTime}. */
    public boolean isSmallDateAndTime() {
        return hasSpecialClass(SMALL_DATE_AND_TIME_CLASS_FIELD);
    }

    /** {@code GCI_OOP_IS_SmallDate}. */
    public boolean isSmallDate() {
        return hasSpecialClass(SMALL_DATE_CLASS_FIELD);
    }

    /** {@code GCI_OOP_IS_SmallTime}. */
    public boolean isSmallTime() {
        return hasSpecialClass(SMALL_TIME_CLASS_FIELD);
    }

    /** {@code GCI_OOP_IS_SMALL_FRACTION}. */
    public boolean isSmallFraction() {
        return hasSpecialClass(SMALL_FRACTION_CLASS_FIELD);
    }

    /** {@code GCI_OOP_IS_SmallScaledDecimal}. */
    public boolean isSmallScaledDecimal() {
        return hasSpecialClass(SMALL_SCALED_DECIMAL_CLASS_FIELD);
    }

    private boolean hasSpecialClass(long classField) {
        return (value & SPECIAL_CLASS_MASK) == (classField | TAG_SPECIAL);
    }

    // ---- Pure conversions (macros, no GCI call) -------------------------------------------

    /** {@code GCI_I64_IS_SMALL_INT}. */
    public static boolean fitsSmallInteger(long value) {
        return value >= SMALL_INTEGER_MIN && value <= SMALL_INTEGER_MAX;
    }

    /**
     * SmallInteger OOP for {@code value} ({@code value << 3 | tag}).
     *
     * @throws IllegalArgumentException outside the SmallInteger range — use
     *     {@link GciSession#newInteger}, which creates a LargeInteger
     */
    public static Oop fromSmallInteger(long value) {
        if (!fitsSmallInteger(value))
            throw new IllegalArgumentException(value + " is outside the SmallInteger range.");
        return new Oop((value << NUM_TAG_BITS) | TAG_SMALL_INT);
    }

    /** Value of a SmallInteger OOP (arithmetic shift keeps the sign). */
    public long toSmallInteger() {
        if (!isSmallInteger()) throw notA("SmallInteger");
        return value >> NUM_TAG_BITS;
    }

    /**
     * {@code GCI_OOP_TO_SMALL_DOUBLE} (gcicmn.ht): a SmallDouble stores sign, an 8-bit exponent
     * (re-biased by 0x380) and 52 fraction bits in the OOP itself.
     */
    public double toSmallDouble() {
        if (!isSmallDouble()) throw notA("SmallDouble");
        long result = 0;
        if ((value >>> (NUM_TAG_BITS + 1)) != 0) {
            long expBits = (value >>> 56) + SMALL_DOUBLE_EXPONENT_BASE;
            result = ((value << 8) >>> 12) | (expBits << 52);
        }
        result |= (value & 0x8) << 60; // sign bit
        return Double.longBitsToDouble(result);
    }

    /**
     * Inverse of {@link #toSmallDouble} (what GciFltToOop does locally): ±0 and doubles with an
     * exponent of 0x380–0x47F (about 6e-39 to 6e38) are SmallDoubles; everything else (larger,
     * smaller, NaN, infinities) needs a Float object.
     *
     * @return the SmallDouble, or {@code null} if the value needs a Float object
     */
    public static Oop tryFromSmallDouble(double value) {
        long bits = Double.doubleToRawLongBits(value);
        long sign = bits >>> 63;
        long exponent = (bits >>> 52) & 0x7FF;
        long fraction = bits & 0xF_FFFF_FFFF_FFFFL;
        if (exponent == 0 && fraction == 0) return new Oop((sign << 3) | TAG_SMALL_DOUBLE);
        // Exponent 0x380 only with a non-zero fraction: all-zero bits above the sign are ±0.
        if (exponent < SMALL_DOUBLE_EXPONENT_BASE || exponent > SMALL_DOUBLE_EXPONENT_BASE + 0xFF
                || (exponent == SMALL_DOUBLE_EXPONENT_BASE && fraction == 0))
            return null;
        return new Oop(((exponent - SMALL_DOUBLE_EXPONENT_BASE) << 56) | (fraction << 4) | (sign << 3) | TAG_SMALL_DOUBLE);
    }

    /** {@code GCI_BOOL_TO_OOP}. */
    public static Oop fromBoolean(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** {@code GCI_OOP_TO_BOOL}. */
    public boolean toBoolean() {
        if (equals(TRUE)) return true;
        if (equals(FALSE)) return false;
        throw notA("Boolean");
    }

    /** {@code GCI_CHR_TO_OOP_} — Character for a Unicode code point. */
    public static Oop fromCharacter(int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF))
            throw new IllegalArgumentException(String.format("0x%X is not a Unicode scalar value.", codePoint));
        return new Oop(((long) codePoint << SPECIAL_VALUE_SHIFT) | ASCII_NUL);
    }

    /** {@code GCI_OOP_TO_CHR} for the full Unicode range: the code point. */
    public int toCharacter() {
        if (!isCharacter()) throw notA("Character");
        long cp = value >>> SPECIAL_VALUE_SHIFT;
        if (cp > MAX_CODE_POINT || (cp >= 0xD800 && cp <= 0xDFFF))
            throw new ClassCastException(String.format("%s is a Character outside the valid Unicode scalar range (0x%X).", this, cp));
        return (int) cp;
    }

    private ClassCastException notA(String kind) {
        return new ClassCastException(this + " is not a " + kind + ".");
    }

    /**
     * Mixes all bits: OOPs of heap objects share their low (tag) bits, and with
     * {@code Long.hashCode} large hash maps of OOPs degenerate into tree bins (a load of 825,000
     * objects spent most of its time there).
     */
    @Override
    public int hashCode() {
        var h = value * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Oop other && other.value == value;
    }

    @Override
    public String toString() {
        return "Oop(0x" + Long.toHexString(value).toUpperCase() + ")";
    }
}
