package se.deversity.asynctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * Marks a test class as end-to-end: it drives the full engine (the JUnit Platform via
 * {@code EngineTestKit} against nested {@code @AsyncTest} dummies) rather than exercising
 * one unit. Tagged classes are excluded from the default local {@code mvn test} and
 * {@code gradlew test} runs and run in CI and under {@code -P e2e}.
 *
 * <p>A meta-annotation rather than a raw {@code @Tag("e2e")} so a typo cannot silently
 * reclassify a class; {@code E2eTagGuardTest} pins the tag id and the tag set. Background:
 * docs/analysis/test-profiles-and-detector-gaps.md.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("e2e")
public @interface E2E {
}
