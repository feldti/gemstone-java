package net.gs64.gci.mapping;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Metadata of collection types (lists, sets, maps, arrays) from the {@link Type} alone; shared by
 * the reflection provider and generated providers. Not meant to be used directly.
 */
public final class GsCollectionMetadata {
    private GsCollectionMetadata() {
    }

    /**
     * Metadata for a collection type, or null if {@code type} is none.
     *
     * @param factory creates an application's own collection class (a concrete class that is not
     *     ArrayList/HashSet/LinkedHashSet/HashMap/LinkedHashMap); answers null if it cannot
     */
    @SuppressWarnings("unchecked")
    public static GsTypeMetadata forType(Type type, Function<Class<?>, IntFunction<?>> factory) {
        if (type instanceof Class<?> c && c.isArray() && c != byte[].class)
            return GsTypeMetadata.array(type, c.getComponentType(), c.getComponentType());
        if (type instanceof GenericArrayType g) {
            var component = g.getGenericComponentType();
            return GsTypeMetadata.array(type, Types.raw(component), component);
        }
        var raw = Types.raw(type);
        if (Map.class.isAssignableFrom(raw)) {
            var key = arg(type, Map.class, 0);
            var value = arg(type, Map.class, 1);
            if (raw.isInterface() || raw == java.util.HashMap.class || raw == java.util.LinkedHashMap.class) return GsTypeMetadata.map(type, key, value);
            return GsTypeMetadata.map(type, key, value, (IntFunction<? extends Map<?, ?>>) own(raw, factory));
        }
        if (Set.class.isAssignableFrom(raw)) {
            var element = arg(type, Set.class, 0);
            if (raw.isInterface() || raw == HashSet.class || raw == LinkedHashSet.class) return GsTypeMetadata.set(type, element);
            return GsTypeMetadata.set(type, element, (IntFunction<? extends Set<?>>) own(raw, factory));
        }
        if (List.class.isAssignableFrom(raw) || raw == Collection.class || raw == Iterable.class) {
            var element = raw == Iterable.class ? arg(type, Iterable.class, 0) : arg(type, Collection.class, 0);
            if (raw.isInterface() || raw == ArrayList.class) return GsTypeMetadata.list(type, element);
            return GsTypeMetadata.list(type, element, (IntFunction<? extends List<?>>) own(raw, factory)); // an application's own list type
        }
        return null;
    }

    private static IntFunction<?> own(Class<?> raw, Function<Class<?>, IntFunction<?>> factory) {
        var f = factory.apply(raw);
        if (f != null) return f;
        return n -> {
            throw new GsMappingException(raw.getName() + " needs a parameterless constructor (accessible to the mapper).", List.of());
        };
    }

    /** A type argument, or Object for raw types. */
    private static Type arg(Type type, Class<?> target, int index) {
        var t = Types.typeArgument(type, target, index);
        return t == null ? Object.class : t;
    }
}
