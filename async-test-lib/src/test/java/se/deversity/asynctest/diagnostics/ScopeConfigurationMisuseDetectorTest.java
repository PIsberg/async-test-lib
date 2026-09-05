package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeConfigurationMisuseDetectorTest {

    private static final long NONE = ScopeConfigurationMisuseDetector.NO_TIMEOUT;

    private static ThreadFactory factory() { return r -> new Thread(r, "scoped"); }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new ScopeConfigurationMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("SCOPE CONFIGURATION MISUSE - clean", d.analyze().toString());
    }

    /**
     * The corrected shape: the lambda returns what it derived, the timeout is positive and
     * generous, each scope has its own factory and name. Nothing fires.
     */
    @Test
    void aCorrectlyConfiguredScopeStaysSilent() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", 3000L, factory(), Thread.currentThread());
        d.recordEffectiveConfiguration("scope-1", "orders", 3000L);
        for (int i = 0; i < 32; i++) d.recordFork("scope-1");
        d.recordJoinOutcome("scope-1", false);
        d.recordScopeClosed("scope-1");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aTimeoutTheLambdaDroppedIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", 3000L, null, Thread.currentThread());
        d.recordEffectiveConfiguration("scope-1", "orders", NONE);   // withTimeout applied to nothing
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("timeout 3000 ms -> none")));
        assertTrue(report.structuredViolations.stream()
                .anyMatch(v -> "configurationDiscarded".equals(v.attributes().get("issue"))));
    }

    @Test
    void aNameTheLambdaDroppedIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", 1000L, null, Thread.currentThread());
        d.recordEffectiveConfiguration("scope-1", null, 1000L);
        d.recordScopeClosed("scope-1");

        assertTrue(d.analyze().violations.stream().anyMatch(v -> v.contains("name 'orders' -> none")));
    }

    @Test
    void aNegativeTimeoutIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", -5L, null, Thread.currentThread());
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("timeout of -5 ms")));
        assertTrue(report.structuredViolations.stream()
                .anyMatch(v -> v.severity() == IssueSeverity.CRITICAL));
    }

    @Test
    void aWideFanOutWithNoTimeoutIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "fanout", NONE, null, Thread.currentThread());
        for (int i = 0; i < 20; i++) d.recordFork("scope-1");
        d.recordScopeClosed("scope-1");

        assertTrue(d.analyze().violations.stream()
                .anyMatch(v -> v.contains("forked 20 subtasks with no timeout")));
    }

    /** Below the threshold, a deadline-less scope is a judgement call and stays silent. */
    @Test
    void aSmallFanOutWithNoTimeoutStaysSilent() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "pair", NONE, null, Thread.currentThread());
        d.recordFork("scope-1");
        d.recordFork("scope-1");
        d.recordScopeClosed("scope-1");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aScopeWhoseEveryJoinExpiredIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", 5L, null, Thread.currentThread());
        d.recordJoinOutcome("scope-1", true);
        d.recordJoinOutcome("scope-1", true);
        d.recordJoinOutcome("scope-1", true);
        d.recordScopeClosed("scope-1");

        assertTrue(d.analyze().violations.stream()
                .anyMatch(v -> v.contains("timed out on all 3 of its joins")));
    }

    /** One expiry among successes is a safety net doing its job, not a mis-sized deadline. */
    @Test
    void anOccasionalTimeoutStaysSilent() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "orders", 3000L, null, Thread.currentThread());
        d.recordJoinOutcome("scope-1", true);
        d.recordJoinOutcome("scope-1", false);
        d.recordScopeClosed("scope-1");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void oneThreadFactoryOnTwoOverlappingScopesIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        ThreadFactory shared = factory();
        d.recordScopeOpened("scope-1", "a", 1000L, shared, Thread.currentThread());
        d.recordScopeOpened("scope-2", "b", 1000L, shared, Thread.currentThread());
        d.recordScopeClosed("scope-2");
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("One ThreadFactory was configured on 2 scopes")));
    }

    /** The same factory reused after the first scope closed shares nothing at any instant. */
    @Test
    void aThreadFactoryReusedSequentiallyStaysSilent() {
        var d = new ScopeConfigurationMisuseDetector();
        ThreadFactory shared = factory();
        d.recordScopeOpened("scope-1", "a", 1000L, shared, Thread.currentThread());
        d.recordScopeClosed("scope-1");
        d.recordScopeOpened("scope-2", "b", 1000L, shared, Thread.currentThread());
        d.recordScopeClosed("scope-2");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void twoLiveScopesUnderOneNameIsReported() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "worker", 1000L, null, Thread.currentThread());
        d.recordScopeOpened("scope-2", "worker", 1000L, null, Thread.currentThread());
        d.recordScopeClosed("scope-2");
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("The name 'worker' was on 2 scopes")));
        assertTrue(report.toString().contains("SCOPE CONFIGURATION MISUSE DETECTED"));
    }

    /** Two scopes under one name, but never alive together: the name is unambiguous at any instant. */
    @Test
    void twoSequentialScopesUnderOneNameStaySilent() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened("scope-1", "worker", 1000L, null, Thread.currentThread());
        d.recordScopeClosed("scope-1");
        d.recordScopeOpened("scope-2", "worker", 1000L, null, Thread.currentThread());
        d.recordScopeClosed("scope-2");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void theFanOutThresholdIsNeverBelowTwo() {
        var d = new ScopeConfigurationMisuseDetector(0);
        d.recordScopeOpened("scope-1", "x", NONE, null, Thread.currentThread());
        d.recordFork("scope-1");
        d.recordScopeClosed("scope-1");
        assertFalse(d.analyze().hasIssues());   // one fork is not a fan-out at any threshold
    }

    @Test
    void recordingIsIgnoredWhileDisabled() {
        var d = new ScopeConfigurationMisuseDetector();
        d.disable();
        d.recordScopeOpened("scope-1", "x", -1L, null, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullArgumentsAreIgnored() {
        var d = new ScopeConfigurationMisuseDetector();
        d.recordScopeOpened(null, "x", 1L, null, Thread.currentThread());
        d.recordScopeOpened("scope-1", "x", 1L, null, null);
        d.recordFork("nonexistent");
        d.recordJoinOutcome("nonexistent", true);
        d.recordScopeClosed(null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void forksDoNotAccumulateAcrossAReopenedScopeId() {
        var d = new ScopeConfigurationMisuseDetector();
        for (int round = 0; round < 4; round++) {
            d.recordScopeOpened("scope-1", "orders", NONE, factory(), Thread.currentThread());
            d.recordEffectiveConfiguration("scope-1", "orders", NONE);
            for (int i = 0; i < 5; i++) d.recordFork("scope-1");
            d.recordJoinOutcome("scope-1", false);
            d.recordScopeClosed("scope-1");
        }
        assertFalse(d.analyze().hasIssues(),
            "five forks per scope is bounded; the count was summed over four scopes that "
                + "reused one id: " + d.analyze());
    }
}
