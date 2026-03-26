package com.github.asynctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code void} method to be called <em>after every invocation round</em>
 * of an {@link AsyncTest} in the same test class.
 *
 * <p>The method is called on the main test thread after all worker threads for
 * that round have finished (successfully or not). It runs even if the round
 * produced failures, so it can be used for resource cleanup or round-level
 * assertions (e.g. verify an invariant after each burst of N threads).
 *
 * <p>The method must be non-private and take no arguments. If it throws, the
 * exception is reported alongside any thread failures from the same round.
 *
 * <pre>{@code
 * class CounterTest {
 *     private volatile int counter = 0;
 *
 *     @BeforeEachInvocation
 *     void reset() { counter = 0; }
 *
 *     @AsyncTest(threads = 10, invocations = 50)
 *     void increment() { counter++; }
 *
 *     @AfterEachInvocation
 *     void assertRoundInvariant() {
 *         // called 50 times; counter should be ≤ 10 (could be less due to races)
 *         assertTrue(counter <= 10);
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AfterEachInvocation {
}
