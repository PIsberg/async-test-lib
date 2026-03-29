package com.github.asynctest.diagnostics;

import com.github.asynctest.AsyncTestConfig;
import com.github.asynctest.AsyncTestListenerRegistry;

/**
 * Groups all Phase 1 detector instances for a single test run.
 *
 * <p>Phase 1 detectors are created by {@link com.github.asynctest.runner.ConcurrencyRunner}
 * and live for the lifetime of one test-method execution. Extracting them into this
 * value-holder eliminates the 7-parameter method signatures in the runner.
 *
 * <p>This class also owns the {@link #printReports()} helper that was previously
 * spread across {@link com.github.asynctest.runner.ConcurrencyRunner#printPhase1Reports}.
 */
public final class Phase1DetectorSet {

    public final VisibilityMonitor      visibility;
    public final LivelockDetector       livelock;
    public final RaceConditionDetector  race;
    public final ThreadLocalMonitor     threadLocal;
    public final BusyWaitDetector       busyWait;
    public final AtomicityValidator     atomicity;
    public final InterruptMonitor       interrupt;

    private Phase1DetectorSet(VisibilityMonitor visibility,
                              LivelockDetector livelock,
                              RaceConditionDetector race,
                              ThreadLocalMonitor threadLocal,
                              BusyWaitDetector busyWait,
                              AtomicityValidator atomicity,
                              InterruptMonitor interrupt) {
        this.visibility  = visibility;
        this.livelock    = livelock;
        this.race        = race;
        this.threadLocal = threadLocal;
        this.busyWait    = busyWait;
        this.atomicity   = atomicity;
        this.interrupt   = interrupt;
    }

    /**
     * Instantiates Phase 1 detectors from the provided config.
     * Detectors whose flag is {@code false} are set to {@code null}.
     */
    public static Phase1DetectorSet from(AsyncTestConfig config) {
        return new Phase1DetectorSet(
            config.detectVisibility          ? new VisibilityMonitor()      : null,
            config.detectLivelocks           ? new LivelockDetector()        : null,
            config.detectRaceConditions      ? new RaceConditionDetector()   : null,
            config.detectThreadLocalLeaks    ? new ThreadLocalMonitor()      : null,
            config.detectBusyWaiting         ? new BusyWaitDetector()        : null,
            config.detectAtomicityViolations ? new AtomicityValidator()      : null,
            config.detectInterruptMishandling? new InterruptMonitor()        : null
        );
    }

    /**
     * Prints to {@code System.err} the report for every enabled Phase 1 detector
     * that has issues to report.
     *
     * <p>This was previously the private {@code printPhase1Reports} method in
     * {@link com.github.asynctest.runner.ConcurrencyRunner}.
     */
    public void printReports() {
        if (visibility != null) {
            VisibilityMonitor.VisibilityReport r = visibility.analyzeVisibility();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("VisibilityMonitor", report);
            }
        }
        if (livelock != null) {
            LivelockDetector.LivelockReport r = livelock.analyzeLivelocks();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("LivelockDetector", report);
            }
        }
        if (race != null) {
            RaceConditionDetector.RaceConditionReport r = race.analyzeRaceConditions();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("RaceConditionDetector", report);
            }
        }
        if (threadLocal != null) {
            ThreadLocalMonitor.ThreadLocalReport r = threadLocal.analyzeThreadLocalLeaks();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("ThreadLocalMonitor", report);
            }
        }
        if (busyWait != null) {
            BusyWaitDetector.BusyWaitReport r = busyWait.analyzeBusyWaiting();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("BusyWaitDetector", report);
            }
        }
        if (atomicity != null) {
            AtomicityValidator.AtomicityReport r = atomicity.analyzeAtomicity();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("AtomicityValidator", report);
            }
        }
        if (interrupt != null) {
            InterruptMonitor.InterruptReport r = interrupt.analyzeInterruptHandling();
            if (r.hasIssues()) {
                String report = "\n" + r;
                System.err.println(report);
                AsyncTestListenerRegistry.fireDetectorReport("InterruptMonitor", report);
            }
        }
    }
}
