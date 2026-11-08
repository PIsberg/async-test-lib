package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.report.Violation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link KotlinMutexReentrancyDetector} (#1007). Mutexes and coroutine {@code Job}s
 * are plain objects here: the detector compares them by identity and never calls them. The
 * consumer fixture in {@code consumer-fixture-langs/kotlin} proves the woven calls against real
 * kotlinx.
 */
class KotlinMutexReentrancyDetectorTest {

    private final Object mutex = new Object();
    private final Object outer = new Object();
    private final Object scope = new Object();

    @Test
    @DisplayName("nothing recorded is clean")
    void cleanWhenNothingRecorded() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        KotlinMutexReentrancyDetector.Report report = d.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.structuredViolations.isEmpty());
        assertTrue(report.toString().contains("clean"));
    }

    @Test
    @DisplayName("the holder is found among the caller and the scopes waiting for it")
    void holderIsFoundInTheWaitingChain() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        assertEquals(1, d.holderAmong(mutex, List.of(scope, outer)));
        assertEquals(0, d.holderAmong(mutex, List.of(outer)));
        assertEquals(-1, d.holderAmong(mutex, List.of(scope)));
        assertEquals(-1, d.holderAmong(new Object(), List.of(outer)), "another mutex has no holder");
    }

    @Test
    @DisplayName("an unlock, or a take by an unnamed coroutine, forgets the holder")
    void holderIsForgotten() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        d.recordHolderUnknown(mutex);
        assertEquals(-1, d.holderAmong(mutex, List.of(outer)));

        d.recordAcquired(mutex, outer);
        d.recordAcquired(mutex, null);
        assertEquals(-1, d.holderAmong(mutex, List.of(outer)), "a take with no Job leaves the holder unknown");
    }

    @Test
    @DisplayName("a holder is compared by identity, not equals")
    void holderIsComparedByIdentity() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, "job");
        assertEquals(-1, d.holderAmong(mutex, List.of(new String("job"))));
    }

    @Test
    @DisplayName("a reentry through a scope is reported with its path, as a HIGH violation")
    void reentryThroughAScopeIsReported() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        int held = d.holderAmong(mutex, List.of(scope, outer));
        d.recordReentry(mutex, List.of("DispatchedCoroutine", "BlockingCoroutine"), held, Thread.currentThread());

        KotlinMutexReentrancyDetector.Report report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.structuredViolations.size());
        Violation v = report.structuredViolations.get(0);
        assertEquals("KotlinMutexReentrancy", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertTrue(v.message().contains("BlockingCoroutine holds it and is suspended until DispatchedCoroutine"),
                v.message());
        assertEquals(1, v.attributes().get("reentries"));
        assertTrue(report.toString().contains("not reentrant"), report.toString());
    }

    @Test
    @DisplayName("the holder locking directly again is reported as such")
    void directReentryIsReported() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        d.recordReentry(mutex, List.of("StandaloneCoroutine"), d.holderAmong(mutex, List.of(outer)),
                Thread.currentThread());
        d.recordReentry(mutex, List.of("StandaloneCoroutine"), 0, Thread.currentThread());

        KotlinMutexReentrancyDetector.Report report = d.analyze();
        assertEquals(1, report.violations.size(), "one finding per mutex");
        assertTrue(report.violations.get(0).contains("StandaloneCoroutine holds it and called lock() on it again"),
                report.violations.get(0));
        assertTrue(report.violations.get(0).contains("2 time(s)"), report.violations.get(0));
    }

    @Test
    @DisplayName("a held mutex nobody locks again is not a finding")
    void heldWithoutReentryIsClean() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    @DisplayName("null arguments and an index outside the path record nothing")
    void invalidArgumentsAreIgnored() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(null, outer);
        d.recordHolderUnknown(null);
        d.recordHolderUnknown(mutex);
        d.recordReentry(null, List.of("A"), 0, Thread.currentThread());
        d.recordReentry(mutex, null, 0, Thread.currentThread());
        d.recordReentry(mutex, List.of("A"), 0, null);
        d.recordReentry(mutex, List.of("A"), -1, Thread.currentThread());
        d.recordReentry(mutex, List.of("A"), 1, Thread.currentThread());
        assertEquals(-1, d.holderAmong(null, List.of(outer)));
        assertEquals(-1, d.holderAmong(mutex, null));
        assertEquals(-1, d.holderAmong(mutex, List.of()));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    @DisplayName("reset forgets every mutex")
    void resetClears() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.recordAcquired(mutex, outer);
        d.recordReentry(mutex, List.of("A"), 0, Thread.currentThread());
        d.reset();
        assertFalse(d.analyze().hasIssues());
        assertEquals(-1, d.holderAmong(mutex, List.of(outer)));
    }

    /**
     * A mutex registered by a reentry still being recorded, its count not yet raised, is left out
     * rather than reported half-written (the analyze-during-record shape of #1212).
     */
    @Test
    @DisplayName("a mutex registered but not yet counted is not reported")
    void registeredButUncountedIsNotReported() {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        d.stateFor(mutex, null, "Mutex").lastPath = "A holds it";
        assertFalse(d.analyze().hasIssues());
    }

    /** The detector releases a mutex the code under test dropped (#918). */
    @Test
    void doesNotKeepARecordedSubjectAlive() throws InterruptedException {
        KotlinMutexReentrancyDetector d = new KotlinMutexReentrancyDetector();
        AbstractInstanceDetectorTest.assertNotRetained(Object::new, m -> d.recordAcquired(m, outer));
    }
}
