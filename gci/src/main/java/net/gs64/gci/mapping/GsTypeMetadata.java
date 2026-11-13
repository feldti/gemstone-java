package net.gs64.gci.mapping;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import net.gs64.gci.Oop;

/**
 * Mapping metadata of one type, created by {@link GsMappingProvider} implementations. Not meant
 * to be used directly.
 */
public final class GsTypeMetadata {
    final Type type;
    final ShapeKind kind;
    final Type[] arguments;
    /** Creates an empty collection (argument: the expected size; the length for arrays). */
    final IntFunction<Object> newCollection;
    final String className;
    final String dictionary;
    final Supplier<Object> create;
    final List<GsPropertyMetadata> properties;
    final List<Class<?>> subtypes;
    final BiConsumer<Object, Oop> setOop;
    final List<String> problems;

    private GsTypeMetadata(Type type, ShapeKind kind, Type[] arguments, IntFunction<Object> newCollection, String className,
                           String dictionary, Supplier<Object> create, List<GsPropertyMetadata> properties, List<Class<?>> subtypes,
                           BiConsumer<Object, Oop> setOop, List<String> problems) {
        this.type = type;
        this.kind = kind;
        this.arguments = arguments;
        this.newCollection = newCollection;
        this.className = className;
        this.dictionary = dictionary;
        this.create = create;
        this.properties = List.copyOf(properties);
        this.subtypes = List.copyOf(subtypes);
        this.setOop = setOop;
        this.problems = List.copyOf(problems);
    }

    private static GsTypeMetadata collection(Type type, ShapeKind kind, IntFunction<Object> factory, Type... args) {
        return new GsTypeMetadata(type, kind, args, factory, null, null, null, List.of(), List.of(), null, List.of());
    }

    /** A list type (List, Collection, Iterable, ArrayList or an own subclass), filled through {@code factory}. */
    public static GsTypeMetadata list(Type declared, Type element, IntFunction<? extends List<?>> factory) {
        return collection(declared, ShapeKind.LIST, factory::apply, element);
    }

    /** A list type filled as an {@link ArrayList}. */
    public static GsTypeMetadata list(Type declared, Type element) {
        return list(declared, element, ArrayList::new);
    }

    /** An array {@code T[]}. */
    public static GsTypeMetadata array(Type declared, Class<?> componentClass, Type element) {
        return collection(declared, ShapeKind.ARRAY, n -> java.lang.reflect.Array.newInstance(componentClass, n), element);
    }

    /** A set type, filled as a {@link LinkedHashSet} (load order kept). */
    public static GsTypeMetadata set(Type declared, Type element) {
        return collection(declared, ShapeKind.SET, n -> new LinkedHashSet<Object>(), element);
    }

    /** A set type filled through {@code factory}. */
    public static GsTypeMetadata set(Type declared, Type element, IntFunction<? extends Set<?>> factory) {
        return collection(declared, ShapeKind.SET, factory::apply, element);
    }

    /** A map type, filled as a {@link LinkedHashMap}. */
    public static GsTypeMetadata map(Type declared, Type key, Type value) {
        return collection(declared, ShapeKind.DICTIONARY, n -> new LinkedHashMap<Object, Object>(), key, value);
    }

    /** A map type filled through {@code factory}. */
    public static GsTypeMetadata map(Type declared, Type key, Type value, IntFunction<? extends Map<?, ?>> factory) {
        return collection(declared, ShapeKind.DICTIONARY, factory::apply, key, value);
    }

    /**
     * A class with {@link GsClass}.
     *
     * @param create parameterless constructor; null for abstract classes
     * @param properties mapped fields, including inherited ones
     * @param subtypes Java subclasses with their own {@link GsClass}, most derived first
     * @param setOop setter of the {@link GsOop} field, or null
     * @param problems mapping errors found while creating the metadata, reported on first use
     */
    @SuppressWarnings("unchecked")
    public static <T> GsTypeMetadata mappedClass(Class<T> type, String className, String dictionary, Supplier<T> create,
                                                 List<GsPropertyMetadata> properties, List<Class<?>> subtypes,
                                                 BiConsumer<T, Oop> setOop, List<String> problems) {
        return new GsTypeMetadata(type, ShapeKind.MAPPED, new Type[0], null, className,
                dictionary == null || dictionary.isEmpty() ? null : dictionary, create == null ? null : create::get, properties, subtypes,
                setOop == null ? null : (o, oop) -> setOop.accept((T) o, oop), problems);
    }

    /**
     * An interface, or a class without {@link GsClass}, used as a field or element type: each
     * object is created as the mapped class of {@code subtypes} that matches its GemStone class.
     */
    public static GsTypeMetadata polymorphic(Type declared, List<Class<?>> subtypes) {
        return new GsTypeMetadata(declared, ShapeKind.MAPPED, new Type[0], null, null, null, null, List.of(), subtypes, null, List.of());
    }
}
