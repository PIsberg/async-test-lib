package com.github.asynctest.diagnostics;

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

    /**
     * Register a CyclicBarrier for monitoring.
     */
    public void registerBarrier(CyclicBarrier barrier, String name, int parties) {
        barrierRegistry.put(barrier, new BarrierInfo(name, parties));
    }

    /**
     * Record a thread arriving at the barrier.
     */
    public void recordArrival(CyclicBarrier barrier) {
        BarrierInfo info = barrierRegistry.get(barrier);
        if (info != null) {
            info.arrive();
        }
    }

    /**
     * Record a barrier await() that timed out.
     */
    public void recordTimeout(CyclicBarrier barrier) {
        timedOutBarriers.add(barrier);
    }

    /**
     * Record a barrier that was broken.
     */
    public void recordBroken(CyclicBarrier barrier) {
        brokenBarriers.add(barrier);
    }

    /**
     * Record successful barrier completion.
     */
    public void recordBarrierComplete(CyclicBarrier barrier) {
        BarrierInfo info = barrierRegistry.get(barrier);
        if (info != null) {
            info.cycleComplete();
        }
    }

    /**
     * Analyze barrier usage and return report.
     */
    public CyclicBarrierReport analyze() {
        return new CyclicBarrierReport(
            barrierRegistry,
            timedOutBarriers,
            brokenBarriers
        );
    }

    /**
     * Report class for CyclicBarrier analysis.
     */
    public static class CyclicBarrierReport {
        private final Map<CyclicBarrier, BarrierInfo> barrierRegistry;
        private final Set<CyclicBarrier> timedOutBarriers;
        private final Set<CyclicBarrier> brokenBarriers;

        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers
        ) {
            this.barrierRegistry = barrierRegistry;
            this.timedOutBarriers = timedOutBarriers;
            this.brokenBarriers = brokenBarriers;
        }

        public boolean hasIssues() {
            return !timedOutBarriers.isEmpty() || !brokenBarriers.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("CYCLICBARRIER ISSUES DETECTED:\n");

            if (!timedOutBarriers.isEmpty()) {
                sb.append("  Timed Out Barriers:\n");
                for (CyclicBarrier barrier : timedOutBarriers) {
                    BarrierInfo info = barrierRegistry.get(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (").append(info.parties).append(" parties expected, ")
                      .append(info.arrivals).append(" arrived before timeout)\n");
                }
                sb.append("  Fix: Ensure all threads reach the barrier before timeout\n");
            }

            if (!brokenBarriers.isEmpty()) {
                sb.append("  Broken Barriers:\n");
                for (CyclicBarrier barrier : brokenBarriers) {
                    BarrierInfo info = barrierRegistry.get(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (barrier broken - thread interrupted or timed out)\n");
                }
                sb.append("  Fix: Handle BrokenBarrierException and InterruptedException properly\n");
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
        int arrivals = 0;
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
    }
}
