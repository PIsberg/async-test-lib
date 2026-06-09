package se.deversity.asynctest.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.FailOn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the known-findings baseline: file parsing, matching, update mode,
 * and end-to-end suppression of the {@code failOn} gate.
 */
class BaselineTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty(Baseline.PATH_PROPERTY);
        System.clearProperty(Baseline.UPDATE_PROPERTY);
    }

    // ---- Unit: parsing & matching ----

    @Test
    void parsesEntriesAndIgnoresCommentsAndBlanks() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        Files.write(file, List.of(
                "# a comment",
                "",
                "com.example.FooTest#bar | RaceConditionDetector",
                "  com.example.FooTest#baz   |   SharedCollectionDetector  "
        ), StandardCharsets.UTF_8);

        Baseline baseline = Baseline.load(file);

        assertEquals(2, baseline.size());
        assertTrue(baseline.contains("com.example.FooTest#bar", "RaceConditionDetector"));
        assertTrue(baseline.contains("com.example.FooTest#baz", "SharedCollectionDetector"),
                "whitespace around separators must be tolerated");
        assertFalse(baseline.contains("com.example.FooTest#bar", "SharedCollectionDetector"));
    }

    @Test
    void missingFileYieldsEmptyBaseline() {
        Baseline baseline = Baseline.load(tempDir.resolve("does-not-exist.txt"));
        assertEquals(0, baseline.size());
        assertFalse(baseline.contains("x#y", "Anything"));
    }

    @Test
    void recordAppendsAndDeduplicates() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        System.setProperty(Baseline.PATH_PROPERTY, file.toString());

        assertEquals(2, Baseline.record("com.example.FooTest#bar",
                List.of("RaceConditionDetector", "SharedCollectionDetector")));
        assertEquals(0, Baseline.record("com.example.FooTest#bar",
                List.of("RaceConditionDetector")), "duplicate entries must not be re-added");
        assertEquals(1, Baseline.record("com.example.FooTest#other",
                List.of("RaceConditionDetector")));

        Baseline reloaded = Baseline.load(file);
        assertEquals(3, reloaded.size());
        assertTrue(reloaded.contains("com.example.FooTest#other", "RaceConditionDetector"));
    }

    // ---- Integration: baseline suppresses the failOn gate ----

    @Test
    void updateModeRecordsFindingsInsteadOfFailing_thenBaselineSuppressesThem() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        System.setProperty(Baseline.PATH_PROPERTY, file.toString());

        // 1. Without a baseline entry and without update mode, the gate fails the test.
        Events failing = runFixture();
        failing.assertStatistics(s -> s.failed(1));

        // 2. Update mode: same run passes and records the finding.
        System.setProperty(Baseline.UPDATE_PROPERTY, "true");
        Events recording = runFixture();
        recording.assertStatistics(s -> s.succeeded(1).failed(0));
        assertTrue(Files.exists(file), "update mode must create the baseline file");
        assertTrue(Baseline.load(file).size() > 0, "update mode must record the finding");

        // 3. Update mode off again: the recorded baseline suppresses the finding.
        System.clearProperty(Baseline.UPDATE_PROPERTY);
        Events suppressed = runFixture();
        suppressed.assertStatistics(s -> s.succeeded(1).failed(0));
    }

    private static Events runFixture() {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(BaselinedFixture.class))
                .execute()
                .testEvents();
    }

    private static MessageDigest sharedDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static class BaselinedFixture {
        private final MessageDigest shared = sharedDigest();

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 10_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                failOn = FailOn.HIGH, licenseMockMode = true)
        void sharedDigestAcrossThreads() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(shared, "shared-sha256", Thread.currentThread());
        }
    }
}
