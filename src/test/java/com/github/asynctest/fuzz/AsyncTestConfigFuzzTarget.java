package com.github.asynctest.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.github.asynctest.AsyncTestConfig;

/**
 * Jazzer fuzz target for {@link AsyncTestConfig.Builder}.
 *
 * <p>Invoked by the Jazzer CLI (not by JUnit/Surefire):
 * <pre>
 *   jazzer --target_class=com.github.asynctest.fuzz.AsyncTestConfigFuzzTarget \
 *          --cp=target/test-classes:target/classes \
 *          -max_total_time=120
 * </pre>
 *
 * <p>The goal is to verify that no arbitrary combination of primitive inputs
 * to the builder causes an unexpected exception (NPE, OOBE, etc.).
 * {@link IllegalArgumentException} is an accepted outcome for clearly invalid
 * parameter combinations.
 */
public class AsyncTestConfigFuzzTarget {

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        try {
            AsyncTestConfig.builder()
                    .threads(data.consumeInt())
                    .invocations(data.consumeInt())
                    .timeoutMs(data.consumeLong())
                    .useVirtualThreads(data.consumeBoolean())
                    .detectAll(data.consumeBoolean())
                    .detectDeadlocks(data.consumeBoolean())
                    .detectVisibility(data.consumeBoolean())
                    .detectRaceConditions(data.consumeBoolean())
                    .detectAtomicityViolations(data.consumeBoolean())
                    .virtualThreadStressMode(data.consumeRemainingAsString())
                    .build();
        } catch (IllegalArgumentException ignored) {
            // Acceptable: builder validates inputs
        }
    }
}
