package net.gs64.gci.mapping;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import net.gs64.gci.CodePoint;
import net.gs64.gci.Oop;

/** How a Java type is filled from a GemStone value (cached per type). */
final class GsShape {
    final Type type;
    final ShapeKind kind;
    /** For scalars: the boxed raw class. */
    final Class<?> scalarClass;
    /** A primitive type: nil cannot be assigned. */
    final boolean primitive;
    GsShape element, key, value;
    GsTypeMap map;
    final IntFunction<Object> newCollection;

    GsShape(Type type, ShapeKind kind, Class<?> scalarClass, boolean primitive, IntFunction<Object> newCollection) {
        this.type = type;
        this.kind = kind;
        this.scalarClass = scalarClass;
        this.primitive = primitive;
        this.newCollection = newCollection;
    }

    boolean isCollection() {
        return kind == ShapeKind.LIST || kind == ShapeKind.ARRAY || kind == ShapeKind.SET || kind == ShapeKind.DICTIONARY;
    }

    @Override
    public String toString() {
        return type.getTypeName();
    }
}
