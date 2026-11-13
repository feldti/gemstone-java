package net.gs64.gci.mapping;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import net.gs64.gci.Oop;

/** A tracked object: its OOP, mapping and field snapshots (an array indexed by {@link GsPropertyMap#slot}). */
final class EntityEntry {
    final Object entity;
    final Oop oop;
    final GsTypeMap map;
    private final PropertySnapshot[] snapshots;

    EntityEntry(Object entity, Oop oop, GsTypeMap map) {
        this.entity = entity;
        this.oop = oop;
        this.map = map;
        this.snapshots = new PropertySnapshot[map.properties.size()];
    }

    private boolean owns(GsPropertyMap p) {
        return p.slot < snapshots.length && map.properties.get(p.slot) == p;
    }

    /** The snapshot of {@code p}, or null. */
    PropertySnapshot snapshot(GsPropertyMap p) {
        return owns(p) ? snapshots[p.slot] : null;
    }

    void setSnapshot(GsPropertyMap p, PropertySnapshot snapshot) {
        if (owns(p)) snapshots[p.slot] = snapshot;
    }

    List<PropertySnapshot> snapshots() {
        return Arrays.stream(snapshots).filter(Objects::nonNull).toList();
    }
}
