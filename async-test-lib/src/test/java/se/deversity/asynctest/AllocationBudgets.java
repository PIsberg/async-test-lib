package se.deversity.asynctest;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Where an allocation budget can be measured (#951).
 *
 * <p>A test that reads a thread allocation counter asserts what the JIT left on a path, and in
 * PIT's coverage pass every test class shares one JVM, whose type profiles from other classes can
 * defeat the escape analysis a budget relies on. Such a test calls {@link #assumeMeasurable()}
 * before it measures; {@code AllocationBudgetUnderMutationTest} requires the call and the property.
 */
public final class AllocationBudgets {

    /** Set to {@code true} by pitest-maven's {@code jvmArgs} in {@code async-test-lib/pom.xml}. */
    public static final String MUTATION_RUN_PROPERTY = "asynctest.mutationRun";

    private AllocationBudgets() {
    }

    /** Skips the calling test inside a mutation run; does nothing anywhere else. */
    public static void assumeMeasurable() {
        assumeFalse(Boolean.getBoolean(MUTATION_RUN_PROPERTY),
                "allocation budgets are measured in a JVM of their own, not in PIT's shared one");
    }
}
