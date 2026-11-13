package net.gs64.gci.mapping;

/** How a collection-valued instance variable is stored on the GemStone side. */
public enum GsCollectionKind {
    /** Array (or a subclass). */
    ARRAY,
    /** OrderedCollection or SortedCollection (or a subclass). */
    ORDERED,
    /** Set. */
    SET,
    /** IdentitySet. */
    IDENTITY_SET,
    /** Bag. */
    BAG,
    /** IdentityBag. */
    IDENTITY_BAG,
    /** Any AbstractDictionary (Dictionary, KeyValueDictionary, SymbolDictionary …). */
    DICTIONARY
}
