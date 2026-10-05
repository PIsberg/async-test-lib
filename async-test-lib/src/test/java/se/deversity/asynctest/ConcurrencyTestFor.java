package se.deversity.asynctest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the classes whose {@code @AIThreadSafe} claim the annotated test drives concurrently.
 *
 * <p>An {@code @AIThreadSafe} note is a specific claim ("at-most-once gate execution under
 * contention"), and {@code ThreadSafetyClaimsAreTestedConcurrentlyTest} requires every class that
 * makes one to be named here by a test that runs it on several threads at once: an
 * {@code @AsyncTest}, or a test that releases its threads through a {@code CyclicBarrier} (#906).
 * The marker is explicit so that a test which merely mentions a class does not count.
 *
 * <p>Only the gate reads it, from source, so it is retained in source only. A test module that
 * cannot see this class (the agent, the analysis module) declares its own annotation with the same
 * simple name; the gate matches the name, not the type.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ConcurrencyTestFor {

    /** {@return the classes whose thread-safety claim this test can fail on} */
    Class<?>[] value();
}
