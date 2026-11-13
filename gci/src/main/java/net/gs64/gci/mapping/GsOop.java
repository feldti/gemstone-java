package net.gs64.gci.mapping;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Receives the OOP of the loaded (or newly saved) object; the field type must be {@link net.gs64.gci.Oop}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GsOop {
}
