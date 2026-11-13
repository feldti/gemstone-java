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

/** Helpers for {@link Type}. */
final class Types {
    private Types() {
    }

    static Class<?> raw(Type t) {
        return switch (t) {
            case Class<?> c -> c;
            case ParameterizedType p -> raw(p.getRawType());
            case GenericArrayType g -> java.lang.reflect.Array.newInstance(raw(g.getGenericComponentType()), 0).getClass();
            case TypeVariable<?> v -> v.getBounds().length == 0 ? Object.class : raw(v.getBounds()[0]);
            case WildcardType w -> w.getUpperBounds().length == 0 ? Object.class : raw(w.getUpperBounds()[0]);
            default -> Object.class;
        };
    }

    static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static final Set<Class<?>> SCALARS = Set.of(Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, BigInteger.class, BigDecimal.class, Character.class, CodePoint.class, String.class,
            byte[].class, OffsetDateTime.class, ZonedDateTime.class, Instant.class, LocalDateTime.class, LocalDate.class, LocalTime.class);

    static boolean isScalar(Class<?> c) {
        return SCALARS.contains(boxed(c)) || c.isEnum();
    }

    /**
     * The type argument {@code index} of {@code target} (a generic class or interface) as seen
     * from {@code type}, resolving type variables along the way; null if {@code type} does not
     * extend {@code target}.
     */
    static Type typeArgument(Type type, Class<?> target, int index) {
        return resolve(type, target, new HashMap<>(), index);
    }

    private static Type resolve(Type type, Class<?> target, Map<TypeVariable<?>, Type> bindings, int index) {
        var raw = raw(type);
        var local = new HashMap<>(bindings);
        if (type instanceof ParameterizedType p) {
            var vars = raw.getTypeParameters();
            var args = p.getActualTypeArguments();
            for (var i = 0; i < vars.length && i < args.length; i++) local.put(vars[i], substitute(args[i], bindings));
        }
        if (raw == target) {
            if (!(type instanceof ParameterizedType p)) return Object.class;
            return substitute(p.getActualTypeArguments()[index], bindings);
        }
        var supers = new ArrayList<Type>();
        if (raw.getGenericSuperclass() != null) supers.add(raw.getGenericSuperclass());
        supers.addAll(List.of(raw.getGenericInterfaces()));
        for (var s : supers) {
            var r = resolve(substitute(s, local), target, local, index);
            if (r != null) return r;
        }
        return null;
    }

    private static Type substitute(Type t, Map<TypeVariable<?>, Type> bindings) {
        if (t instanceof TypeVariable<?> v && bindings.containsKey(v)) return bindings.get(v);
        return t;
    }
}
