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

final class GsPropertyMap {
    final GsPropertyMetadata metadata;
    final GsShape shape;
    /** Position in {@link GsTypeMap#properties} (index of the snapshot in an {@link EntityEntry}). */
    final int slot;
    /** 1-based named instVar index in the GemStone class. */
    int index;
    final String path;
    final boolean nilForbidden;

    GsPropertyMap(GsPropertyMetadata metadata, GsShape shape, int slot) {
        this.metadata = metadata;
        this.shape = shape;
        this.slot = slot;
        this.path = metadata.owner + "." + metadata.name;
        this.nilForbidden = Types.raw(metadata.type).isPrimitive();
    }

    String instVar() {
        return metadata.instVar;
    }

    GsValueConverter converter() {
        return metadata.converter;
    }

    GsCollectionKind collectionKind() {
        return metadata.collectionKind;
    }

    String keySelector() {
        return metadata.keySelector;
    }

    boolean readOnly() {
        return metadata.readOnly;
    }

    Object get(Object entity) {
        return metadata.get.apply(entity);
    }

    void set(Object entity, Object value) {
        metadata.set.accept(entity, value);
    }
}
