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

/** Builds shapes and type maps from the metadata of a {@link GsMappingProvider}. */
final class GsShapeFactory {
    private final GsMappingProvider provider;
    private final Map<Type, GsShape> shapes = new HashMap<>();
    /** Type maps created by {@link #shapeOf} that still need their server side resolved. */
    final List<GsTypeMap> newMaps = new ArrayList<>();

    GsShapeFactory(GsMappingProvider provider) {
        this.provider = provider;
    }

    GsShape shapeOf(Type type, List<String> errors, String where) {
        var known = shapes.get(type);
        if (known != null) return known;
        var raw = Types.raw(type);
        if (raw == Oop.class) return add(new GsShape(type, ShapeKind.OOP, null, false, null));
        if (raw == Object.class) return add(new GsShape(type, ShapeKind.OBJECT, null, false, null));
        if (Types.isScalar(raw)) return add(new GsShape(type, ShapeKind.SCALAR, Types.boxed(raw), raw.isPrimitive(), null));

        var meta = provider.typeMetadata(type);
        if (meta != null) {
            switch (meta.kind) {
                case LIST, ARRAY, SET -> {
                    var shape = add(new GsShape(type, meta.kind, null, false, meta.newCollection));
                    shape.element = shapeOf(meta.arguments[0], errors, where);
                    if (meta.kind == ShapeKind.ARRAY && Types.raw(meta.arguments[0]).isPrimitive())
                        errors.add(where + ": arrays of primitives (other than byte[]) are not supported; use a List.");
                    return shape;
                }
                case DICTIONARY -> {
                    var shape = add(new GsShape(type, ShapeKind.DICTIONARY, null, false, meta.newCollection));
                    shape.key = shapeOf(meta.arguments[0], errors, where);
                    shape.value = shapeOf(meta.arguments[1], errors, where);
                    return shape;
                }
                case MAPPED -> {
                    var shape = add(new GsShape(type, ShapeKind.MAPPED, null, false, null));
                    buildMap(shape, meta, errors); // registered first: fields may refer back to it
                    return shape;
                }
                default -> {
                }
            }
        }
        errors.add(where + ": type " + type.getTypeName() + " is not supported (no @GsClass and not a scalar, Oop or collection type"
                + (provider instanceof ReflectionMappingProvider ? "" : ", or unknown to " + provider.getClass().getSimpleName()) + ").");
        return add(new GsShape(type, ShapeKind.OBJECT, null, false, null));
    }

    private GsShape add(GsShape shape) {
        shapes.put(shape.type, shape);
        return shape;
    }

    private void buildMap(GsShape shape, GsTypeMetadata meta, List<String> errors) {
        var polymorphic = meta.className == null;
        var raw = Types.raw(meta.type);
        var map = new GsTypeMap(raw, polymorphic ? raw.getSimpleName() : meta.className, meta.dictionary, meta.create, meta.setOop, polymorphic);
        shape.map = map;
        newMaps.add(map);
        errors.addAll(meta.problems);
        for (var p : meta.properties) {
            var where = p.owner + "." + p.name;
            var propShape = p.converter == null ? shapeOf(p.type, errors, where) : new GsShape(p.type, ShapeKind.OBJECT, null, false, null);
            var kind = p.collectionKind;
            if (kind != null && !propShape.isCollection())
                errors.add(where + ": @GsCollection on a field that is not a collection type.");
            if (kind == GsCollectionKind.DICTIONARY && propShape.isCollection() && propShape.kind != ShapeKind.DICTIONARY)
                errors.add(where + ": @GsCollection(DICTIONARY) needs a Map field type.");
            if (kind != null && kind != GsCollectionKind.DICTIONARY && propShape.kind == ShapeKind.DICTIONARY)
                errors.add(where + ": a Map field type needs @GsCollection(DICTIONARY) or none.");
            if (p.keySelector != null) {
                var sel = p.keySelector;
                if (!propShape.isCollection() || propShape.kind == ShapeKind.DICTIONARY || (kind != null && kind != GsCollectionKind.DICTIONARY))
                    errors.add(where + ": @GsDictionaryKey belongs on a list or array field (the values of a dictionary).");
                if (!sel.matches("[A-Za-z_][A-Za-z0-9_]*"))
                    errors.add(where + ": @GsDictionaryKey(\"" + sel + "\") is not a unary selector.");
            }
            map.properties.add(new GsPropertyMap(p, propShape, map.properties.size()));
        }
        for (var sub : meta.subtypes) {
            var subMap = shapeOf(sub, errors, sub.getSimpleName()).map;
            if (subMap != null && !subMap.polymorphic) map.subtypes.add(subMap);
        }
        if (polymorphic && map.subtypes.isEmpty())
            errors.add(raw.getSimpleName() + ": no @GsClass class implements or extends it (sealed, @GsSubtypes or registered classes).");
    }

}
