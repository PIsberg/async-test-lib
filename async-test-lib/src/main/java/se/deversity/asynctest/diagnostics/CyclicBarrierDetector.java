package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

/**
 * Detects CyclicBarrier misuse patterns:
 * - Barrier timeout (await with timeout expiring)
 * - Broken barrier (barrier broken due to thread interruption or timeout)
 * - Barrier reuse issues (inconsistent participation across cycles)
 * - Missing participants (not all expected threads arrive)
 */
public class CyclicBarrierDetector {

    private final Map<CyclicBarrier, BarrierInfo> barrierRegistry = new ConcurrentHashMap<>();
    private final Set<CyclicBarrier> timedOutBarriers = ConcurrentHashMap.newKeySet();
    private final Set<CyclicBarrier> brokenBarriers = ConcurrentHashMap.newKeySet();
    private final Set<CyclicBarrier> reuseAfterBrokenBarriers = ConcurrentHashMap.newKeySet();

    /**
     * Register a CyclicBarrier for monitoring.
     *
     * @param barrier the barrier being recorded, tracked by identity
     * @param name a label identifying the barrier in the report
     * @param parties the number of parties the barrier was created for
     */
    public void registerBarrier(CyclicBarrier barrier, String name, int parties) {
        barrierRegistry.put(barrier, new BarrierInfo(name, parties));
    }

    /**
     * Record a thread arriving at the barrier.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordArrival(CyclicBarrier barrier) {
        BarrierInfo info = barrierRegistry.get(barrier);
        if (info != null) {
            info.arrive();
        }
    }

    /**
     * Record a barrier await() that timed out.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordTimeout(CyclicBarrier barrier) {
        timedOutBarriers.add(barrier);
    }

    /**
     * Record a barrier that was broken.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordBroken(CyclicBarrier barrier) {
        brokenBarriers.add(barrier);
    }

    /**
     * Record a barrier that was reset, repairing it after it broke.
     * Subsequent await() calls are no longer considered reuse-after-broken.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordReset(CyclicBarrier barrier) {
        brokenBarriers.remove(barrier);
    }

    /**
     * Record a thread calling await() on the barrier. If the barrier is
     * currently broken and has not been reset since, this is flagged as
     * reuse of a broken barrier without an intervening reset().
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordAwait(CyclicBarrier barrier) {
        if (brokenBarriers.contains(barrier)) {
            reuseAfterBrokenBarriers.add(barrier);
        }
    }

    /**
     * Record successful barrier completion.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordBarrierComplete(CyclicBarrier barrier) {
        BarrierInfo info = barrierRegistry.get(barrier);
        if (info != null) {
            info.cycleComplete();
        }
    }

    /**
     * Analyze barrier usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public CyclicBarrierReport analyze() {
        return new CyclicBarrierReport(
            barrierRegistry,
            timedOutBarriers,
            brokenBarriers,
            reuseAfterBrokenBarriers
        );
    }

    /**
     * Report class for CyclicBarrier analysis.
     */
    public static class CyclicBarrierReport {
        private final Map<CyclicBarrier, BarrierInfo> barrierRegistry;
        private final Set<CyclicBarrier> timedOutBarriers;
        private final Set<CyclicBarrier> brokenBarriers;
        private final Set<CyclicBarrier> reuseAfterBrokenBarriers;
        /**
         * Creates a CyclicBarrierReport.
         *
         * @param barrierRegistry every registered barrier and what was observed on it
         * @param timedOutBarriers the barriers whose await timed out
         * @param brokenBarriers the barriers left in a broken state
         * @param reuseAfterBrokenBarriers the barriers used again after they had broken
         */
        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers,
            Set<CyclicBarrier> reuseAfterBrokenBarriers
        ) {
            this.barrierRegistry = Collections.unmodifiableMap(new HashMap<>(barrierRegistry));
            this.timedOutBarriers = Collections.unmodifiableSet(new HashSet<>(timedOutBarriers));
            this.brokenBarriers = Collections.unmodifiableSet(new HashSet<>(brokenBarriers));
            this.reuseAfterBrokenBarriers = Collections.unmodifiableSet(new HashSet<>(reuseAfterBrokenBarriers));
        }

        /**
         * Legacy constructor retained for binary compatibility with 1.6.0, before
         * reuse-after-broken tracking was added.
         *
         * @deprecated since 1.7.0 — use the four-argument constructor; this overload
         *             reports no reuse-after-broken barriers.
         *
         * @param barrierRegistry every registered barrier and what was observed on it
         * @param timedOutBarriers the barriers whose await timed out
         * @param brokenBarriers the barriers left in a broken state
         */
        @Deprecated(since = "1.7.0")
        @SuppressWarnings("InlineMeSuggester") // binary-compat shim for 1.6.0 callers, not an active migration target
        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers
        ) {
            this(barrierRegistry, timedOutBarriers, brokenBarriers, Collections.emptySet());
        }

        /**
         * {@return the reuse after broken barriers}
         */
        public Set<CyclicBarrier> getReuseAfterBrokenBarriers() {
            return reuseAfterBrokenBarriers;
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !timedOutBarriers.isEmpty() || !brokenBarriers.isEmpty() || !reuseAfterBrokenBarriers.isEmpty();
        }

        /**
         * Registry lookup that always yields a non-null {@code BarrierInfo}.
         *
         * <p>Nothing requires a {@code record*} call's subject to have been passed to the matching
         * {@code register*} first — no precondition, no runtime check — and the two are written at
         * different places in a test. When the registration is missed the lookup returns
         * {@code null} and dereferencing it threw out of {@code toString()}. That NPE never reached
         * the user: {@code DetectorRegistry.ifIssue} catches it so one detector cannot discard the
         * whole sweep, so the finding was simply dropped and the report the user needed never
         * appeared. A placeholder keeps the finding and says plainly which subject was not
         * registered.
         */
        private BarrierInfo infoFor(CyclicBarrier barrier) {
            BarrierInfo info = barrierRegistry.get(barrier);
            return info != null ? info : new BarrierInfo("<unregistered barrier>", 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("CYCLICBARRIER ISSUES DETECTED:\n");

            if (!timedOutBarriers.isEmpty()) {
                sb.append("  Timed Out Barriers:\n");
                for (CyclicBarrier barrier : timedOutBarriers) {
                    BarrierInfo info = infoFor(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (").append(info.parties).append(" parties expected, ")
                      .append(info.getArrivals()).append(" arrived before timeout)\n");
                }
                sb.append("  Why: If fewer threads than expected call await(), the barrier never trips and all waiting threads block indefinitely.\n");
                sb.append("       Once a barrier is broken, it propagates BrokenBarrierException to all waiting parties.\n");
                sb.append("  Fix: Ensure all registered parties call await(); wrap in try-catch BrokenBarrierException and reset() before reuse\n");
            }

            if (!brokenBarriers.isEmpty()) {
                sb.append("  Broken Barriers:\n");
                for (CyclicBarrier barrier : brokenBarriers) {
                    BarrierInfo info = infoFor(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (barrier broken - thread interrupted or timed out)\n");
                }
                sb.append("  Why: A thread timing out or being interrupted breaks the barrier for all other waiting parties,\n");
                sb.append("       leaving them stuck unless the barrier is explicitly reset.\n");
                sb.append("  Fix: Catch BrokenBarrierException, call barrier.reset() before reuse, and restore interrupt status on InterruptedException\n");
            }

            if (!reuseAfterBrokenBarriers.isEmpty()) {
                sb.append("  Reuse After Broken Barriers:\n");
                for (CyclicBarrier barrier : reuseAfterBrokenBarriers) {
                    BarrierInfo info = infoFor(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (await() called on a barrier that was already broken)\n");
                }
                sb.append("  Why: await() on a broken barrier throws BrokenBarrierException immediately for every caller;\n");
                sb.append("       the barrier stays broken until reset() is called, so repeated reuse without a reset\n");
                sb.append("       keeps failing every participant.\n");
                sb.append("  Fix: Call barrier.reset() after handling BrokenBarrierException, or replace the barrier instance;\n");
                sb.append("       consider Phaser for more flexible recovery when barriers break frequently.\n");
            }

            if (!hasIssues()) {
                sb.append("  No CyclicBarrier issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal barrier information.
     */
    static class BarrierInfo {
        final String name;
        final int parties;
        private int arrivals = 0;
        int completedCycles = 0;

        BarrierInfo(String name, int parties) {
            this.name = name;
            this.parties = parties;
        }

        synchronized void arrive() {
            arrivals++;
        }

        synchronized void cycleComplete() {
            completedCycles++;
            arrivals = 0;
        }

        synchronized int getArrivals() {
            return arrivals;
        }
    }
}
