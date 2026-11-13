package net.gs64.gci.mapping;

import net.gs64.gci.Oop;

/**
 * Snapshot of one mapped field as loaded or last saved. A value that the OOP itself encodes (an
 * Oop field, a long held as SmallInteger, a double held as SmallDouble) is not kept as a box of
 * its own: a marker says how to read it from the OOP. Small integers and booleans share boxes.
 */
final class PropertySnapshot {
    private static final Object OOP_IN_OOP = new Object(), LONG_IN_OOP = new Object(), DOUBLE_IN_OOP = new Object();

    final Oop oop;
    private final Object value;
    final CollectionSnapshot collection;
    /** The value was a Symbol: a changed string/enum is written as a Symbol again. */
    final boolean isSymbol;
    /** For enums written as Symbols: the Symbol starts lower case (#active). */
    final boolean lowerFirst;

    PropertySnapshot(Oop oop, Object value, CollectionSnapshot collection, boolean isSymbol, boolean lowerFirst) {
        this.oop = oop;
        this.collection = collection;
        this.isSymbol = isSymbol;
        this.lowerFirst = lowerFirst;
        if (value instanceof Oop o && o.equals(oop)) this.value = OOP_IN_OOP;
        else if (value instanceof Long l && oop.isSmallInteger() && oop.toSmallInteger() == l) this.value = LONG_IN_OOP;
        else if (value instanceof Double d && oop.isSmallDouble()
                && Double.doubleToRawLongBits(oop.toSmallDouble()) == Double.doubleToRawLongBits(d)) this.value = DOUBLE_IN_OOP;
        else this.value = BoxCache.intern(value);
    }

    PropertySnapshot(Oop oop) {
        this(oop, null, null, false, true);
    }

    /** The value as loaded or saved. */
    Object value() {
        if (value == OOP_IN_OOP) return oop;
        if (value == LONG_IN_OOP) return oop.toSmallInteger();
        if (value == DOUBLE_IN_OOP) return oop.toSmallDouble();
        return value;
    }

    /** Whether {@code current} equals {@link #value()}, without boxing it again. */
    boolean matches(Object current) {
        if (value == OOP_IN_OOP) return current instanceof Oop o && o.equals(oop);
        if (value == LONG_IN_OOP) return current instanceof Long l && l == oop.toSmallInteger();
        if (value == DOUBLE_IN_OOP) return current instanceof Double d && d.equals(oop.toSmallDouble());
        return GsObjectContext.valueEquals(current, value);
    }
}
