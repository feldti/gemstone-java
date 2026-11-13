package net.gs64.gci;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A test against a real stone; skipped unless GS_TEST_STONE is set. Stone tests run one after
 * another (JUnit's default): the classic GCI has one current session per process.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Test
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+", disabledReason = "GS_TEST_STONE not set — no GemStone stone available")
public @interface StoneTest {
}
