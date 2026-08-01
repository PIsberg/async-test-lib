package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;

/**
 * Detects Phaser misuse patterns:
 * - Missing arrive() calls (phaser never advances)
 * - Phaser timeout (awaitAdvance with timeout expiring)
 * - Phaser termination (phaser terminated unexpectedly)
 * - Wrong party count (more/fewer parties than registered)
 */
public class PhaserDetector {

    private final Map<Phaser, PhaserInfo> phaserRegistry = new ConcurrentHashMap<>();
    private final Set<Phaser> timedOutPhasers = ConcurrentHashMap.newKeySet();
    private final Set<Phaser> terminatedPhasers = ConcurrentHashMap.newKeySet();

    /**
     * Register a Phaser for monitoring.
     */
    public void registerPhaser(Phaser phaser, String name, int parties) {
        phaserRegistry.put(phaser, new PhaserInfo(name, parties));
    }

    /**
     * Record a thread arriving at the phaser.
     */
    public void recordArrive(Phaser phaser) {
        PhaserInfo info = phaserRegistry.get(phaser);
        if (info != null) {
            info.arrive();
        }
    }

    /**
     * Record a thread arriving and awaiting advance.
     */
    public void recordArriveAwaitAdvance(Phaser phaser) {
        PhaserInfo info = phaserRegistry.get(phaser);
        if (info != null) {
            info.arrive();
            info.awaitAdvance();
        }
    }

    /**
     * Record a phaser await that timed out.
     */
    public void recordTimeout(Phaser phaser) {
        timedOutPhasers.add(phaser);
    }

    /**
     * Record a phaser that was terminated.
     */
    public void recordTermination(Phaser phaser) {
        terminatedPhasers.add(phaser);
    }

    /**
     * Record successful phaser phase completion.
     */
    public void recordPhaseComplete(Phaser phaser, int phase) {
        PhaserInfo info = phaserRegistry.get(phaser);
        if (info != null) {
            info.phaseComplete(phase);
        }
    }

    /**
     * Analyze phaser usage and return report.
     */
    public PhaserReport analyze() {
        return new PhaserReport(
            phaserRegistry,
            timedOutPhasers,
            terminatedPhasers
        );
    }

    /**
     * Report class for Phaser analysis.
     */
    public static class PhaserReport {
        private final Map<Phaser, PhaserInfo> phaserRegistry;
        private final Set<Phaser> timedOutPhasers;
        private final Set<Phaser> terminatedPhasers;

        public PhaserReport(
            Map<Phaser, PhaserInfo> phaserRegistry,
            Set<Phaser> timedOutPhasers,
            Set<Phaser> terminatedPhasers
        ) {
            this.phaserRegistry = Collections.unmodifiableMap(new HashMap<>(phaserRegistry));
            this.timedOutPhasers = Collections.unmodifiableSet(new HashSet<>(timedOutPhasers));
            this.terminatedPhasers = Collections.unmodifiableSet(new HashSet<>(terminatedPhasers));
        }

        public boolean hasIssues() {
            return !timedOutPhasers.isEmpty() || !terminatedPhasers.isEmpty();
        }

        /**
         * Registry lookup that always yields a non-null {@code PhaserInfo}.
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
        private PhaserInfo infoFor(Phaser phaser) {
            PhaserInfo info = phaserRegistry.get(phaser);
            return info != null ? info : new PhaserInfo("<unregistered phaser>", 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("PHASER ISSUES DETECTED:\n");

            if (!timedOutPhasers.isEmpty()) {
                sb.append("  Timed Out Phasers:\n");
                for (Phaser phaser : timedOutPhasers) {
                    PhaserInfo info = infoFor(phaser);
                    sb.append("    - ").append(info.name)
                      .append(" (").append(info.parties).append(" parties expected, ")
                      .append(info.getArrivals()).append(" arrived)\n");
                }
                sb.append("  Why: A Phaser advances only when all registered parties arrive. If any party never calls arrive() or arriveAndAwaitAdvance(),\n");
                sb.append("       all other parties block at the phase boundary indefinitely — the phaser stalls forever.\n");
                sb.append("  Fix: Ensure every registered party always arrives (even on exception paths); use try/finally or deregister with arriveAndDeregister()\n");
            }

            if (!terminatedPhasers.isEmpty()) {
                sb.append("  Terminated Phasers:\n");
                for (Phaser phaser : terminatedPhasers) {
                    PhaserInfo info = infoFor(phaser);
                    sb.append("    - ").append(info.name)
                      .append(" (phaser terminated - possibly due to timeout or unbalance)\n");
                }
                sb.append("  Why: A terminated Phaser rejects all further arrive/await calls, causing threads that still need to\n");
                sb.append("       synchronise to receive an unexpected terminated-state response.\n");
                sb.append("  Fix: Check phaser.isTerminated() before registering or arriving; investigate unintended arriveAndDeregister() calls\n");
            }

            if (!hasIssues()) {
                sb.append("  No Phaser issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal phaser information.
     */
    static class PhaserInfo {
        final String name;
        final int parties;
        private int arrivals = 0;
        int completedPhases = 0;
        int currentPhase = 0;

        PhaserInfo(String name, int parties) {
            this.name = name;
            this.parties = parties;
        }

        synchronized void arrive() {
            arrivals++;
        }

        synchronized void awaitAdvance() {
            // Mark that thread is waiting
        }

        synchronized void phaseComplete(int phase) {
            if (phase > currentPhase) {
                completedPhases++;
                currentPhase = phase;
                arrivals = 0;
            }
        }

        synchronized int getArrivals() {
            return arrivals;
        }
    }
}
