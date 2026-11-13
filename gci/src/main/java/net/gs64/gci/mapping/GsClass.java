package net.gs64.gci.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a Java class to a GemStone class. The class needs a parameterless constructor (any
 * access). Only fields marked with {@link GsInstVar} are mapped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GsClass {
    /** Name of the GemStone class. */
    String value();

    /** Symbol dictionary holding the class (e.g. "UserGlobals"); empty: resolved through the session's symbol list. */
    String dictionary() default "";
}
