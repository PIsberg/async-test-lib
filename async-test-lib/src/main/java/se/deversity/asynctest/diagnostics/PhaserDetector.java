package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;

/**
 * Detects a {@link Phaser} whose party count came up short.
 *
 * <p>Two findings, both decided on the real phaser rather than on what the test declares:
 * <ul>
 *   <li><b>An arrival after the parties ran out.</b> A root phaser terminates when its registered
 *       party count reaches zero, and from then on {@code register}, {@code bulkRegister},
 *       {@code arrive}, {@code arriveAndDeregister} and {@code arriveAndAwaitAdvance} return a
 *       negative phase instead of synchronizing. A party making one of those calls was never in
 *       the count: the phaser was created or registered with too few parties, or a party
 *       deregistered twice. Pass the phase the call returned to
 *       {@link #recordArrival(Phaser, int)}; a negative phase on a phaser with no registered
 *       parties left is the finding.</li>
 *   <li><b>A stalled phase.</b> A registered party never arrived, so the phase never advanced.
 *       Either of two recordings shows it, and both are checked against the phaser when the run
 *       is analyzed: a timed wait ({@code awaitAdvanceInterruptibly} with a timeout) expired,
 *       recorded with {@link #recordTimeout(Phaser)}, on a phase that is still current with
 *       parties not arrived; or an {@code arriveAndAwaitAdvance()} recorded as started with
 *       {@link #recordAwaitAdvanceStarted(Phaser)} never returned, its phase is still current with
 *       parties not arrived, and its thread is still parked inside the phaser (#602).</li>
 * </ul>
 *
 * <p>Not findings (#587):
 * <ul>
 *   <li>termination, which is how a phaser ends: {@code arriveAndDeregister} to zero,
 *       {@code forceTermination}, or {@code onAdvance} returning {@code true};</li>
 *   <li>an arrival after termination while parties are still registered, which only
 *       {@code forceTermination} or {@code onAdvance} produce, and which tells the party that the
 *       protocol ended;</li>
 *   <li>a timed wait whose phase advanced afterwards: the wait was shorter than its partner.</li>
 * </ul>
 * Too many arrivals in one phase need no detector: the phaser throws
 * {@link IllegalStateException} into the body.
 *
 * <p>Boundaries. Do not pass {@code awaitAdvance(int)}'s result to {@code recordArrival}: a
 * non-party waiting for termination with it sees a negative phase in correct code. A caller that
 * checks the negative phase from {@code register()} and backs out is still reported, because a
 * phaser that ended while a party was still on its way to join is the short count itself. A phase
 * that stalls with neither recording on it is not seen; the run's own timeout reports the parties
 * it strands. A wait is judged on the phase current when {@code recordAwaitAdvanceStarted} ran, so
 * a phase that advances between that call and the thread's arrival leaves the later wait unjudged
 * (silent). Registrations left behind with nobody waiting (a body that registers per round and
 * only arrives) are not a stall: no party is blocked on them.
 */
public class PhaserDetector {

    private final Map<Phaser, PhaserInfo> phaserRegistry = new ConcurrentHashMap<>();
    /** The phases a timed wait gave up on, per phaser. */
    private final Map<Phaser, Set<Integer>> timedOutPhases = new ConcurrentHashMap<>();
    /** Phasers a party arrived at, or registered with, after every registered party had left. */
    private final Set<Phaser> lateArrivals = ConcurrentHashMap.newKeySet();
    /** Phasers the body recorded as terminated: context for the report, never a finding. */
    private final Set<Phaser> terminatedPhasers = ConcurrentHashMap.newKeySet();
    /**
     * Waits in {@code arriveAndAwaitAdvance()} that were started and not yet recorded as returned:
     * per phaser, the waiting thread and the phase current when it started.
     */
    private final Map<Phaser, Map<Thread, Integer>> openWaits = new ConcurrentHashMap<>();

    /**
     * Register a Phaser for monitoring.
     *
     * @param phaser the phaser being recorded, tracked by identity
     * @param name a label identifying the phaser in the report
     * @param parties the number of parties the phaser was created for
     */
    public void registerPhaser(Phaser phaser, String name, int parties) {
        if (phaser == null) return;
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        phaserRegistry.putIfAbsent(phaser, new PhaserInfo(name, parties));
    }

    /**
     * Record an arrival whose returned phase was not kept. It is counted for the report, but it
     * cannot show an arrival after termination; prefer {@link #recordArrival(Phaser, int)}.
     *
     * @param phaser the phaser being recorded, tracked by identity
     */
    public void recordArrive(Phaser phaser) {
        if (phaser == null) return;
        PhaserInfo info = phaserRegistry.get(phaser);
        if (info != null) {
            info.arrive();
        }
    }

    /**
     * Record the phase a {@code register()}, {@code bulkRegister(int)}, {@code arrive()},
     * {@code arriveAndDeregister()} or {@code arriveAndAwaitAdvance()} call returned. A negative
     * phase on a phaser whose registered parties had all deregistered is an arrival after the
     * parties ran out, and is reported. Do not record {@code awaitAdvance(int)} here.
     *
     * @param phaser the phaser the call was made on, tracked by identity
     * @param arrivalPhase the phase the call returned; negative when the phaser had terminated
     * @since 1.12.1
     */
    public void recordArrival(Phaser phaser, int arrivalPhase) {
        if (phaser == null) return;
        recordArrive(phaser);
        // A terminated phaser's counts are frozen: register and arrive return without changing
        // them, so this read cannot race a deregistration. Natural termination leaves no party
        // registered; forceTermination and onAdvance leave the count as it was.
        if (arrivalPhase < 0 && phaser.getRegisteredParties() == 0) {
            lateArrivals.add(phaser);
        }
    }

    /**
     * Record a thread arriving and awaiting advance, without the phase the call returned.
     * Counted for the report; prefer {@link #recordArrival(Phaser, int)}.
     *
     * @param phaser the phaser being recorded, tracked by identity
     */
    public void recordArriveAwaitAdvance(Phaser phaser) {
        recordArrive(phaser);
    }

    /**
     * Record that the calling thread is about to block in {@code arriveAndAwaitAdvance()}. Call it
     * immediately before the call, and {@link #recordAwaitAdvanceReturned(Phaser, int)} right after
     * it returns. A wait that never returned is a stalled phase when, at analysis, the phase it
     * started in is still current with parties not arrived and the thread is still parked inside
     * the phaser: a registered party never arrived, and nothing else would have released it.
     *
     * @param phaser the phaser the thread is about to wait on, tracked by identity
     * @since 1.12.1
     */
    public void recordAwaitAdvanceStarted(Phaser phaser) {
        if (phaser == null) return;
        int phase = phaser.getPhase();
        if (phase < 0) {
            return;   // terminated: the call returns at once with a negative phase
        }
        // A thread waits in one call at a time, so a new start replaces the thread's last one.
        openWaits.computeIfAbsent(phaser, p -> new ConcurrentHashMap<>())
                .put(Thread.currentThread(), phase);
    }

    /**
     * Record that the calling thread's {@code arriveAndAwaitAdvance()} returned, with the phase it
     * returned. Closes the wait {@link #recordAwaitAdvanceStarted(Phaser)} opened, and is also an
     * arrival: the phase is judged as {@link #recordArrival(Phaser, int)} judges it.
     *
     * @param phaser the phaser the thread waited on, tracked by identity
     * @param returnedPhase the phase the call returned; negative when the phaser had terminated
     * @since 1.12.1
     */
    public void recordAwaitAdvanceReturned(Phaser phaser, int returnedPhase) {
        if (phaser == null) return;
        Map<Thread, Integer> waits = openWaits.get(phaser);
        if (waits != null) {
            waits.remove(Thread.currentThread());
        }
        recordArrival(phaser, returnedPhase);
    }

    /**
     * Record a timed wait on the phaser that expired. Call it right after the timeout; the
     * phaser's current phase is read here, and the timeout becomes a finding only if that phase
     * is still current, with parties not arrived, when the run is analyzed.
     *
     * @param phaser the phaser being recorded, tracked by identity
     */
    public void recordTimeout(Phaser phaser) {
        if (phaser == null) return;
        int phase = phaser.getPhase();
        if (phase < 0) {
            return;   // terminated: nothing is left to advance
        }
        timedOutPhases.computeIfAbsent(phaser, p -> ConcurrentHashMap.newKeySet()).add(phase);
    }

    /**
     * Record a phaser that was terminated. Context for the report only: termination is how a
     * phaser ends, and an arrival after it is decided by {@link #recordArrival(Phaser, int)}.
     *
     * @param phaser the phaser being recorded, tracked by identity
     */
    public void recordTermination(Phaser phaser) {
        if (phaser == null) return;
        terminatedPhasers.add(phaser);
    }

    /**
     * Record a completed phase, counted for the report.
     *
     * @param phaser the phaser being recorded, tracked by identity
     * @param phase the phase number that completed
     */
    public void recordPhaseComplete(Phaser phaser, int phase) {
        if (phaser == null) return;
        PhaserInfo info = phaserRegistry.get(phaser);
        if (info != null) {
            info.phaseComplete(phase);
        }
    }

    /**
     * Analyze phaser usage and return report. Stalls are decided here, on each phaser's state at
     * this moment.
     *
     * @return the findings this detector collected during the run
     */
    public PhaserReport analyze() {
        Map<Phaser, String> stalled = new LinkedHashMap<>();
        Set<Phaser> advancedAfterTimeout = new HashSet<>();
        for (Map.Entry<Phaser, Set<Integer>> entry : timedOutPhases.entrySet()) {
            Phaser phaser = entry.getKey();
            int current = phaser.getPhase();
            int unarrived = phaser.getUnarrivedParties();
            if (current >= 0 && unarrived > 0 && entry.getValue().contains(current)) {
                stalled.put(phaser, "phase " + current + " never advanced after a timed wait expired "
                        + "on it; " + unarrived + " of " + phaser.getRegisteredParties()
                        + " registered parties had not arrived");
            } else {
                advancedAfterTimeout.add(phaser);
            }
        }
        for (Map.Entry<Phaser, Map<Thread, Integer>> entry : openWaits.entrySet()) {
            Phaser phaser = entry.getKey();
            String stall = unreturnedWaitStall(phaser, entry.getValue());
            if (stall != null) {
                stalled.putIfAbsent(phaser, stall);
            }
        }
        return new PhaserReport(phaserRegistry, stalled, lateArrivals, terminatedPhasers,
                advancedAfterTimeout);
    }

    /**
     * Decides whether a wait that was started and never recorded as returned is still stuck.
     * Three things must hold, each observed rather than declared: the phase the wait started in is
     * still the phaser's current phase, a registered party has not arrived in it, and the thread is
     * still parked inside {@link Phaser}. The last one separates a real wait from a start whose
     * call threw (an arrival with no party registered) and from a pooled thread that went on to
     * other work or back to its queue; neither is waiting on the phaser.
     *
     * @return the stall description, or {@code null} when no open wait is stuck
     */
    private static @Nullable String unreturnedWaitStall(Phaser phaser, Map<Thread, Integer> waits) {
        int current = phaser.getPhase();
        int unarrived = phaser.getUnarrivedParties();
        if (current < 0 || unarrived <= 0) {
            return null;
        }
        int stuck = 0;
        for (Map.Entry<Thread, Integer> wait : waits.entrySet()) {
            if (wait.getValue() == current && parkedInPhaser(wait.getKey())) {
                stuck++;
            }
        }
        if (stuck == 0) {
            return null;
        }
        return "phase " + current + " never advanced: " + stuck
                + (stuck == 1 ? " party waiting" : " parties waiting")
                + " in arriveAndAwaitAdvance() never returned; " + unarrived + " of "
                + phaser.getRegisteredParties() + " registered parties had not arrived";
    }

    private static boolean parkedInPhaser(Thread thread) {
        if (!thread.isAlive()) {
            return false;
        }
        // Read at analysis only, and only for a wait whose phase is still current, so the stack
        // walk is paid for the rare stuck candidate, never on the recording path.
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (Phaser.class.getName().equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Report class for Phaser analysis.
     */
    public static class PhaserReport {
        private final Map<Phaser, PhaserInfo> phaserRegistry;
        private final Map<Phaser, String> stalledPhasers;
        private final Set<Phaser> lateArrivals;
        private final Set<Phaser> terminatedPhasers;
        private final Set<Phaser> advancedAfterTimeout;

        /**
         * Creates a PhaserReport.
         *
         * @param phaserRegistry every registered phaser and what was observed on it
         * @param timedOutPhasers the phasers to report as stalled
         * @param terminatedPhasers the phasers that reached termination, shown as context only
         */
        public PhaserReport(
            Map<Phaser, PhaserInfo> phaserRegistry,
            Set<Phaser> timedOutPhasers,
            Set<Phaser> terminatedPhasers
        ) {
            this(phaserRegistry, stalledWithoutDetail(timedOutPhasers), Set.of(), terminatedPhasers,
                    Set.of());
        }

        PhaserReport(
            Map<Phaser, PhaserInfo> phaserRegistry,
            Map<Phaser, String> stalledPhasers,
            Set<Phaser> lateArrivals,
            Set<Phaser> terminatedPhasers,
            Set<Phaser> advancedAfterTimeout
        ) {
            this.phaserRegistry = Collections.unmodifiableMap(new HashMap<>(phaserRegistry));
            this.stalledPhasers = Collections.unmodifiableMap(new LinkedHashMap<>(stalledPhasers));
            this.lateArrivals = Collections.unmodifiableSet(new HashSet<>(lateArrivals));
            this.terminatedPhasers = Collections.unmodifiableSet(new HashSet<>(terminatedPhasers));
            this.advancedAfterTimeout = Collections.unmodifiableSet(new HashSet<>(advancedAfterTimeout));
        }

        private static Map<Phaser, String> stalledWithoutDetail(Set<Phaser> phasers) {
            Map<Phaser, String> stalled = new LinkedHashMap<>();
            for (Phaser phaser : phasers) {
                stalled.put(phaser, "a timed wait expired and the phase never advanced");
            }
            return stalled;
        }

        /**
         * {@return whether a party arrived after the parties ran out, or a timed-out phase stalled}
         */
        public boolean hasIssues() {
            return !lateArrivals.isEmpty() || !stalledPhasers.isEmpty();
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

            if (!lateArrivals.isEmpty()) {
                sb.append("  Arrivals After The Parties Ran Out:\n");
                for (Phaser phaser : lateArrivals) {
                    PhaserInfo info = infoFor(phaser);
                    sb.append("    - ").append(info.name)
                      .append(" (created for ").append(info.parties).append(" parties; ")
                      .append(info.describeActivity())
                      .append("; a register or arrive returned a negative phase with no party left registered)\n");
                }
                sb.append("  Why: A root Phaser terminates when its registered party count reaches zero. A party that registers or arrives\n");
                sb.append("       after that gets a negative phase back instead of waiting for anyone, so the work it meant to coordinate runs alone.\n");
                sb.append("  Fix: Register one party per participant before any of them can deregister (new Phaser(n), bulkRegister, or\n");
                sb.append("       register() before starting the task), and deregister each party exactly once\n");
            }

            if (!stalledPhasers.isEmpty()) {
                sb.append("  Stalled Phases:\n");
                for (Map.Entry<Phaser, String> entry : stalledPhasers.entrySet()) {
                    PhaserInfo info = infoFor(entry.getKey());
                    sb.append("    - ").append(info.name)
                      .append(" (").append(entry.getValue()).append("; ")
                      .append(info.describeActivity()).append(")\n");
                }
                sb.append("  Why: A Phaser advances only when all registered parties arrive. A party that never calls arrive() or\n");
                sb.append("       arriveAndAwaitAdvance() leaves every other party blocked at the phase boundary.\n");
                sb.append("  Fix: Make every registered party arrive on every path (try/finally), or deregister it with arriveAndDeregister()\n");
            }

            if (!terminatedPhasers.isEmpty() || !advancedAfterTimeout.isEmpty()) {
                sb.append("  Note, not a finding (termination is how a phaser ends; a timed wait whose phase advanced later was shorter than its partner):\n");
                for (Phaser phaser : terminatedPhasers) {
                    sb.append("    - ").append(infoFor(phaser).name).append(": terminated\n");
                }
                for (Phaser phaser : advancedAfterTimeout) {
                    sb.append("    - ").append(infoFor(phaser).name)
                      .append(": a timed wait expired, and the phase advanced or the phaser ended afterwards\n");
                }
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
        private int arrivals;
        private int completions;
        private int lastCompletedPhase = -1;

        PhaserInfo(String name, int parties) {
            this.name = name;
            this.parties = parties;
        }

        synchronized void arrive() {
            arrivals++;
        }

        synchronized void phaseComplete(int phase) {
            completions++;
            lastCompletedPhase = Math.max(lastCompletedPhase, phase);
        }

        synchronized String describeActivity() {
            String text = arrivals + (arrivals == 1 ? " arrival" : " arrivals") + " recorded, "
                    + completions + (completions == 1 ? " phase completion" : " phase completions");
            return lastCompletedPhase >= 0 ? text + " (last phase " + lastCompletedPhase + ")" : text;
        }
    }
}
