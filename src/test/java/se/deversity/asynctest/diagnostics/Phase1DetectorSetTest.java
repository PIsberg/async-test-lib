package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the instance-convergence fix for {@link Phase1DetectorSet#from}.
 *
 * <p>Before this fix, {@code VisibilityMonitor}, {@code LivelockDetector},
 * {@code RaceConditionDetector}, {@code ThreadLocalMonitor}, {@code BusyWaitDetector},
 * {@code AtomicityValidator} and {@code InterruptMonitor} were each constructed twice
 * per test run — once by {@code Phase1DetectorSet.from(config)} and once inside the
 * {@code AsyncTestContext}'s {@code DetectorRegistry} — with no link between the two
 * instances. Whichever one actually received events was disconnected from whichever
 * one got analyzed by the other consumer. {@link Phase1DetectorSet#from(AsyncTestConfig, AsyncTestContext)}
 * now reuses the context's registry-backed instance for each enabled detector instead.
 */
class Phase1DetectorSetTest {

    private static AsyncTestConfig allEnabledConfig() {
        return AsyncTestConfig.builder()
                .detectVisibility(true)
                .detectLivelocks(true)
                .detectRaceConditions(true)
                .detectThreadLocalLeaks(true)
                .detectBusyWaiting(true)
                .detectAtomicityViolations(true)
                .detectInterruptMishandling(true)
                .build();
    }

    // ---- from(config, ctx): converges on the context's registry-backed instances ----

    @Test
    void from_withContext_reusesSharedVisibilityMonitor() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedVisibilityMonitor(), phase1.visibility,
                "Phase1DetectorSet must reuse the registry's VisibilityMonitor, not construct a disconnected duplicate");
    }

    @Test
    void from_withContext_reusesSharedLivelockDetector() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedLivelockDetector(), phase1.livelock);
    }

    @Test
    void from_withContext_reusesSharedRaceConditionDetector() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedRaceConditionDetector(), phase1.race);
    }

    @Test
    void from_withContext_reusesSharedThreadLocalMonitor() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedThreadLocalMonitor(), phase1.threadLocal);
    }

    @Test
    void from_withContext_reusesSharedBusyWaitDetector() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedBusyWaitDetector(), phase1.busyWait);
    }

    @Test
    void from_withContext_reusesSharedAtomicityValidator() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedAtomicityValidator(), phase1.atomicity);
        // Also the same instance the public telemetry-bridge accessor exposes.
        AsyncTestContext.install(ctx);
        try {
            assertSame(AsyncTestContext.atomicityValidator(), phase1.atomicity,
                    "the telemetry-fed instance and the Phase1DetectorSet instance must now be identical");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    void from_withContext_reusesSharedInterruptMonitor() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertSame(ctx.sharedInterruptMonitor(), phase1.interrupt);
    }

    // ---- disabled flags: still null, regardless of ctx ----

    @Test
    void from_withContext_disabledFlagsStayNull() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build(); // everything off
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        assertNull(phase1.visibility);
        assertNull(phase1.livelock);
        assertNull(phase1.race);
        assertNull(phase1.threadLocal);
        assertNull(phase1.busyWait);
        assertNull(phase1.atomicity);
        assertNull(phase1.interrupt);
    }

    // ---- fallback behavior: no context available ----

    @Test
    void from_singleArgOverload_stillConstructsFreshInstances() {
        AsyncTestConfig cfg = allEnabledConfig();
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg);

        assertNotNull(phase1.visibility);
        assertNotNull(phase1.race);
        assertNotNull(phase1.atomicity);
    }

    @Test
    void from_nullContext_behavesLikeSingleArgOverload() {
        AsyncTestConfig cfg = allEnabledConfig();
        Phase1DetectorSet withNullCtx = Phase1DetectorSet.from(cfg, null);
        Phase1DetectorSet other = Phase1DetectorSet.from(cfg, null);

        assertNotNull(withNullCtx.atomicity);
        // Each call with a null context must build its own fresh instance — no
        // accidental sharing between unrelated calls.
        assertNotSame(withNullCtx.atomicity, other.atomicity);
    }

    // ---- collectReports() must not double-report findings ctx.analyzeAll() already covers ----

    @Test
    void collectReports_withContext_isEmptyToAvoidDoubleCounting() {
        AsyncTestConfig cfg = allEnabledConfig();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg, ctx);

        // Feed a genuine, analyzable violation directly into the shared instance.
        phase1.atomicity.recordCompoundOperationStart("op");
        phase1.atomicity.recordFieldAccess("counter", 5, false);
        phase1.atomicity.recordFieldAccess("counter", 4, true);
        phase1.atomicity.recordCompoundOperationEnd("op");
        phase1.atomicity.detectCheckThenActViolation("counter", 5, 4, true);
        assertTrue(phase1.atomicity.analyzeAtomicity().hasIssues(),
                "precondition: the shared instance must have a real finding to report");

        // collectReports() must stay empty: ctx.analyzeAll() analyzes this exact same
        // instance (see DetectorRegistry.analyzeAll()) and the runner always calls that
        // on every code path — reporting it here too would double-count/double-print.
        assertTrue(phase1.collectReports().isEmpty(),
                "collectReports() must be empty when instances are shared with the registry, to avoid "
                        + "double-reporting a finding ctx.analyzeAll() already covers");

        // Confirm ctx.analyzeAll() is indeed the one surfacing it.
        assertFalse(ctx.analyzeAll().isEmpty(),
                "ctx.analyzeAll() must still report the finding recorded on the shared instance");
    }

    @Test
    void collectReports_withoutContext_stillReportsOwnFindings() {
        AsyncTestConfig cfg = allEnabledConfig();
        Phase1DetectorSet phase1 = Phase1DetectorSet.from(cfg); // no ctx — owns its instances

        phase1.atomicity.recordCompoundOperationStart("op");
        phase1.atomicity.recordFieldAccess("counter", 5, false);
        phase1.atomicity.recordFieldAccess("counter", 4, true);
        phase1.atomicity.recordCompoundOperationEnd("op");
        phase1.atomicity.detectCheckThenActViolation("counter", 5, 4, true);

        assertFalse(phase1.collectReports().isEmpty(),
                "with no shared registry to report through, Phase1DetectorSet must still report its own findings");
    }
}
