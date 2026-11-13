package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure tag-bit logic ported from the GCI_* macros; no stone needed. */
class OopTest {
    @ParameterizedTest
    @CsvSource({"0, 2", "1, 10", "7, 58", "-1, -6"})
    void smallIntegerEncoding(long value, long expectedOop) {
        var oop = Oop.fromSmallInteger(value);
        assertEquals(expectedOop, oop.value());
        assertTrue(oop.isSmallInteger());
        assertTrue(oop.isSpecial());
        assertEquals(value, oop.toSmallInteger());
    }

    @ParameterizedTest
    @ValueSource(longs = {Oop.SMALL_INTEGER_MIN, Oop.SMALL_INTEGER_MAX})
    void smallIntegerRangeLimitsRoundTrip(long value) {
        assertEquals(value, Oop.fromSmallInteger(value).toSmallInteger());
    }

    @ParameterizedTest
    @ValueSource(longs = {Oop.SMALL_INTEGER_MAX + 1, Oop.SMALL_INTEGER_MIN - 1, Long.MAX_VALUE})
    void outsideSmallIntegerRangeThrows(long value) {
        assertFalse(Oop.fitsSmallInteger(value));
        assertThrows(IllegalArgumentException.class, () -> Oop.fromSmallInteger(value));
    }

    @Test
    void booleans() {
        assertEquals(Oop.TRUE, Oop.fromBoolean(true));
        assertEquals(Oop.FALSE, Oop.fromBoolean(false));
        assertTrue(Oop.TRUE.isBoolean());
        assertTrue(Oop.FALSE.isBoolean());
        assertFalse(Oop.NIL.isBoolean());
        assertTrue(Oop.TRUE.toBoolean());
        assertFalse(Oop.FALSE.toBoolean());
        assertThrows(ClassCastException.class, Oop.NIL::toBoolean);
    }

    @ParameterizedTest
    @CsvSource({"0, 28", "97, 24860", "228, 58396", "128512, 32899100"})
    void characterEncoding(int codePoint, long expectedOop) {
        var oop = Oop.fromCharacter(codePoint);
        assertEquals(expectedOop, oop.value());
        assertTrue(oop.isCharacter());
        assertEquals(codePoint, oop.toCharacter());
    }

    @Test
    void kindPredicatesAreExclusive() {
        assertTrue(Oop.NIL.isNil());
        assertTrue(Oop.NIL.isSpecial());
        assertFalse(Oop.NIL.isSmallInteger());
        assertFalse(Oop.TRUE.isNil());
        assertFalse(Oop.ILLEGAL.isSpecial()); // POM tag bit
        assertFalse(new Oop(66817).isSpecial()); // a heap object (class Array)
        assertThrows(ClassCastException.class, Oop.NIL::toSmallInteger);
        assertThrows(ClassCastException.class, Oop.NIL::toCharacter);
    }

    @Test
    void smallDoublesRoundTripLocally() {
        for (var d : new double[] {0.0, -0.0, 1.0, -2.5, 0.1, 1e30, 6e-39}) {
            var oop = Oop.tryFromSmallDouble(d);
            assertTrue(oop != null && oop.isSmallDouble(), "SmallDouble for " + d);
            assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(oop.toSmallDouble()));
        }
        assertNull(Oop.tryFromSmallDouble(1e100));
        assertNull(Oop.tryFromSmallDouble(Double.NaN));
        assertNull(Oop.tryFromSmallDouble(Double.POSITIVE_INFINITY));
    }
}
