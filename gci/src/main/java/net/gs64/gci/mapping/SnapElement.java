package net.gs64.gci.mapping;

import net.gs64.gci.Oop;

/**
 * Snapshot of one collection element (or dictionary key/value) as loaded or last saved;
 * {@code nested} for an element that is a collection itself.
 */
record SnapElement(Oop oop, Object value, boolean isSymbol, CollectionSnapshot nested) {
    SnapElement(Oop oop, Object value, boolean isSymbol) {
        this(oop, value, isSymbol, null);
    }
}
