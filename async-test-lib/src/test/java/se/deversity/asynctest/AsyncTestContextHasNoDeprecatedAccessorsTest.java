package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.13.0 removes the 42 {@code *Monitor()} accessors 1.7 renamed to {@code *Detector()} (#921).
 *
 * <p>Each was a second name for the same instance, deprecated for five minor releases and naming its
 * replacement. Kept, they doubled the static surface of the class users read first and made every
 * new accessor a question of which suffix to pick.
 */
class AsyncTestContextHasNoDeprecatedAccessorsTest {

    /** The renames that are not a plain suffix swap, which a migration is most likely to get wrong. */
    private static final Map<String, String> IRREGULAR = Map.of(
            "semaphoreMonitor", "semaphoreMisuseDetector",
            "completableFutureMonitor", "completableFutureExceptionDetector",
            "conditionMonitor", "conditionVariableDetector",
            "copyOnWriteMonitor", "copyOnWriteCollectionDetector",
            "nestedMonitorLockoutMonitor", "nestedMonitorLockoutDetector");

    @Test
    void noPublicMethodOfTheContextIsDeprecated() {
        List<String> deprecated = Arrays.stream(AsyncTestContext.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.isAnnotationPresent(Deprecated.class))
                .map(Method::getName)
                .sorted()
                .toList();
        assertTrue(deprecated.isEmpty(), "1.13.0 removes what 1.12 deprecated: " + deprecated);
    }

    @Test
    void theOldNamesAreGone_andTheirReplacementsRemain() {
        Set<String> names = Arrays.stream(AsyncTestContext.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        IRREGULAR.forEach((old, replacement) -> {
            assertTrue(!names.contains(old), old + "() must be gone");
            assertTrue(names.contains(replacement), replacement + "() must remain");
        });
        long monitorSuffixed = names.stream()
                .filter(n -> n.endsWith("Monitor"))
                .filter(n -> names.contains(n.substring(0, n.length() - "Monitor".length()) + "Detector"))
                .count();
        assertEquals(0, monitorSuffixed, "no accessor may keep a *Monitor twin of a *Detector one");
    }
}
