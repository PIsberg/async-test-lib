package com.github.asynctest.example;

import com.github.asynctest.AsyncTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Example demonstrating the Uncommitted Changes Detector.
 *
 * <p>This detector identifies if there are any untracked or uncommitted files
 * in the Git repository when the tests are run. This is useful for ensuring
 * that tests are executed against a clean, reproducible state.
 */
class UncommittedChangesExampleTest {

    /**
     * A test that enables uncommitted changes detection.
     *
     * <p>If you run this test in a "dirty" Git repository (e.g., after modifying
     * a source file or adding a new file without committing it), the library
     * will report a LOW severity issue in the diagnostic summary.
     */
    @AsyncTest(
        threads = 2,
        invocations = 10,
        detectUncommittedChanges = true
    )
    void testWithCleanRepositoryCheck() {
        // Your concurrent test logic here
        assertTrue(true, "Business logic passed");
    }

    /**
     * Demonstrates that the detector can also be enabled via 'detectAll'.
     */
    @AsyncTest(threads = 1, detectAll = true)
    void testWithAllDetectorsIncludingGitCheck() {
        // This will also include the uncommitted changes check
        assertTrue(true);
    }
}
