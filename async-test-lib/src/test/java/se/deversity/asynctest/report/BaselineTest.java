package se.deversity.asynctest.report;
import se.deversity.asynctest.E2E;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import se.deversity.asynctest.runner.ConcurrencyRunner;
import org.slf4j.LoggerFactory;

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
@E2E
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
    void aPerFindingEntryCoversThatFindingAndNoOther() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        String known = Baseline.fingerprint("  - 'order-checksum' accessed from 2 threads (worker-0, worker-1)");
        Files.write(file, List.of(
                "com.example.FooTest#bar | SharedMessageDigestDetector | " + known,
                "com.example.FooTest#bar|SharedMessageDigestDetector|- a | b"), StandardCharsets.UTF_8);

        Baseline baseline = Baseline.load(file);

        assertTrue(baseline.covers("com.example.FooTest#bar", "SharedMessageDigestDetector", known));
        assertTrue(baseline.covers("com.example.FooTest#bar", "SharedMessageDigestDetector", "- a | b"),
                "spacing around the first two separators is tolerated, and the finding keeps its own");
        assertFalse(baseline.covers("com.example.FooTest#bar", "SharedMessageDigestDetector",
                Baseline.fingerprint("  - 'session-token-hash' accessed from 2 threads (worker-0, worker-1)")));
        assertFalse(baseline.contains("com.example.FooTest#bar", "SharedMessageDigestDetector"),
                "a per-finding entry is not a detector-wide one");
    }

    @Test
    void fingerprintsIgnoreWhatChangesBetweenRuns() {
        String esc = String.valueOf((char) 27);
        assertEquals(
                Baseline.fingerprint(esc + "[33mHIGH" + esc + "[0m 'a' seen by 2 threads (w-0, w-1) in 12ms, lock@1b6d3586"),
                Baseline.fingerprint("HIGH  'a' seen by 7 threads (w-3, w-5) in 250ms, lock@7a81197d"));
        assertFalse(Baseline.fingerprint("'a' seen").equals(Baseline.fingerprint("'b' seen")),
                "the finding's identity survives normalisation");
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

    @Test
    void writtenFilesCarryTheFormatVersionAndOlderFilesStillLoad() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        System.setProperty(Baseline.PATH_PROPERTY, file.toString());
        Baseline.record("com.example.FooTest#bar", List.of("RaceConditionDetector"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(lines.contains("# format-version: " + Baseline.FORMAT_VERSION),
                "a written baseline names its format version so a future incompatible shape can be "
                        + "recognised instead of misread; was:\n" + String.join("\n", lines));

        // A file from before the marker existed (or from another release) is the same format
        // minus the comment, and must keep loading: the marker is a comment, never a gate.
        Path legacy = tempDir.resolve("legacy.txt");
        Files.writeString(legacy,
                "# async-test baseline\ncom.example.FooTest#bar | RaceConditionDetector\n",
                StandardCharsets.UTF_8);
        assertTrue(Baseline.load(legacy).contains("com.example.FooTest#bar", "RaceConditionDetector"),
                "a baseline without a format-version line must load unchanged");

        // A file from a release that knows a newer shape is refused, loudly, naming the fix.
        Path newer = tempDir.resolve("newer.txt");
        Files.writeString(newer, "# format-version: " + (Baseline.FORMAT_VERSION + 1)
                + System.lineSeparator() + "com.example.FooTest#bar | RaceConditionDetector"
                + System.lineSeparator(), StandardCharsets.UTF_8);
        IllegalStateException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> Baseline.load(newer),
                "a baseline from a newer format must not be silently misread");
        assertTrue(refused.getMessage().contains("format-version " + (Baseline.FORMAT_VERSION + 1)),
                "the refusal names the version it met; was: " + refused.getMessage());
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

    /**
     * Accepting one known finding must not accept every finding its detector reports later.
     *
     * <p>The baseline used to be keyed on test id plus detector name alone, so recording one shared
     * digest silenced a second, unrelated shared digest the same test started leaking afterwards:
     * the new finding never printed and never failed. That is the failure a baseline exists to
     * prevent, turned around.
     */
    @Test
    void aBaselinedFindingDoesNotSuppressANewFindingFromTheSameDetector() throws Exception {
        Path file = tempDir.resolve("baseline.txt");
        System.setProperty(Baseline.PATH_PROPERTY, file.toString());
        TwoFindingFixture.secondFinding = false;
        try {
            System.setProperty(Baseline.UPDATE_PROPERTY, "true");
            runFixture(TwoFindingFixture.class).assertStatistics(s -> s.succeeded(1).failed(0));
            System.clearProperty(Baseline.UPDATE_PROPERTY);

            runFixture(TwoFindingFixture.class).assertStatistics(s -> s.succeeded(1).failed(0));

            TwoFindingFixture.secondFinding = true;
            runFixture(TwoFindingFixture.class).assertStatistics(s -> s.failed(1));
        } finally {
            TwoFindingFixture.secondFinding = false;
        }
    }

    /**
     * A baseline written by an earlier release names the detector only. It must keep suppressing
     * everything that detector reports in that test, new findings included: changing what an
     * existing file accepts would turn a green build red on upgrade.
     */
    @Test
    void aDetectorWideEntryFromAnOlderFileStillSuppressesEveryFindingOfThatDetector() throws Exception {
        Path file = tempDir.resolve("legacy-baseline.txt");
        Files.writeString(file, TwoFindingFixture.class.getName() + "#sharedDigestsAcrossThreads"
                + " | SharedMessageDigestDetector" + System.lineSeparator(), StandardCharsets.UTF_8);
        System.setProperty(Baseline.PATH_PROPERTY, file.toString());
        TwoFindingFixture.secondFinding = true;
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger runnerLog = context.getLogger(ConcurrencyRunner.class);
        Level previousLevel = runnerLog.getLevel();
        runnerLog.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        runnerLog.addAppender(appender);
        try {
            runFixture(TwoFindingFixture.class).assertStatistics(s -> s.succeeded(1).failed(0));
        } finally {
            TwoFindingFixture.secondFinding = false;
            runnerLog.detachAppender(appender);
            appender.stop();
            runnerLog.setLevel(previousLevel);
        }
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().startsWith("baseline.detector-wide.suppressed ")
                        && event.getFormattedMessage().contains("detector=SharedMessageDigestDetector")),
                "a detector-wide entry hiding findings must say so at INFO; logged: "
                        + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private static Events runFixture(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
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

    /** Shares one digest always, and a second, unrelated one when {@link #secondFinding} is set. */
    static class TwoFindingFixture {
        static volatile boolean secondFinding;
        private final MessageDigest first = sharedDigest();
        private final MessageDigest second = sharedDigest();

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 10_000,
                includes = {DetectorType.SHARED_MESSAGE_DIGEST},
                failOn = FailOn.HIGH, licenseMockMode = true)
        void sharedDigestsAcrossThreads() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(first, "order-checksum", Thread.currentThread());
            if (secondFinding) {
                AsyncTestContext.sharedMessageDigestDetector()
                        .recordAccess(second, "session-token-hash", Thread.currentThread());
            }
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
