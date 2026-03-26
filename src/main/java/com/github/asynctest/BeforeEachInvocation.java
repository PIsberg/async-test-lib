package com.github.asynctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code void} method to be called <em>before every invocation round</em>
 * of an {@link AsyncTest} in the same test class.
 *
 * <p>Unlike {@code @BeforeEach}, which runs once before the entire N-thread ×
 * M-invocation execution, {@code @BeforeEachInvocation} is called once per
 * invocation round (i.e. M times). This makes it easy to reset shared state
 * between rounds without embedding reset logic inside the test body.
 *
 * <p>The method is called on the main test thread, before any worker threads are
 * launched for that round. It must be non-private and take no arguments.
 *
 * <pre>{@code
 * class CounterTest {
 *     private int counter = 0;
 *
 *     @BeforeEachInvocation
 *     void resetCounter() {
 *         counter = 0;   // reset before each of the 100 invocation rounds
 *     }
 *
 *     @AsyncTest(threads = 10, invocations = 100)
 *     void increment() {
 *         counter++;
 *     }
 *
 *     @AfterEach
 *     void verify() {
 *         assertEquals(10, counter);  // exactly one round's worth
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BeforeEachInvocation {
}
