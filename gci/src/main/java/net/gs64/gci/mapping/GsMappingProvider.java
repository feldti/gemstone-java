package net.gs64.gci.mapping;

import java.lang.reflect.Type;

/**
 * Supplies the mapping metadata of Java types to a {@link GsObjectContext}: how to create
 * objects and collections and how to read and write mapped fields. The default is
 * {@link ReflectionMappingProvider}; an annotation processor can generate one without reflection.
 */
public interface GsMappingProvider {
    /**
     * Metadata for a {@link GsClass} type or a collection type, or null if the type is unknown to
     * this provider. Scalars, {@link net.gs64.gci.Oop} and {@link Object} need no metadata.
     */
    GsTypeMetadata typeMetadata(Type type);
}
