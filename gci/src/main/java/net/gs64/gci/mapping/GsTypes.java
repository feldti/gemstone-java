package net.gs64.gci.mapping;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;

/**
 * Generic types without reflection, for generated mapping metadata ({@code List<Order>} as
 * {@code GsTypes.parameterized(List.class, Order.class)}). Equal to the JDK's own representations
 * (the {@link ParameterizedType} and {@link GenericArrayType} contracts).
 */
public final class GsTypes {
    private GsTypes() {
    }

    /** {@code raw<args>}. */
    public static ParameterizedType parameterized(Class<?> raw, Type... args) {
        return new Parameterized(raw, args.clone(), raw.getDeclaringClass());
    }

    /** {@code component[]} for a generic component type (for a plain class use {@code X[].class}). */
    public static Type array(Type component) {
        if (component instanceof Class<?> c) return java.lang.reflect.Array.newInstance(c, 0).getClass();
        return new GenericArray(component);
    }

    /** The class a type erases to. */
    public static Class<?> raw(Type type) {
        return Types.raw(type);
    }

    private record Parameterized(Class<?> raw, Type[] args, Type owner) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType p && raw.equals(p.getRawType()) && Objects.equals(owner, p.getOwnerType())
                    && Arrays.equals(args, p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(args) ^ Objects.hashCode(owner) ^ raw.hashCode(); // as the JDK's ParameterizedTypeImpl
        }

        @Override
        public String toString() {
            var sb = new StringBuilder(raw.getTypeName()).append('<');
            for (var i = 0; i < args.length; i++) sb.append(i == 0 ? "" : ", ").append(args[i].getTypeName());
            return sb.append('>').toString();
        }
    }

    private record GenericArray(Type component) implements GenericArrayType {
        @Override
        public Type getGenericComponentType() {
            return component;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GenericArrayType g && component.equals(g.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return component.hashCode();
        }

        @Override
        public String toString() {
            return component.getTypeName() + "[]";
        }
    }
}
