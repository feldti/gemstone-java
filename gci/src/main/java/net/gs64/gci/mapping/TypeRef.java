package net.gs64.gci.mapping;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * A generic type for {@link GsObjectContext#load}, e.g. {@code new TypeRef<List<Account>>() {}}
 * (Java erases type arguments; the anonymous subclass keeps them).
 *
 * @param <T> the type
 */
public abstract class TypeRef<T> {
    private final Type type;

    protected TypeRef() {
        var superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType p))
            throw new IllegalStateException("TypeRef needs a type argument: new TypeRef<List<X>>() {}.");
        type = p.getActualTypeArguments()[0];
    }

    /** The type argument. */
    public Type type() {
        return type;
    }
}
