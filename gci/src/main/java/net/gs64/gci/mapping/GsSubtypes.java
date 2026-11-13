package net.gs64.gci.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The mapped subclasses (or implementations) of a class or interface, for polymorphic loading.
 * Not needed for sealed hierarchies (their permitted subclasses are found) or for classes
 * registered with the context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GsSubtypes {
    Class<?>[] value();
}
