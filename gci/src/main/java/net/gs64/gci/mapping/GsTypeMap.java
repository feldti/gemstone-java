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

/** A Java class with {@link GsClass} (or a polymorphic base) and its resolved GemStone class. */
final class GsTypeMap {
    final Class<?> type;
    final String className;
    final String dictionary;
    final Supplier<Object> create;
    final List<GsPropertyMap> properties = new ArrayList<>();
    final BiConsumer<Object, Oop> setOop;
    /** Java subclasses with their own {@link GsClass}, most derived first. */
    final List<GsTypeMap> subtypes = new ArrayList<>();
    final boolean polymorphic;
    Oop classOop = Oop.ILLEGAL;

    GsTypeMap(Class<?> type, String className, String dictionary, Supplier<Object> create, BiConsumer<Object, Oop> setOop, boolean polymorphic) {
        this.type = type;
        this.className = className;
        this.dictionary = dictionary;
        this.create = create;
        this.setOop = setOop;
        this.polymorphic = polymorphic;
    }

    @Override
    public String toString() {
        return polymorphic ? type.getSimpleName() + " ↔ (" + String.join(" | ", subtypes.stream().map(s -> s.className).toList()) + ")"
                : type.getSimpleName() + " ↔ " + className;
    }
}
