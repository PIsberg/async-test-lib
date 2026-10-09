package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import se.deversity.asynctest.AllocationBudgets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation budgets are measured in surefire's per-class JVMs, never in PIT's shared one (#951).
 *
 * <p>The weekly mutation gate of 2026-10-04 computed no score: its coverage pass runs every test
 * class in one JVM, where {@code SharedStatefulCryptoDetectorTest}'s record-path budget failed,
 * although the same test passed in its own JVM and in a scoped PIT run. A per-thread allocation
 * count depends on what the JIT could prove about the measured path, and the type profiles other
 * test classes leave behind in a shared JVM change that. A budget test kills few mutants anyway:
 * it asserts a cost, not a behaviour. So PIT's {@code jvmArgs} set
 * {@value AllocationBudgets#MUTATION_RUN_PROPERTY}, and every test that reads an allocation counter
 * calls {@link AllocationBudgets#assumeMeasurable()} first.
 */
class AllocationBudgetUnderMutationTest {

    @Test
    @DisplayName("an allocation budget is skipped inside a mutation run and measured outside it")
    void theGuardSkipsOnlyUnderMutation() {
        String key = AllocationBudgets.MUTATION_RUN_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertDoesNotThrow(AllocationBudgets::assumeMeasurable);
            System.setProperty(key, "true");
            assertThrows(TestAbortedException.class, AllocationBudgets::assumeMeasurable);
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    @DisplayName("PIT's minion JVMs carry the mutation-run property")
    void pitSetsTheProperty() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        int pit = pom.indexOf("<artifactId>pitest-maven</artifactId>");
        assertTrue(pit >= 0, "no pitest-maven plugin in async-test-lib/pom.xml");
        int jvmArgs = pom.indexOf("<jvmArgs>", pit);
        int end = pom.indexOf("</jvmArgs>", jvmArgs);
        assertTrue(jvmArgs >= 0 && end > jvmArgs, "pitest-maven has no <jvmArgs>");
        assertTrue(pom.substring(jvmArgs, end)
                        .contains("-D" + AllocationBudgets.MUTATION_RUN_PROPERTY + "=true"),
                "pitest-maven's <jvmArgs> must set -D" + AllocationBudgets.MUTATION_RUN_PROPERTY
                        + "=true, or every allocation budget is measured in PIT's shared JVM (#951)");
    }

    @Test
    @DisplayName("every test that reads an allocation counter calls the guard")
    void everyAllocationReadIsGuarded() {
        TreeSet<String> unguarded = new TreeSet<>();
        Path tests = Path.of("src/test/java");
        try (Stream<Path> files = Files.walk(tests)) {
            List<Path> java = files.filter(f -> f.toString().endsWith(".java")).toList();
            assertFalse(java.isEmpty(), "no test sources under " + tests.toAbsolutePath());
            for (Path file : java) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains("AllocatedBytes(") && !source.contains("assumeMeasurable()")) {
                    unguarded.add(tests.relativize(file).toString().replace('\\', '/'));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(unguarded.isEmpty(), "these tests read a thread allocation counter without"
                + " AllocationBudgets.assumeMeasurable(), so PIT's shared coverage JVM measures them"
                + " and a budget miss there stops the mutation gate from computing a score (#951): "
                + unguarded);
    }
}
