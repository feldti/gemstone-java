package net.gs64.gci.mapping;

import java.lang.reflect.Type;

/** Custom conversion of a field value (see {@link GsConverter}). */
public interface GsValueConverter {
    /**
     * Converts a GemStone value to the field type. {@code value} is already decoded: null,
     * Boolean, Long, BigInteger, Double, BigDecimal, CodePoint, String (also for Symbols), byte[],
     * date/time values, or the {@link net.gs64.gci.Oop} for anything else.
     */
    Object fromGemStone(Object value, Type targetType);

    /**
     * Converts a field value back for saving: a Java value that {@link net.gs64.gci.GciSession#fromValue}
     * understands (or an Oop). The default throws: a one-way converter makes saving a changed value fail.
     */
    default Object toGemStone(Object value, Type fieldType) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " cannot convert values back to GemStone (implement toGemStone).");
    }

    /** Whether {@link #toGemStone} is implemented. */
    default boolean canWrite() {
        return false;
    }
}
