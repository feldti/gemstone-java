package net.gs64.gci;

/**
 * OOPs of kernel classes from {@code $GEMSTONE/include/gcioop.ht} (3.7.5). These are fixed for a
 * GemStone version; classes not listed there (e.g. DateAndTime) are resolved by name.
 */
public final class KnownClasses {
    private KnownClasses() {
    }

    public static final Oop INTEGER = new Oop(70145);               // OOP_CLASS_INTEGER
    public static final Oop SMALL_INTEGER = new Oop(74241);         // OOP_CLASS_SMALL_INTEGER
    public static final Oop LARGE_INTEGER = new Oop(136193);        // OOP_CLASS_LargeInteger
    public static final Oop FLOAT = new Oop(135937);                // OOP_CLASS_Float
    public static final Oop SMALL_FLOAT = new Oop(136705);          // OOP_CLASS_SmallFloat
    public static final Oop SMALL_DOUBLE = new Oop(121345);         // OOP_CLASS_SMALL_DOUBLE
    public static final Oop SCALED_DECIMAL = new Oop(146433);       // OOP_CLASS_ScaledDecimal
    public static final Oop SMALL_SCALED_DECIMAL = new Oop(159233); // OOP_CLASS_SmallScaledDecimal
    public static final Oop BOOLEAN = new Oop(68097);               // OOP_CLASS_BOOLEAN
    public static final Oop CHARACTER = new Oop(68353);             // OOP_CLASS_CHARACTER
    public static final Oop UNDEFINED_OBJECT = new Oop(76289);      // OOP_CLASS_UNDEFINED_OBJECT
    public static final Oop CHARACTER_COLLECTION = new Oop(80385);  // OOP_CLASS_CHARACTER_COLLECTION
    public static final Oop STRING = new Oop(74753);                // OOP_CLASS_STRING
    public static final Oop SYMBOL = new Oop(110849);               // OOP_CLASS_SYMBOL
    public static final Oop DOUBLE_BYTE_STRING = new Oop(143873);   // OOP_CLASS_DoubleByteString
    public static final Oop DOUBLE_BYTE_SYMBOL = new Oop(144129);   // OOP_CLASS_DoubleByteSymbol
    public static final Oop QUAD_BYTE_SYMBOL = new Oop(144641);     // OOP_CLASS_QuadByteSymbol
    public static final Oop UTF8 = new Oop(154113);                 // OOP_CLASS_Utf8
    public static final Oop UNICODE7 = new Oop(154369);             // OOP_CLASS_Unicode7
    public static final Oop UNICODE16 = new Oop(154625);            // OOP_CLASS_Unicode16
    public static final Oop UNICODE32 = new Oop(154881);            // OOP_CLASS_Unicode32
    public static final Oop BYTE_ARRAY = new Oop(103425);           // OOP_CLASS_BYTE_ARRAY
    public static final Oop ARRAY = new Oop(66817);                 // OOP_CLASS_ARRAY
    public static final Oop SMALL_DATE_AND_TIME = new Oop(159489);  // OOP_CLASS_SmallDateAndTime
}
