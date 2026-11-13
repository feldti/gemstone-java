package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GciValueIntegrationTest {
    private GciSession s;

    @BeforeAll
    void login() {
        s = StoneEnv.login();
    }

    @AfterAll
    void logout() {
        if (s != null) s.close();
    }

    @StoneTest
    void pureEncodingsMatchTheServer() {
        assertEquals(s.execute("42"), Oop.fromSmallInteger(42));
        assertEquals(s.execute("-42"), Oop.fromSmallInteger(-42));
        assertEquals(s.execute("SmallInteger maxVal"), Oop.fromSmallInteger(Oop.SMALL_INTEGER_MAX));
        assertEquals(s.execute("SmallInteger minVal"), Oop.fromSmallInteger(Oop.SMALL_INTEGER_MIN));
        assertEquals(s.execute("$a"), Oop.fromCharacter('a'));
        assertEquals(s.execute("Character codePoint: 16r1F600"), Oop.fromCharacter(0x1F600));
        assertTrue(s.execute("DateAndTime now").isSmallDateAndTime());
        assertTrue(s.execute("1.5").isSmallDouble());
        assertTrue(s.execute("Date today").isSmallDate());
        assertTrue(s.execute("Time now").isSmallTime());
        assertTrue(s.execute("1/3").isSmallFraction());
        assertTrue(s.execute("1.5s1").isSmallScaledDecimal());
        assertFalse(s.execute("Date today").isSmallTime());
        String[] literals = {"0.0", "1.5", "-2.25", "1.0e30", "1.0e-30"};
        double[] values = {0.0, 1.5, -2.25, 1e30, 1e-30};
        for (var i = 0; i < values.length; i++) assertEquals(s.execute(literals[i]), Oop.tryFromSmallDouble(values[i]));
    }

    static Stream<Arguments> scalarCases() {
        return Stream.of(
                Arguments.of("nil", null),
                Arguments.of("true", true),
                Arguments.of("false", false),
                Arguments.of("3 + 4", 7L),
                Arguments.of("-5", -5L),
                Arguments.of("2 raisedTo: 62", 1L << 62), // LargeInteger within int64
                Arguments.of("1.5", 1.5),                 // SmallDouble
                Arguments.of("1.0e300", 1e300),           // Float
                Arguments.of("12.34s2", new BigDecimal("12.34")),
                Arguments.of("-0.001s3", new BigDecimal("-0.001")),
                Arguments.of("(1/3) asScaledDecimal: 5", new BigDecimal("0.33333")),
                Arguments.of("$ä", new CodePoint('ä')),
                Arguments.of("'abc'", "abc"),
                Arguments.of("#foo:bar:", "foo:bar:"),
                Arguments.of("'Grüße 日本'", "Grüße 日本"),
                Arguments.of("'x😀y'", "x😀y"),
                Arguments.of("'abc' asDoubleByteString", "abc"),
                Arguments.of("'Grüße' encodeAsUTF8", "Grüße"),
                Arguments.of("Date newDay: 15 monthNumber: 7 year: 2026", LocalDate.of(2026, 7, 15)),
                Arguments.of("Time fromSeconds: 3661", LocalTime.of(1, 1, 1)));
    }

    @ParameterizedTest
    @MethodSource("scalarCases")
    void evaluateMapsToJavaValues(String source, Object expected) {
        assertEquals(expected, s.evaluate(source));
    }

    @StoneTest
    void largeIntegersBeyondLongBecomeBigInteger() {
        assertEquals(BigInteger.TWO.pow(70), s.evaluate("2 raisedTo: 70"));
        assertEquals(BigInteger.TWO.pow(70).negate(), s.evaluate("(2 raisedTo: 70) negated"));
        assertThrows(ClassCastException.class, () -> s.toLong(s.execute("2 raisedTo: 70")));
    }

    @StoneTest
    void largeStringsAreFetchedCompletely() {
        // > 64 KiB fetch chunk, both 8-bit and UTF-8-encoded paths.
        assertEquals("a".repeat(200_000), s.evaluate("String new: 200000 withAll: $a"));
        assertEquals("ü".repeat(100_000), s.evaluate("String new: 100000 withAll: $ü"));
        assertEquals("😀".repeat(50_000),
                s.evaluate("(Unicode32 new: 50000) atAllPut: (Character codePoint: 16r1F600); yourself", String.class));
    }

    @StoneTest
    void byteArrayBecomesBytesAndUnmappedObjectsStayOops() {
        assertArrayEquals(new byte[] {1, 2, (byte) 255}, (byte[]) s.evaluate("#[1 2 255]"));
        var array = assertInstanceOf(Oop.class, s.evaluate("Array with: 1 with: 2"));
        try {
            assertEquals(KnownClasses.ARRAY, s.fetchClass(array));
        } finally {
            s.release(array);
        }
        assertInstanceOf(Oop.class, s.evaluate("1/3")); // SmallFraction: not mapped
    }

    static Stream<Object> roundTripValues() {
        return Stream.of(true, 0L, 42L, -42L, Long.MAX_VALUE, Long.MIN_VALUE, BigInteger.TEN.pow(30), BigInteger.TEN.pow(30).negate(),
                0.0, -2.5, 1e300, Double.MAX_VALUE, new BigDecimal("12.345"), new BigDecimal("-0.5"),
                new BigDecimal("123456789012345678901234.5678"), new BigDecimal("0.000000000000000000000000000000001"),
                new CodePoint('x'), new CodePoint(0x1F600), "", "plain", "Grüße 日本 😀",
                LocalDate.of(2026, 7, 15), LocalDate.of(1, 1, 1), LocalDate.of(1900, 12, 31), LocalDate.of(9999, 12, 31),
                LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59, 999_999_000), LocalTime.of(12, 30, 15, 123_000_000));
    }

    @ParameterizedTest
    @MethodSource("roundTripValues")
    void fromValueToValueRoundTrips(Object value) {
        var oop = s.fromValue(value);
        try {
            assertEquals(value, s.toValue(oop));
        } finally {
            s.release(oop);
        }
    }

    @StoneTest
    void smallIntegerTypesAndBytesRoundTrip() {
        assertEquals(42L, s.toValue(s.fromValue(42)));
        assertEquals(-7L, s.toValue(s.fromValue((short) -7)));
        for (var bytes : new byte[][] {{0, 1, (byte) 255}, {}}) {
            var oop = s.fromValue(bytes);
            try {
                assertArrayEquals(bytes, (byte[]) s.toValue(oop));
            } finally {
                s.release(oop);
            }
        }
    }

    @StoneTest
    void dateAndTimeUsesUnixEpochAndKeepsOffsetAndMicroseconds() {
        // Created in Java → interpreted correctly by the server.
        var dt = s.newDateAndTime(OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC));
        try {
            assertEquals(0, new BigDecimal("1767268800").compareTo(new BigDecimal(s.send(dt, "asPosixSeconds").toString())));
        } finally {
            s.release(dt);
        }
        // Created on the server → read correctly in Java.
        assertEquals(OffsetDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneOffset.ofHours(2)),
                s.evaluate("DateAndTime year: 2026 month: 7 day: 1 hour: 12 minute: 0 second: 0 offset: (Duration seconds: 7200)"));
        // Round trip, microsecond precision: sub-microsecond nanos are truncated.
        var value = OffsetDateTime.of(2026, 7, 1, 14, 30, 15, 123_456_789, ZoneOffset.ofHours(2));
        var oop = s.newDateAndTime(value);
        try {
            var back = s.toDateTime(oop);
            assertEquals(value.withNano(123_456_000), back);
            assertEquals(value.getOffset(), back.getOffset());
        } finally {
            s.release(oop);
        }
        // Offsets that are not whole minutes survive (historic local mean time).
        var lmt = OffsetDateTime.of(1850, 1, 1, 0, 0, 0, 0, ZoneOffset.ofTotalSeconds(3208));
        var o2 = s.newDateAndTime(lmt);
        try {
            assertEquals(lmt, s.toDateTime(o2));
        } finally {
            s.release(o2);
        }
    }

    @StoneTest
    void sendConvertsArgumentsAndResult() {
        var str = s.newString("Grüße");
        try {
            assertEquals(5L, s.send(str, "size"));
            assertEquals("Grüße, Welt", s.send(str, ",", ", Welt"));
            assertEquals(new CodePoint('ü'), s.send(str, "at:", 3));
            assertEquals(7, s.send(Integer.class, Oop.fromSmallInteger(3), "+", 4));
            assertEquals(new BigDecimal("1.5"), s.send(BigDecimal.class, s.execute("1.0s1"), "+", new BigDecimal("0.5")));
        } finally {
            s.release(str);
        }
    }

    @StoneTest
    void evaluateTypedConverts() {
        assertEquals(7, s.evaluate("3 + 4", Integer.class));
        assertEquals(7, (int) s.evaluate("3 + 4", int.class));
        assertEquals("abc", s.evaluate("'abc'", String.class));
        assertNull(s.evaluate("nil", String.class));
        assertNull(s.evaluate("nil", Integer.class));
        assertThrows(ClassCastException.class, () -> s.evaluate("nil", int.class));
        assertThrows(ClassCastException.class, () -> s.evaluate("'abc'", Integer.class));
        assertThrows(ArithmeticException.class, () -> s.evaluate("2 raisedTo: 40", Integer.class));
    }

    @StoneTest
    void symbolsAreCanonical() {
        assertEquals(s.execute("#foo"), s.newSymbol("foo"));
        assertEquals(s.execute("'grüß' asSymbol"), s.newSymbol("grüß"));
        assertEquals("grüß", s.toStringValue(s.newSymbol("grüß")));
    }

    @StoneTest
    void newStringPicksSmallestClass() {
        assertEquals(KnownClasses.STRING, s.fetchClass(s.newString("abc")));
        assertEquals(KnownClasses.UNICODE16, s.fetchClass(s.newString("日本")));
        assertEquals(KnownClasses.UNICODE32, s.fetchClass(s.newString("😀")));
    }

    @StoneTest
    void conversionErrorsAreTyped() {
        assertThrows(ClassCastException.class, () -> s.toDouble(s.execute("'abc'")));
        assertThrows(ClassCastException.class, () -> s.toLong(s.execute("'abc'")));
        assertThrows(GciException.class, () -> s.toStringValue(Oop.fromSmallInteger(1)));
        assertThrows(IllegalArgumentException.class, () -> s.fromValue(new Object()));
        assertTrue(s.isLoggedIn());
    }

    @StoneTest
    void toValuesMatchesToValueInOneBatch() {
        var array = s.execute("""
                { nil. true. 42. 1.5. $a. 2 raisedTo: 100. (2 raisedTo: 100) negated. 1234.56s2. -0.01s2.
                  99999999999999999999.99s2. 'abc'. #sym. 'Köln' asUnicodeString. 'x' encodeAsUTF8.
                  1.0e300. 1.5e0 asSmallFloat. #[1 2 3].
                  DateAndTime year: 2024 month: 2 day: 29 hour: 12 minute: 30 second: (45123456/1000000) offset: (Duration seconds: 3600).
                  Date newDay: 15 monthNumber: 3 year: 2021. Time fromSeconds: 3661. 3/4. Object new }
                """);
        try {
            var elements = s.fetchVaryingOops(array);
            var before = s.callCount();
            var batch = s.toValues(Arrays.asList(elements));
            var batchCalls = s.callCount() - before;
            assertEquals(elements.length, batch.length);
            for (var i = 0; i < elements.length; i++) {
                var single = s.toValue(elements[i]);
                if (single instanceof byte[] bytes) assertArrayEquals(bytes, (byte[]) batch[i]);
                else assertEquals(single, batch[i], "element " + i);
            }
            assertInstanceOf(Oop.class, batch[batch.length - 1]); // Object new: no Java counterpart
            assertInstanceOf(Oop.class, batch[batch.length - 2]); // Fraction
            assertTrue(batchCalls >= 1 && batchCalls <= 10, "calls " + batchCalls);
        } finally {
            s.release(array);
        }
    }
}
