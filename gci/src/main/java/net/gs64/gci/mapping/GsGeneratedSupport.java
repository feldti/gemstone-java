package net.gs64.gci.mapping;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Access the generated mapping code cannot write as plain Java (private fields and constructors,
 * members of classes in other packages). Called by code the annotation processor generates; not
 * meant to be used directly.
 */
public final class GsGeneratedSupport {
    private GsGeneratedSupport() {
    }

    /** A class by its binary name, loaded with the loader of {@code anchor} (for classes the generated code cannot name). */
    public static Class<?> classNamed(String binaryName, Class<?> anchor) {
        try {
            return Class.forName(binaryName, false, anchor.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A VarHandle for a field of any access. */
    public static VarHandle field(Class<?> owner, String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Field " + owner.getName() + "." + name + " is not accessible to the mapper (open its package to net.gs64.gci).", e);
        }
    }

    /** The parameterless constructor (any access) as a supplier. */
    public static Supplier<Object> constructor(Class<?> type) {
        try {
            var ctor = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findConstructor(type, MethodType.methodType(void.class)).asType(MethodType.methodType(Object.class));
            return () -> {
                try {
                    return ctor.invokeExact();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            };
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(type.getName() + " has no parameterless constructor accessible to the mapper.", e);
        }
    }

    /** A mapped field (the owner given by name, for messages). */
    public static GsPropertyMetadata property(String owner, String name, Type type, String instVar, Function<Object, Object> get,
                                              BiConsumer<Object, Object> set, GsCollectionKind collectionKind, boolean readOnly,
                                              GsValueConverter converter, String keySelector) {
        return GsPropertyMetadata.named(owner, name, type, instVar, get, set, collectionKind, readOnly, converter, keySelector);
    }
}
