package net.gs64.gci.mapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import net.gs64.gci.Oop;

/**
 * Mapping metadata by reflection. Fields are read and written through method handles from
 * {@link MethodHandles#privateLookupIn}: in a named module, the package of the mapped classes
 * must be open to this library ({@code opens … to net.gs64.gci}).
 *
 * <p>Subclasses for polymorphic loading come from three sources: the permitted subclasses of a
 * sealed class or interface, {@link GsSubtypes}, and the classes passed to {@link #register}.
 */
public final class ReflectionMappingProvider implements GsMappingProvider {
    private final Set<Class<?>> registered = new CopyOnWriteArraySet<>();
    private final Map<Class<?>, GsTypeMetadata> classes = new HashMap<>();

    /** A provider that knows the given mapped classes up front (for polymorphic loading). */
    public ReflectionMappingProvider(Class<?>... mappedClasses) {
        register(mappedClasses);
    }

    /** Makes mapped classes known as subclasses of their mapped or unmapped supertypes. */
    public void register(Class<?>... mappedClasses) {
        for (var c : mappedClasses) {
            if (c.getAnnotation(GsClass.class) == null)
                throw new IllegalArgumentException(c.getName() + " has no @GsClass.");
            registered.add(c);
        }
    }

    @Override
    public GsTypeMetadata typeMetadata(Type type) {
        var collection = GsCollectionMetadata.forType(type, ReflectionMappingProvider::factory);
        if (collection != null) return collection;
        var raw = Types.raw(type);
        if (raw.getAnnotation(GsClass.class) != null && !raw.isInterface()) return classes.computeIfAbsent(raw, this::forClass);
        var subtypes = subtypesOf(raw);
        return subtypes.isEmpty() ? null : GsTypeMetadata.polymorphic(type, subtypes);
    }

    /** A collection class with a parameterless constructor (any access). */
    private static IntFunction<?> factory(Class<?> raw) {
        try {
            var ctor = MethodHandles.privateLookupIn(raw, MethodHandles.lookup())
                    .findConstructor(raw, MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class));
            return n -> {
                try {
                    return ctor.invokeExact();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException("Cannot create a " + raw.getName() + ".", t);
                }
            };
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    /** Mapped classes that implement or extend {@code type}, most derived first. */
    private List<Class<?>> subtypesOf(Class<?> type) {
        var found = new LinkedHashSet<Class<?>>();
        collect(type, found, new HashSet<>());
        for (var r : registered) if (r != type && type.isAssignableFrom(r)) found.add(r);
        var result = new ArrayList<Class<?>>();
        for (var c : found) if (c != type && c.getAnnotation(GsClass.class) != null && !c.isInterface()) result.add(c);
        result.sort(Comparator.comparingInt((Class<?> c) -> depth(c)).reversed());
        return result;
    }

    private static void collect(Class<?> type, Set<Class<?>> into, Set<Class<?>> seen) {
        if (!seen.add(type)) return;
        var declared = type.getAnnotation(GsSubtypes.class);
        var direct = new ArrayList<Class<?>>();
        if (declared != null) direct.addAll(List.of(declared.value()));
        if (type.isSealed()) direct.addAll(List.of(type.getPermittedSubclasses()));
        for (var d : direct) {
            if (type.isAssignableFrom(d)) into.add(d);
            collect(d, into, seen);
        }
    }

    private static int depth(Class<?> t) {
        var d = 0;
        for (var b = t.getSuperclass(); b != null; b = b.getSuperclass()) d++;
        return d;
    }

    private GsTypeMetadata forClass(Class<?> type) {
        var cls = type.getAnnotation(GsClass.class);
        var problems = new ArrayList<String>();
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            problems.add(type.getSimpleName() + ": not accessible to the mapper (open its package to net.gs64.gci): " + e.getMessage());
            return GsTypeMetadata.mappedClass(type, cls.value(), cls.dictionary(), null, List.of(), List.of(), null, problems);
        }

        Supplier<Object> create = null;
        if (!Modifier.isAbstract(type.getModifiers())) {
            try {
                var ctor = lookup.findConstructor(type, MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class));
                create = () -> {
                    try {
                        return ctor.invokeExact();
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                };
            } catch (NoSuchMethodException | IllegalAccessException e) {
                problems.add(type.getSimpleName() + ": needs a parameterless constructor.");
            }
        }

        BiConsumer<Object, Oop> setOop = null;
        var properties = new ArrayList<GsPropertyMetadata>();
        for (var f : fieldsOf(type)) {
            if (f.getAnnotation(GsIgnore.class) != null) continue;
            var where = type.getSimpleName() + "." + f.getName();
            var isOop = f.getAnnotation(GsOop.class) != null;
            var iv = f.getAnnotation(GsInstVar.class);
            if (!isOop && iv == null) continue;
            if (Modifier.isFinal(f.getModifiers())) {
                problems.add(where + ": a mapped field must not be final.");
                continue;
            }
            MethodHandle getter, setter;
            try {
                var fieldLookup = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup());
                getter = fieldLookup.unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
                setter = fieldLookup.unreflectSetter(f).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (IllegalAccessException e) {
                problems.add(where + ": not accessible to the mapper: " + e.getMessage());
                continue;
            }
            Function<Object, Object> get = o -> {
                try {
                    return getter.invokeExact(o);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            };
            BiConsumer<Object, Object> set = (o, v) -> {
                try {
                    setter.invokeExact(o, v);
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            };
            if (isOop) {
                if (f.getType() != Oop.class) problems.add(where + ": @GsOop requires type Oop.");
                else setOop = set::accept;
                continue;
            }
            GsValueConverter converter = null;
            var conv = f.getAnnotation(GsConverter.class);
            if (conv != null) {
                try {
                    converter = conv.value().getConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    problems.add(where + ": converter " + conv.value().getSimpleName() + " needs a public parameterless constructor.");
                }
            }
            var kind = f.getAnnotation(GsCollection.class);
            var key = f.getAnnotation(GsDictionaryKey.class);
            @SuppressWarnings("unchecked") var owner = (Class<Object>) f.getDeclaringClass();
            properties.add(GsPropertyMetadata.of(owner, f.getName(), f.getGenericType(), iv.value(), get, set,
                    kind == null ? null : kind.value(), f.getAnnotation(GsReadOnly.class) != null, converter,
                    key == null ? null : key.value()));
        }
        @SuppressWarnings("unchecked") var typed = (Class<Object>) type;
        return GsTypeMetadata.mappedClass(typed, cls.value(), cls.dictionary(), create, properties, subtypesOf(type), setOop, problems);
    }

    /** Instance fields including inherited ones, base class first; a field hidden by a subclass is skipped. */
    private static List<Field> fieldsOf(Class<?> type) {
        var chain = new ArrayList<Class<?>>();
        for (var t = type; t != null && t != Object.class; t = t.getSuperclass()) chain.add(0, t);
        var seen = new HashSet<String>();
        var result = new ArrayList<Field>();
        for (var i = chain.size() - 1; i >= 0; i--)
            for (var f : chain.get(i).getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic() && seen.add(f.getName())) result.add(f);
        result.sort(Comparator.comparingInt(f -> chain.indexOf(f.getDeclaringClass())));
        return result;
    }

    static boolean isParameterized(Type t) {
        return t instanceof ParameterizedType;
    }

    static List<Type> args(Type t) {
        return t instanceof ParameterizedType p ? Arrays.asList(p.getActualTypeArguments()) : List.of();
    }
}
