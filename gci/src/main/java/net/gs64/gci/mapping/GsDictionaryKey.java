package net.gs64.gci.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A list field whose GemStone collection is a dictionary keyed by an attribute of its values
 * (name → object). Loading yields the values; when the list changes, saving puts new values under
 * {@code value perform: selector} (computed on the server) and removes the keys of removed values.
 * Without it such a list can be loaded but not changed.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GsDictionaryKey {
    /** Unary selector the server sends to a value to get its key, e.g. "name". */
    String value();
}
