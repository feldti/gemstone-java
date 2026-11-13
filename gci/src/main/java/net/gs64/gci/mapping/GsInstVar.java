package net.gs64.gci.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Maps a field (any access, not final) to a named instance variable of the GemStone class. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GsInstVar {
    /** Name of the instance variable. */
    String value();
}
