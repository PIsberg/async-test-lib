package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;

/**
 * Detects reuse of a broken CyclicBarrier: an arrival or await on a barrier whose
 * {@code isBroken()} is true at that moment, so the await throws {@code BrokenBarrierException} for
 * every caller until somebody calls {@code reset()}.
 *
 * <p>A recorded timeout is not a finding (#595). A timed-out {@code await(timeout, unit)} breaks the
 * barrier for every party, and what the caller does next decides whether that is a defect: catching
 * the {@code TimeoutException} and calling {@code reset()}, or backing off and dropping the barrier,
 * is correct, and a recording call cannot tell that from code that goes on to use the barrier. The
 * consequence that fails is the next await on the still-broken barrier, which is the reuse finding,
 * decided on the barrier itself. {@link #recordTimeout} is kept as context, so that report can say
 * what broke the barrier.
 *
 * <p>A broken barrier is not a finding by itself (#584). Breaking one is how its parties are
 * cancelled: {@code reset()} with parties waiting, or interrupting them, and then discarding the
 * barrier is correct code. What fails is the next await on it, so that is what is reported, and it
 * is decided by asking the barrier rather than by what the body recorded. {@link #recordBroken}
 * and {@link #recordReset} are kept as context for that report; a recorded break on a barrier that
 * is not broken at the await reports nothing, and a break nobody recorded is still seen.
 *
 * <p>The decision is taken when the arrival or await is recorded, so a barrier that breaks after
 * that check and before the await itself is missed. That errs towards silence.
 *
 * <p>A barrier left a party short is the second finding (#631): a thread that recorded an arrival or
 * await on it is still parked in an untimed {@code await()} when the runner times the round out, or
 * when the run is analyzed, and fewer threads than the barrier's parties are parked there. It is
 * decided from the recording threads' own state and stack, never by asking the barrier:
 * {@code getNumberWaiting()} and {@code isBroken()} take the barrier's lock, a barrier action runs
 * holding that lock, and a round stuck in its action would leave the runner stuck in the probe
 * instead of failing the round. What that costs:
 * <ul>
 *   <li>A thread that recorded nothing is not seen, so neither is a waiter the body did not report.</li>
 *   <li>A timed {@code await(timeout, unit)} is not stranded, because its timeout ends it and breaks
 *       the barrier for every party; a barrier with one is not reported.</li>
 *   <li>A barrier with a recorded thread inside {@code await()} but not parked in it, for example the
 *       thread running a blocked barrier action, is not reported: its parties did arrive.</li>
 *   <li>A thread is attributed to the barrier it last recorded. One that records barrier A and then
 *       awaits barrier B without recording is counted against A.</li>
 * </ul>
 */
public class CyclicBarrierDetector {

    private final Map<CyclicBarrier, BarrierInfo> barrierRegistry = new ConcurrentHashMap<>();
    /** Barriers with at least one recorded timeout; context for the reuse report, never a finding. */
    private final Set<CyclicBarrier> timedOutBarriers = ConcurrentHashMap.newKeySet();
    /** Barriers with at least one recorded break; context for the reuse report, never a finding. */
    private final Set<CyclicBarrier> brokenBarriers = ConcurrentHashMap.newKeySet();
    private final Set<CyclicBarrier> reuseAfterBrokenBarriers = ConcurrentHashMap.newKeySet();
    private final Map<CyclicBarrier, Integer> strandedBarriers = new ConcurrentHashMap<>();
    /**
     * The barrier each recording thread last said it was about to await. Read only when a round is
     * judged; a replaced value allocates nothing, so a worker recording every round costs one entry.
     */
    private final Map<Thread, CyclicBarrier> recordedWaiters = new ConcurrentHashMap<>();

    private static final String UNREGISTERED = "<unregistered barrier>";
    private static final String CONDITION_OBJECT =
        "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject";

    /**
     * Register a CyclicBarrier for monitoring.
     *
     * @param barrier the barrier being recorded, tracked by identity
     * @param name a label identifying the barrier in the report
     * @param parties the number of parties the barrier was created for
     */
    public void registerBarrier(CyclicBarrier barrier, String name, int parties) {
        if (barrier == null) return;
        int resolvedParties = parties > 0 ? parties : barrier.getParties();
        barrierRegistry.compute(barrier, (k, existing) -> {
            if (existing == null || UNREGISTERED.equals(existing.name)) {
                return new BarrierInfo(name, resolvedParties);
            }
            return existing;
        });
    }

    /**
     * Register a CyclicBarrier for monitoring with parties inferred from the barrier.
     *
     * @param barrier the barrier being recorded, tracked by identity
     * @param name a label identifying the barrier in the report
     * @since 1.12.1
     */
    public void registerBarrier(CyclicBarrier barrier, String name) {
        if (barrier == null) return;
        registerBarrier(barrier, name, barrier.getParties());
    }

    private void autoRegister(CyclicBarrier barrier) {
        barrierRegistry.computeIfAbsent(barrier, b -> new BarrierInfo(UNREGISTERED, b.getParties()));
        recordedWaiters.put(Thread.currentThread(), barrier);
    }

    /**
     * Record a thread arriving at the barrier, about to await it. An arrival at a barrier that is
     * broken at this moment is reported as reuse of a broken barrier, the same as
     * {@link #recordAwait}.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordArrival(CyclicBarrier barrier) {
        if (barrier == null) return;
        autoRegister(barrier);
        checkBroken(barrier);
    }

    /**
     * Record a barrier {@code await(timeout, unit)} that timed out. This is context for a later
     * reuse finding, not a finding (#595): the timeout broke the barrier, and a caller that handles
     * the {@code TimeoutException} with {@code reset()}, or backs off and drops the barrier, is
     * correct. An arrival or await while the barrier is still broken is reported, and that report
     * then names the timeout.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordTimeout(CyclicBarrier barrier) {
        if (barrier == null) return;
        timedOutBarriers.add(barrier);
    }

    /**
     * Record that the barrier broke, for example a party leaving with
     * {@code BrokenBarrierException}. This is context for a later reuse finding, not a finding:
     * a barrier broken to cancel its parties and then discarded is correct.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordBroken(CyclicBarrier barrier) {
        if (barrier == null) return;
        brokenBarriers.add(barrier);
    }

    /**
     * Record a call to {@code reset()}; call it just before the reset, while any waiting parties are
     * still waiting. A reset with parties waiting breaks the barrier for each of them, so when
     * {@code getNumberWaiting()} is non-zero it counts as a recorded break. It never clears
     * anything: whether a later await is on a broken barrier is asked of the barrier at that
     * await, and after a completed reset it is not.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordReset(CyclicBarrier barrier) {
        if (barrier == null) return;
        if (barrier.getNumberWaiting() > 0) {
            brokenBarriers.add(barrier);
        }
    }

    /**
     * Record a thread about to call await() on the barrier. If the barrier is broken at this
     * moment, the await throws {@code BrokenBarrierException} immediately, and this is reported
     * as reuse of a broken barrier without an intervening reset().
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordAwait(CyclicBarrier barrier) {
        if (barrier == null) return;
        autoRegister(barrier);
        checkBroken(barrier);
    }

    private void checkBroken(CyclicBarrier barrier) {
        if (barrier.isBroken()) {
            reuseAfterBrokenBarriers.add(barrier);
        }
    }

    /**
     * Record successful barrier completion. Accepted for source compatibility; it decides nothing,
     * because a completed cycle is not evidence against an await on a barrier broken later.
     *
     * @param barrier the barrier being recorded, tracked by identity
     */
    public void recordBarrierComplete(CyclicBarrier barrier) {
        // Nothing to record: see the javadoc.
    }

    /**
     * Tells the detector the runner has timed the current round out and is about to interrupt its
     * workers. Called by the runner, not by test bodies.
     *
     * <p>At this moment the workers parked on untimed {@code await()} calls have not been interrupted
     * yet, so a barrier left a party short is still visible; once the runner cancels them, the
     * interrupt breaks the barrier and the evidence is gone. The barrier itself is never asked: its
     * lock may be held by a barrier action that is the reason the round is stuck, and this runs on
     * the runner thread before it cancels anything.
     *
     * @since 1.12.1
     */
    public void markRoundTimedOut() {
        collectStranded(true);
    }

    /**
     * Analyze barrier usage and return report. A barrier a recording thread is still parked on,
     * untimed and a party short, is reported here as well, for a body whose waiters outlive it
     * without the round timing out.
     *
     * @return the findings this detector collected during the run
     */
    public CyclicBarrierReport analyze() {
        collectStranded(false);
        return new CyclicBarrierReport(
            barrierRegistry,
            timedOutBarriers,
            brokenBarriers,
            reuseAfterBrokenBarriers,
            strandedBarriers
        );
    }

    /**
     * Counts, per barrier, the recording threads parked in an untimed await on it, without taking
     * the barrier's lock. A barrier with a recorded thread inside await() in any other way (timed,
     * running the barrier action, or acquiring the lock) is excused: it is not a party short.
     */
    private void collectStranded(boolean timedOut) {
        Map<CyclicBarrier, Integer> parked = new HashMap<>();
        Set<CyclicBarrier> excused = new HashSet<>();
        for (Map.Entry<Thread, CyclicBarrier> entry : recordedWaiters.entrySet()) {
            CyclicBarrier barrier = entry.getValue();
            AwaitState state = awaitStateOf(entry.getKey());
            if (state == AwaitState.UNTIMED_PARKED) {
                parked.merge(barrier, 1, Integer::sum);
            } else if (state == AwaitState.OTHERWISE_IN_AWAIT) {
                excused.add(barrier);
            }
        }
        for (Map.Entry<CyclicBarrier, Integer> entry : parked.entrySet()) {
            CyclicBarrier barrier = entry.getKey();
            int waiting = entry.getValue();
            if (excused.contains(barrier) || waiting >= barrier.getParties()) {
                continue;
            }
            if (timedOut) {
                strandedBarriers.put(barrier, waiting);
            } else {
                strandedBarriers.putIfAbsent(barrier, waiting);
            }
        }
    }

    private enum AwaitState { UNTIMED_PARKED, OTHERWISE_IN_AWAIT, NOT_IN_AWAIT }

    /**
     * Where a thread is relative to {@code CyclicBarrier.await}, read from its state and stack. An
     * untimed await parks in {@code ConditionObject.await()} directly under
     * {@code CyclicBarrier.dowait}; a timed one parks in {@code awaitNanos}. A stack shaped any other
     * way errs towards silence.
     */
    private static AwaitState awaitStateOf(Thread thread) {
        Thread.State state = thread.getState();
        if (state == Thread.State.NEW || state == Thread.State.TERMINATED) {
            return AwaitState.NOT_IN_AWAIT;
        }
        StackTraceElement[] frames = thread.getStackTrace();
        for (int i = 0; i < frames.length; i++) {
            StackTraceElement frame = frames[i];
            if ("dowait".equals(frame.getMethodName())
                    && CyclicBarrier.class.getName().equals(frame.getClassName())) {
                boolean untimedPark = i > 0
                    && state == Thread.State.WAITING
                    && "await".equals(frames[i - 1].getMethodName())
                    && CONDITION_OBJECT.equals(frames[i - 1].getClassName());
                return untimedPark ? AwaitState.UNTIMED_PARKED : AwaitState.OTHERWISE_IN_AWAIT;
            }
        }
        return AwaitState.NOT_IN_AWAIT;
    }

    /**
     * Report class for CyclicBarrier analysis.
     */
    public static class CyclicBarrierReport {
        private final Map<CyclicBarrier, BarrierInfo> barrierRegistry;
        private final Set<CyclicBarrier> timedOutBarriers;
        private final Set<CyclicBarrier> brokenBarriers;
        private final Set<CyclicBarrier> reuseAfterBrokenBarriers;
        private final Map<CyclicBarrier, Integer> strandedBarriers;

        /**
         * Creates a CyclicBarrierReport.
         *
         * @param barrierRegistry every registered barrier and what was observed on it
         * @param timedOutBarriers the barriers with a recorded timeout; context for the reuse
         *                         report, never a finding on its own
         * @param brokenBarriers the barriers with a recorded break; context for the reuse report,
         *                       never a finding on its own
         * @param reuseAfterBrokenBarriers the barriers arrived at or awaited while broken
         * @param strandedBarriers the barriers left a party short with untimed waiters parked
         * @since 1.12.1
         */
        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers,
            Set<CyclicBarrier> reuseAfterBrokenBarriers,
            Map<CyclicBarrier, Integer> strandedBarriers
        ) {
            this.barrierRegistry = Collections.unmodifiableMap(new HashMap<>(barrierRegistry));
            this.timedOutBarriers = Collections.unmodifiableSet(new HashSet<>(timedOutBarriers));
            this.brokenBarriers = Collections.unmodifiableSet(new HashSet<>(brokenBarriers));
            this.reuseAfterBrokenBarriers = Collections.unmodifiableSet(new HashSet<>(reuseAfterBrokenBarriers));
            this.strandedBarriers = Collections.unmodifiableMap(new HashMap<>(strandedBarriers));
        }

        /**
         * Retained for binary compatibility with 1.7.0.
         *
         * @deprecated since 1.12.1 — use the five-argument constructor; this overload
         *             reports no stranded barriers.
         *
         * @param barrierRegistry every registered barrier and what was observed on it
         * @param timedOutBarriers the barriers with a recorded timeout
         * @param brokenBarriers the barriers with a recorded break
         * @param reuseAfterBrokenBarriers the barriers arrived at or awaited while broken
         */
        @Deprecated(since = "1.12.1")
        @SuppressWarnings("InlineMeSuggester")
        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers,
            Set<CyclicBarrier> reuseAfterBrokenBarriers
        ) {
            this(barrierRegistry, timedOutBarriers, brokenBarriers, reuseAfterBrokenBarriers, Collections.emptyMap());
        }

        /**
         * Legacy constructor retained for binary compatibility with 1.6.0, before
         * reuse-after-broken tracking was added.
         *
         * @deprecated since 1.7.0 — use the five-argument constructor; this overload
         *             reports no reuse-after-broken or stranded barriers.
         *
         * @param barrierRegistry every registered barrier and what was observed on it
         * @param timedOutBarriers the barriers with a recorded timeout; context only, never a finding
         * @param brokenBarriers the barriers with a recorded break; context only, never a finding
         */
        @Deprecated(since = "1.7.0")
        @SuppressWarnings("InlineMeSuggester") // binary-compat shim for 1.6.0 callers, not an active migration target
        public CyclicBarrierReport(
            Map<CyclicBarrier, BarrierInfo> barrierRegistry,
            Set<CyclicBarrier> timedOutBarriers,
            Set<CyclicBarrier> brokenBarriers
        ) {
            this(barrierRegistry, timedOutBarriers, brokenBarriers, Collections.emptySet(), Collections.emptyMap());
        }

        /**
         * {@return the reuse after broken barriers}
         */
        public Set<CyclicBarrier> getReuseAfterBrokenBarriers() {
            return reuseAfterBrokenBarriers;
        }

        /**
         * {@return the barriers left a party short with untimed waiters parked}
         * @since 1.12.1
         */
        public Set<CyclicBarrier> getStrandedBarriers() {
            return strandedBarriers.keySet();
        }

        /**
         * {@return the number of waiting parties observed for a stranded barrier, or 0 if not stranded}
         * @param barrier the barrier to query
         * @since 1.12.1
         */
        public int getWaitingParties(CyclicBarrier barrier) {
            return strandedBarriers.getOrDefault(barrier, 0);
        }

        /**
         * Whether a finding was made: an arrival or await on a barrier that was broken at that
         * moment, or a barrier left a party short with untimed waiters parked. A recorded break alone
         * is not one (#584), and neither is a recorded timeout (#595): both are context for the report.
         *
         * @return whether there are issues
         */
        public boolean hasIssues() {
            return !reuseAfterBrokenBarriers.isEmpty() || !strandedBarriers.isEmpty();
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
            return info != null ? info : new BarrierInfo(UNREGISTERED, barrier.getParties());
        }

        /** The recorded context for a reuse finding: what the body said broke the barrier, if anything. */
        private String whatBrokeIt(CyclicBarrier barrier) {
            if (timedOutBarriers.contains(barrier)) {
                return "; a timeout was recorded earlier, and a timed-out await breaks the barrier for every party";
            }
            if (brokenBarriers.contains(barrier)) {
                return "; a break was recorded earlier, by a party or a reset() with parties waiting";
            }
            return "; the break was not recorded: a party timed out, was interrupted, or the barrier action threw";
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("CYCLICBARRIER ISSUES DETECTED:\n");

            if (!reuseAfterBrokenBarriers.isEmpty()) {
                sb.append("  Reuse After Broken Barriers:\n");
                for (CyclicBarrier barrier : reuseAfterBrokenBarriers) {
                    BarrierInfo info = infoFor(barrier);
                    sb.append("    - ").append(info.name)
                      .append(" (").append(info.parties).append(" parties; arrival or await() while barrier.isBroken() was true")
                      .append(whatBrokeIt(barrier)).append(")\n");
                }
                sb.append("  Why: await() on a broken barrier throws BrokenBarrierException immediately for every caller;\n");
                sb.append("       the barrier stays broken until reset() is called, so repeated reuse without a reset\n");
                sb.append("       keeps failing every participant.\n");
                sb.append("  Fix: Call barrier.reset() after handling BrokenBarrierException, or replace the barrier instance;\n");
                sb.append("       consider Phaser for more flexible recovery when barriers break frequently.\n");
            }

            if (!strandedBarriers.isEmpty()) {
                sb.append("  Stranded Barriers (party short):\n");
                for (Map.Entry<CyclicBarrier, Integer> entry : strandedBarriers.entrySet()) {
                    CyclicBarrier barrier = entry.getKey();
                    int waiting = entry.getValue();
                    BarrierInfo info = infoFor(barrier);
                    int totalParties = info.parties > 0 ? info.parties : barrier.getParties();
                    int shortCount = totalParties - waiting;
                    sb.append("    - ").append(info.name)
                      .append(" (").append(waiting).append(" of ").append(totalParties)
                      .append(" parties waiting; left ").append(shortCount)
                      .append(" party short with untimed waiters parked)\n");
                }
                sb.append("  Why: untimed await() blocks indefinitely when fewer than the required parties arrive;\n");
                sb.append("       the round times out while waiting parties stay parked indefinitely.\n");
                sb.append("  Fix: Ensure all parties arrive before awaiting, use await(timeout, unit) to detect missing parties,\n");
                sb.append("       or consider CountDownLatch / Phaser if the number of parties is dynamic.\n");
            }

            if (!hasIssues()) {
                sb.append("  No CyclicBarrier issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal barrier information: what the body registered. Nothing observed is kept here; the
     * finding is decided on the barrier itself.
     */
    static class BarrierInfo {
        final String name;
        final int parties;

        BarrierInfo(String name, int parties) {
            this.name = name;
            this.parties = parties;
        }
    }
}
