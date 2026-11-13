package net.gs64.gci.mapping;

import java.lang.reflect.Type;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** A mapped field, see {@link GsTypeMetadata}. Not meant to be used directly. */
public final class GsPropertyMetadata {
    final String owner;
    final String name;
    final Type type;
    final String instVar;
    final Function<Object, Object> get;
    final BiConsumer<Object, Object> set;
    final GsCollectionKind collectionKind;
    final boolean readOnly;
    final GsValueConverter converter;
    final String keySelector;

    private GsPropertyMetadata(String owner, String name, Type type, String instVar, Function<Object, Object> get,
                               BiConsumer<Object, Object> set, GsCollectionKind collectionKind, boolean readOnly,
                               GsValueConverter converter, String keySelector) {
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.instVar = instVar;
        this.get = get;
        this.set = set;
        this.collectionKind = collectionKind;
        this.readOnly = readOnly;
        this.converter = converter;
        this.keySelector = keySelector;
    }

    static GsPropertyMetadata named(String owner, String name, Type type, String instVar, Function<Object, Object> get,
                                    BiConsumer<Object, Object> set, GsCollectionKind collectionKind, boolean readOnly,
                                    GsValueConverter converter, String keySelector) {
        return new GsPropertyMetadata(owner, name, type, instVar, get, set, collectionKind, readOnly, converter, keySelector);
    }

    /**
     * A field of {@code owner} (the declaring class) mapped to {@code instVar}.
     *
     * @param collectionKind from {@link GsCollection}, or null
     * @param converter from {@link GsConverter}, or null
     * @param keySelector from {@link GsDictionaryKey}, or null
     */
    @SuppressWarnings("unchecked")
    public static <O> GsPropertyMetadata of(Class<O> owner, String name, Type type, String instVar, Function<O, Object> get,
                                            BiConsumer<O, Object> set, GsCollectionKind collectionKind, boolean readOnly,
                                            GsValueConverter converter, String keySelector) {
        return new GsPropertyMetadata(owner.getSimpleName(), name, type, instVar, o -> get.apply((O) o), (o, v) -> set.accept((O) o, v),
                collectionKind, readOnly, converter, keySelector);
    }
}
