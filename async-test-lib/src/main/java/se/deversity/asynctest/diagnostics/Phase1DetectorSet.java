package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListenerRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Groups all Phase 1 detector instances for a single test run.
 *
 * <p>Phase 1 detectors are created by {@link se.deversity.asynctest.runner.ConcurrencyRunner}
 * and live for the lifetime of one test-method execution. Extracting them into this
 * value-holder eliminates the 7-parameter method signatures in the runner.
 *
 * <p>This class also owns the {@link #printReports()} helper that was previously
 * the private {@code printPhase1Reports} method in {@link se.deversity.asynctest.runner.ConcurrencyRunner}.
 */
public final class Phase1DetectorSet {

    public final VisibilityMonitor      visibility;
    public final LivelockDetector       livelock;
    public final RaceConditionDetector  race;
    public final ThreadLocalMonitor     threadLocal;
    public final BusyWaitDetector       busyWait;
    public final AtomicityValidator     atomicity;
    public final InterruptMonitor       interrupt;

    /**
     * True when this set's instances were sourced from an {@link AsyncTestContext}'s
     * {@code DetectorRegistry} (the normal runner path). {@link #collectReports()} and
     * {@link #printReports()} use this to avoid reporting the same finding twice: every
     * one of this class's detectors is <em>also</em> analyzed by
     * {@code DetectorRegistry.analyzeAll()} (surfaced via
     * {@code AsyncTestContext.analyzeAll()}), which the runner already calls on every
     * code path (success, failure, and timeout). Once {@link #from(AsyncTestConfig, AsyncTestContext)}
     * made these the *same* objects as the registry's, letting both sides independently
     * report on them would double-count and double-print every Phase 1/3 finding.
     * Only when {@code ctx} was {@code null} (direct/unit-test construction, where no
     * registry exists to report through) do these methods report the detectors
     * themselves.
     */
    private final boolean reportedByRegistry;

    private Phase1DetectorSet(VisibilityMonitor visibility,
                              LivelockDetector livelock,
                              RaceConditionDetector race,
                              ThreadLocalMonitor threadLocal,
                              BusyWaitDetector busyWait,
                              AtomicityValidator atomicity,
                              InterruptMonitor interrupt,
                              boolean reportedByRegistry) {
        this.visibility  = visibility;
        this.livelock    = livelock;
        this.race        = race;
        this.threadLocal = threadLocal;
        this.busyWait    = busyWait;
        this.atomicity   = atomicity;
        this.interrupt   = interrupt;
        this.reportedByRegistry = reportedByRegistry;
    }

    /**
     * Instantiates Phase 1 detectors from the provided config.
     * Detectors whose flag is {@code false} are set to {@code null}.
     *
     * <p>Prefer {@link #from(AsyncTestConfig, AsyncTestContext)} whenever a context is
     * available (the runner always has one) — this overload always constructs fresh,
     * disconnected instances and is kept only for direct/unit-test construction.
     */
    public static Phase1DetectorSet from(AsyncTestConfig config) {
        return from(config, null);
    }

    /**
     * Instantiates Phase 1 detectors from the provided config, reusing {@code ctx}'s
     * {@code DetectorRegistry}-backed instances for any detector whose flag is enabled.
     *
     * <p>Without this, {@code VisibilityMonitor}, {@code LivelockDetector},
     * {@code RaceConditionDetector}, {@code ThreadLocalMonitor}, {@code BusyWaitDetector},
     * {@code AtomicityValidator} and {@code InterruptMonitor} were each constructed twice
     * per test run — once here and once inside {@code DetectorRegistry} — with no link
     * between the two. Whichever instance actually received events (recorded manually,
     * via the telemetry bridge, or by a future instrumentation source) was silently
     * disconnected from the other instance's analysis pass, which always analyzed an
     * empty detector. Reusing {@code ctx}'s instance here means recording and analysis
     * always converge on the same object.
     *
     * <p>Falls back to constructing a fresh instance when {@code ctx} is {@code null} or
     * when the registry unexpectedly has none for an enabled flag — this should not
     * happen in practice since both are built from the same {@code config}, but a
     * disconnected-but-functional detector is safer than a {@code NullPointerException}.
     *
     * @since 1.9.0
     */
    public static Phase1DetectorSet from(AsyncTestConfig config, AsyncTestContext ctx) {
        return new Phase1DetectorSet(
            config.detectVisibility
                ? sharedOrNew(ctx == null ? null : ctx.sharedVisibilityMonitor(), VisibilityMonitor::new)
                : null,
            config.detectLivelocks
                ? sharedOrNew(ctx == null ? null : ctx.sharedLivelockDetector(), LivelockDetector::new)
                : null,
            config.detectRaceConditions
                ? sharedOrNew(ctx == null ? null : ctx.sharedRaceConditionDetector(), RaceConditionDetector::new)
                : null,
            config.detectThreadLocalLeaks
                ? sharedOrNew(ctx == null ? null : ctx.sharedThreadLocalMonitor(), ThreadLocalMonitor::new)
                : null,
            config.detectBusyWaiting
                ? sharedOrNew(ctx == null ? null : ctx.sharedBusyWaitDetector(), BusyWaitDetector::new)
                : null,
            config.detectAtomicityViolations
                ? sharedOrNew(ctx == null ? null : ctx.sharedAtomicityValidator(), AtomicityValidator::new)
                : null,
            config.detectInterruptMishandling
                ? sharedOrNew(ctx == null ? null : ctx.sharedInterruptMonitor(), InterruptMonitor::new)
                : null,
            ctx != null
        );
    }

    private static <T> T sharedOrNew(T shared, Supplier<T> factory) {
        return shared != null ? shared : factory.get();
    }

    /**
     * Prints to {@code System.err} the report for every enabled Phase 1 detector
     * that has issues to report.
     *
     * <p>This was previously the private {@code printPhase1Reports} method in
     * {@link se.deversity.asynctest.runner.ConcurrencyRunner}.
     */
    public void printReports() {
        for (Map.Entry<String, String> e : collectReports().entrySet()) {
            System.err.println(e.getValue());
            AsyncTestListenerRegistry.fireDetectorReport(e.getKey(), e.getValue());
        }
    }

    /**
     * Analyzes every enabled Phase 1 detector and returns the reports of those
     * with issues, keyed by detector name, in stable declaration order.
     *
     * <p>Unlike {@link #printReports()} this performs no printing or listener
     * firing — used by the runner's success-path {@code failOn} gate, which
     * needs the reports as data before deciding whether to fail the test.
     *
     * <p>Returns an empty map when {@link #reportedByRegistry} is {@code true}: these
     * detectors are the same instances {@code DetectorRegistry.analyzeAll()} already
     * analyzes (via {@code AsyncTestContext.analyzeAll()}, called on every code path),
     * so reporting them here too would double-count and double-print every finding.
     *
     * @since 1.7.0
     */
    public Map<String, String> collectReports() {
        Map<String, String> out = new LinkedHashMap<>();
        if (reportedByRegistry) {
            return out;
        }
        if (visibility != null) {
            VisibilityMonitor.VisibilityReport r = visibility.analyzeVisibility();
            if (r.hasIssues()) out.put("VisibilityMonitor", "\n" + r);
        }
        if (livelock != null) {
            LivelockDetector.LivelockReport r = livelock.analyzeLivelocks();
            if (r.hasIssues()) out.put("LivelockDetector", "\n" + r);
        }
        if (race != null) {
            RaceConditionDetector.RaceConditionReport r = race.analyzeRaceConditions();
            if (r.hasIssues()) out.put("RaceConditionDetector", "\n" + r);
        }
        if (threadLocal != null) {
            ThreadLocalMonitor.ThreadLocalReport r = threadLocal.analyzeThreadLocalLeaks();
            if (r.hasIssues()) out.put("ThreadLocalMonitor", "\n" + r);
        }
        if (busyWait != null) {
            BusyWaitDetector.BusyWaitReport r = busyWait.analyzeBusyWaiting();
            if (r.hasIssues()) out.put("BusyWaitDetector", "\n" + r);
        }
        if (atomicity != null) {
            AtomicityValidator.AtomicityReport r = atomicity.analyzeAtomicity();
            if (r.hasIssues()) out.put("AtomicityValidator", "\n" + r);
        }
        if (interrupt != null) {
            InterruptMonitor.InterruptReport r = interrupt.analyzeInterruptHandling();
            if (r.hasIssues()) out.put("InterruptMonitor", "\n" + r);
        }
        return out;
    }
}
