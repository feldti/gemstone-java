package net.gs64.gci.mapping;

import java.util.List;

import net.gs64.gci.Oop;

/** Snapshot of a collection-valued field: the GemStone collection and its contents. */
final class CollectionSnapshot {
    final Oop oop;
    List<SnapElement> elements;
    /** A list whose GemStone collection is a dictionary (the elements are its values). */
    final boolean keyedValues;

    CollectionSnapshot(Oop oop, List<SnapElement> elements, boolean keyedValues) {
        this.oop = oop;
        this.elements = elements;
        this.keyedValues = keyedValues;
    }

    CollectionSnapshot(Oop oop, List<SnapElement> elements) {
        this(oop, elements, false);
    }
}
