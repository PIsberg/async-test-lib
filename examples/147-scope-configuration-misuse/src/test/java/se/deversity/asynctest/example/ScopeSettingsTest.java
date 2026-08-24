package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ScopeConfigurationMisuseDetector;
import se.deversity.asynctest.example.service.ScopeSettings;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ScopeSettings.
 *
 * ========================================================================
 * DETECTOR: ScopeConfigurationMisuseDetector
 *           (DetectorType.SCOPE_CONFIGURATION_MISUSE)
 * ========================================================================
 *
 * JEP 525 replaced the StructuredTaskScope constructors with a
 * UnaryOperator<Configuration> passed to open(). Configuration is
 * immutable, so every withTimeout / withName returns a new instance and
 * the lambda has to hand that instance back.
 *
 * A lambda that calls withTimeout for its side effect and returns
 * something else compiles and runs, and the scope silently has no
 * deadline. Structured concurrency guarantees the scope waits for every
 * subtask, so "no deadline" means one hung subtask hangs the test - the
 * exact failure the timeout was added to prevent.
 *
 * THE BUG:
 *   - cfg -> { cfg.withTimeout(d); return somethingElse; }
 *
 * THE FIX:
 *   - cfg -> cfg.withTimeout(d).withName("order-fetcher")
 */
class ScopeSettingsTest {

    private static final Duration DEADLINE = Duration.ofSeconds(3);
    private static final long NONE = ScopeConfigurationMisuseDetector.NO_TIMEOUT;

    private ScopeConfigurationMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ScopeConfigurationMisuseDetector();
    }

    /** What {@code StructuredTaskScope.open} does with the lambda: apply it to the defaults. */
    private static ScopeSettings resolve(UnaryOperator<ScopeSettings> configurator) {
        return configurator.apply(ScopeSettings.defaults());
    }

    // -----------------------------------------------------------------------
    // Part 1: the bug. The lambda derives a configuration and returns a
    // different one, so the deadline is applied to an object nobody uses.
    // -----------------------------------------------------------------------

    @Test
    void aLambdaThatDropsWhatWithTimeoutReturned_isDetected() {
        ScopeSettings effective = resolve(cfg -> {
            cfg.withTimeout(DEADLINE);          // return value discarded
            return cfg.withName("order-fetcher");
        });

        assertNull(effective.timeout(), "the deadline never reached the scope");

        detector.recordScopeOpened("scope-1", "order-fetcher", DEADLINE.toMillis(),
                null, Thread.currentThread());
        detector.recordEffectiveConfiguration("scope-1", effective.name(), effective.timeoutMillis());
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "a scope that thinks it has a deadline:\n" + report);

        var v = report.structuredViolations.stream()
                .filter(x -> "configurationDiscarded".equals(x.attributes().get("issue")))
                .findFirst()
                .orElseThrow();
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(NONE, v.attributes().get("effectiveTimeoutMillis"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the fix. One chain, returned whole. Same two recording calls.
    // -----------------------------------------------------------------------

    @Test
    void aLambdaThatReturnsItsOwnChain_isClean() {
        ScopeSettings effective = resolve(cfg -> cfg.withTimeout(DEADLINE).withName("order-fetcher"));

        assertEquals(DEADLINE, effective.timeout());

        detector.recordScopeOpened("scope-1", "order-fetcher", DEADLINE.toMillis(),
                null, Thread.currentThread());
        detector.recordEffectiveConfiguration("scope-1", effective.name(), effective.timeoutMillis());
        for (int i = 0; i < 32; i++) detector.recordFork("scope-1");
        detector.recordJoinOutcome("scope-1", false);
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "a configured deadline and a join that met it:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: no lambda at all. A wide fan-out with no deadline is the shape
    // that hangs, and it needs no mistake in the lambda to get there.
    // -----------------------------------------------------------------------

    @Test
    void aFanOutWithNoDeadline_isDetected() {
        detector.recordScopeOpened("scope-1", "fanout", NONE, null, Thread.currentThread());
        for (int i = 0; i < 20; i++) detector.recordFork("scope-1");
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("forked 20 subtasks with no timeout")));
    }

    // -----------------------------------------------------------------------
    // Part 4: a deadline so tight that every join expires. The scope is
    // configured, the code looks right, and the fallback is the only path
    // the test ever takes - so the assertions are about the fallback.
    // -----------------------------------------------------------------------

    @Test
    void aDeadlineShorterThanTheWork_isDetected() {
        detector.recordScopeOpened("scope-1", "order-fetcher", 1L, null, Thread.currentThread());
        detector.recordJoinOutcome("scope-1", true);
        detector.recordJoinOutcome("scope-1", true);
        detector.recordJoinOutcome("scope-1", true);
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("timed out on all 3 of its joins")));
    }

    // -----------------------------------------------------------------------
    // Part 5: one ThreadFactory on two scopes that are alive together. The
    // factory's per-scope state - a name counter here - serves both.
    // -----------------------------------------------------------------------

    @Test
    void oneThreadFactoryAcrossOverlappingScopes_isDetected() {
        ThreadFactory shared = new ThreadFactory() {
            private int seq;
            @Override public Thread newThread(Runnable r) {
                return new Thread(r, "worker-" + (++seq));
            }
        };

        detector.recordScopeOpened("scope-1", "a", DEADLINE.toMillis(), shared, Thread.currentThread());
        detector.recordScopeOpened("scope-2", "b", DEADLINE.toMillis(), shared, Thread.currentThread());
        detector.recordScopeClosed("scope-2");
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("One ThreadFactory was configured on 2 scopes")));
    }

    @Test
    void aThreadFactoryPerScope_isClean() {
        detector.recordScopeOpened("scope-1", "a", DEADLINE.toMillis(),
                (ThreadFactory) Thread::new, Thread.currentThread());
        detector.recordScopeOpened("scope-2", "b", DEADLINE.toMillis(),
                (ThreadFactory) Thread::new, Thread.currentThread());
        detector.recordScopeClosed("scope-2");
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "distinct factories share no state to interleave:\n" + report);
    }
}
