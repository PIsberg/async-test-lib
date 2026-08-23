package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical case a user writes first: a collection shared by the test body itself.
 *
 * <p>Separate from the corpus because it is not a third-party subject. It pins the path everything
 * else in this module depends on: the test class is loaded by JUnit before the runner attaches the
 * agent, so a finding here proves that retransformation reaches an already-loaded class and that
 * the call site in the body is rewritten. If this goes silent, every zero in the corpus report
 * becomes meaningless rather than informative.
 */
class TestBodyCollectionIsObservedTest {

    private static AsyncFindings findings;

    private final Map<String, Integer> shared = new HashMap<>();

    @BeforeAll
    static void collect() {
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 4, invocations = 25)
    void sharedHashMapFromTheTestBody() {
        shared.put("key", shared.size());
    }

    @AfterAll
    static void theBodysOwnCallsAreObserved() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SharedCollection")),
                    "an unsynchronized HashMap written by four threads from the test body must be "
                            + "reported. Nothing was: either the agent no longer retransforms the "
                            + "already-loaded test class, or CollectionAccessWeaver stopped "
                            + "matching Map.put. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
