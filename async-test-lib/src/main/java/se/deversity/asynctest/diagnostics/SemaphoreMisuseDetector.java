package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects semaphore misuse patterns in concurrent code.
 * 
 * Common semaphore issues detected:
 * - Permit leaks: acquire() without matching release()
 * - Over-release: release() called more times than acquire()
 *   (not concluded for a semaphore the run released before it ever acquired it, unless the test
 *   registered its permits: that is how a signal looks, one thread releasing and another
 *   acquiring, and a signal nobody has awaited yet is a surplus, not a permit returned twice)
 * - Unreleased permits at test completion
 * - Concurrent access patterns that may cause starvation
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, includes = DetectorType.SEMAPHORE)
 * void testSemaphore() throws InterruptedException {
 *     semaphore.acquire();
 *     AsyncTestContext.semaphoreMisuseDetector()
 *         .recordAcquire(semaphore, "resource-pool");
 *     try {
 *         // work
 *     } finally {
 *         semaphore.release();
 *         AsyncTestContext.semaphoreMisuseDetector()
 *             .recordRelease(semaphore, "resource-pool");
 *     }
 * }
 * }</pre>
 */
public class SemaphoreMisuseDetector extends AbstractInstanceDetector<SemaphoreMisuseDetector.SemaphoreState> {

    static final class SemaphoreState {
        final String name;
        final Semaphore semaphore;
        final AtomicInteger initialPermits;
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        /**
         * Permits taken and returned on threads that work for the run without being its workers:
         * a pool thread running a handed task, a thread the body started. Kept apart because
         * such a thread may outlive the run, so what it does alone is never a finding; it only
         * balances what the run's own threads did (see {@link #recordHelperRelease}).
         */
        final AtomicInteger helperAcquireCount = new AtomicInteger(0);
        final AtomicInteger helperReleaseCount = new AtomicInteger(0);
        final Set<Long> acquiringThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> releasingThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger maxConcurrentAcquires = new AtomicInteger(0);
        final AtomicInteger currentAcquires = new AtomicInteger(0);
        /**
         * Whether a release was recorded while no acquisition had been. A holder records its
         * acquisition before its release, so this is a release by a thread that holds nothing: a
         * signal, sent ahead of whoever waits for it.
         */
        volatile boolean releasedBeforeAcquired;

        SemaphoreState(Semaphore semaphore, String name, int permits) {
            this.semaphore = semaphore;
            this.name = name;
            this.initialPermits = new AtomicInteger(permits);
        }
    }

    @Override
    SemaphoreState newState(Object instance, String label) {
        return new SemaphoreState((Semaphore) instance, label, -1);
    }

    private volatile boolean enabled = true;

    /**
     * Register a semaphore for monitoring.
     * 
     * @param semaphore the semaphore to monitor
     * @param name a descriptive name for reporting
     * @param initialPermits the initial permit count
     */
    public void registerSemaphore(Semaphore semaphore, String name, int initialPermits) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        if (!enabled || semaphore == null) {
            return;
        }
        stateFor(semaphore, name, "semaphore", label -> new SemaphoreState(semaphore, label, initialPermits));
    }

    /**
     * Record a permit acquisition.
     * 
     * @param semaphore the semaphore being recorded, tracked by identity
     * @param name the semaphore name (should match registration)
     */
    public void recordAcquire(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        // Auto-registers with unknown permits, once: get-then-put let two racing threads each
        // keep a private state, so acquiringThreads and currentAcquires undercounted exactly when
        // contention made them matter.
        SemaphoreState state = stateFor(semaphore, name, "semaphore");
        state.acquireCount.incrementAndGet();
        state.acquiringThreads.add(Thread.currentThread().threadId());
        int current = state.currentAcquires.incrementAndGet();
        state.maxConcurrentAcquires.updateAndGet(max -> Math.max(max, current));
    }

    /**
     * Record a permit release.
     * 
     * @param semaphore the semaphore being recorded, tracked by identity
     * @param name the semaphore name (should match registration)
     */
    public void recordRelease(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        // Registered on first sight, like an acquisition. A release used to count only against a
        // semaphore an acquisition had already registered, so the releases of a signal, which
        // come before the acquisitions they wake, were dropped and balanced use read as a leak.
        SemaphoreState state = stateFor(semaphore, name, "semaphore");
        if (state.acquireCount.get() == 0) {
            state.releasedBeforeAcquired = true;
        }
        state.releaseCount.incrementAndGet();
        state.releasingThreads.add(Thread.currentThread().threadId());
        state.currentAcquires.decrementAndGet();
    }

    /**
     * Record a permit acquired on a thread that works for the run without being one of its
     * workers. It is not held against the run, since the thread may still hold it when the run
     * is analysed; it only accounts for a release the run's own thread makes of that permit,
     * which would otherwise read as an over-release.
     *
     * @param semaphore the semaphore being recorded, tracked by identity
     * @param name the semaphore name
     * @since 1.14.3
     */
    public void recordHelperAcquire(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        stateFor(semaphore, name, "semaphore").helperAcquireCount.incrementAndGet();
    }

    /**
     * Record a permit released on a thread that works for the run without being one of its
     * workers: the signal a pool task, a callback or a started thread sends to the worker that
     * waits for it. Dropping it made every such signal a permit leak. A release beyond what
     * helper threads took themselves balances an acquisition by the run; a surplus is never an
     * over-release.
     *
     * @param semaphore the semaphore being recorded, tracked by identity
     * @param name the semaphore name
     * @since 1.14.3
     */
    public void recordHelperRelease(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        stateFor(semaphore, name, "semaphore").helperReleaseCount.incrementAndGet();
    }

    /**
     * Analyze semaphore usage for issues.
     * 
     * @return a report of detected issues
     */
    public SemaphoreMisuseReport analyze() {
        SemaphoreMisuseReport report = new SemaphoreMisuseReport();
        report.enabled = enabled;

        for (SemaphoreState state : states()) {
            int acquires = state.acquireCount.get();
            int releases = state.releaseCount.get();
            int available = state.semaphore.availablePermits();
            int initialPermits = state.initialPermits.get();

            // What helper threads did beyond their own use: permits they returned for the run,
            // and permits they took that the run returned.
            int helperNet = state.helperReleaseCount.get() - state.helperAcquireCount.get();
            int returnedByHelpers = Math.max(0, helperNet);
            int takenByHelpers = Math.max(0, -helperNet);

            // Check for permit leaks (more acquires than releases)
            if (acquires > releases + returnedByHelpers) {
                report.permitLeaks.add(String.format(
                    "%s: acquired %d times but released only %d times (%d permits potentially leaked)",
                    state.name, acquires, releases + returnedByHelpers,
                    acquires - releases - returnedByHelpers));
            }

            // Check for over-release (more releases than acquires). With no declared capacity
            // to hold it to, a semaphore released before it was ever acquired is a signal, and
            // its surplus is a signal not yet awaited.
            boolean signal = initialPermits < 0 && state.releasedBeforeAcquired;
            if (releases > acquires + takenByHelpers && !signal) {
                report.overReleases.add(String.format(
                    "%s: released %d times but acquired only %d times (%d extra releases)",
                    state.name, releases, acquires + takenByHelpers,
                    releases - acquires - takenByHelpers));
            }

            // Check for unreleased permits at completion
            if (initialPermits >= 0 && available != initialPermits) {
                report.unreleasedPermits.add(String.format(
                    "%s: expected %d available permits but found %d",
                    state.name, initialPermits, available));
            }

            // Track thread participation
            if (!state.acquiringThreads.isEmpty()) {
                report.threadActivity.add(String.format(
                    "%s: %d threads acquired, %d threads released, max concurrent: %d",
                    state.name,
                    state.acquiringThreads.size(),
                    state.releasingThreads.size(),
                    state.maxConcurrentAcquires.get()));
            }
        }

        if (report.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(report.toString())
                    .orElse(IssueSeverity.HIGH);
            for (String finding : report.permitLeaks) {
                report.structuredViolations.add(new Violation("Semaphore", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.overReleases) {
                report.structuredViolations.add(new Violation("Semaphore", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.unreleasedPermits) {
                report.structuredViolations.add(new Violation("Semaphore", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Report class for semaphore misuse analysis.
     */
    public static class SemaphoreMisuseReport {
        private boolean enabled = true;
        final List<String> permitLeaks = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> overReleases = new ArrayList<>();
        final List<String> unreleasedPermits = new ArrayList<>();
        /**
         * One line per semaphore object, named but not keyed by the name: two semaphores may
         * share a name, and filed under it the second one's line overwrote the first's (#789).
         */
        final List<String> threadActivity = new ArrayList<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !permitLeaks.isEmpty() || !overReleases.isEmpty() || !unreleasedPermits.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "SemaphoreMisuseReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("SEMAPHORE MISUSE DETECTED:\n");

            if (!permitLeaks.isEmpty()) {
                sb.append("  Permit Leaks:\n");
                for (String leak : permitLeaks) {
                    sb.append("    - ").append(leak).append("\n");
                }
            }

            if (!overReleases.isEmpty()) {
                sb.append("  Over-Release:\n");
                for (String over : overReleases) {
                    sb.append("    - ").append(over).append("\n");
                }
            }

            if (!unreleasedPermits.isEmpty()) {
                sb.append("  Unreleased Permits:\n");
                for (String unreleased : unreleasedPermits) {
                    sb.append("    - ").append(unreleased).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (String activity : threadActivity) {
                    sb.append("    - ").append(activity).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
                          Why: A permit that is acquired but never released is permanently consumed — the semaphore's available
                               count drops by one for every leak. Under sustained leaking, all permits are exhausted and every
                               subsequent acquire() blocks forever, halting all threads that depend on the semaphore.
                               Over-releasing is equally wrong: it inflates the permit count beyond the intended limit, allowing
                               more concurrent access than the resource can safely handle.
                          Fix:
                            - Always pair acquire() with release() in a finally block: sem.acquire(); try { ... } finally { sem.release(); }
                            - Verify the initial permit count matches the actual resource capacity\
                        """);
            return sb.toString();
        }
    }
}
