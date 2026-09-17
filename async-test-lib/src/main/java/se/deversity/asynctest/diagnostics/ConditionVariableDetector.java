package se.deversity.asynctest.diagnostics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Detects {@link Condition} waits that no signal accounts for.
 *
 * <p><strong>The model.</strong> Every await is paired with the signals that could have woken it,
 * per condition and per thread:
 * <ul>
 *   <li>a {@code signal()} recorded while at least one waiter has no wakeup owed to it owes one
 *       wakeup; a {@code signalAll()} owes one to every such waiter;</li>
 *   <li>an await that exits as woken ({@code timedOut == false}) settles one owed wakeup. If
 *       none is owed, the await returned with no signal behind it and that is the
 *       <em>missing signal</em> finding;</li>
 *   <li>an await that exits with {@code timedOut == true} is a waiter that stopped waiting. It is
 *       not a finding: a bounded poll that is never signalled by design runs exactly this way;</li>
 *   <li>a thread still inside an await when the run is analysed is a <em>stuck waiter</em>;</li>
 *   <li>an exit recorded on a thread that has no open await is ignored, so the waiter count can
 *       never go negative.</li>
 * </ul>
 * Findings are therefore per wait, not per run: one signal anywhere does not silence a second
 * waiter that nothing woke (#583).
 *
 * <p><strong>Stuck waiters, read from the lock.</strong> Register a condition together with the
 * lock that created it, {@link #registerCondition(ReentrantLock, Condition, String)} or
 * {@link #registerCondition(ReentrantReadWriteLock, Condition, String)}, and a stuck waiter is
 * what the lock reports: at analysis the detector takes the lock with {@code tryLock()} and reads
 * {@code getWaitQueueLength(condition)}, so a thread parked on the condition is reported whether
 * or not its await was recorded, and a recorded await with no thread parked behind it (the body
 * recorded it and then threw, or found its predicate true and never called {@code await()}) is a
 * note, not a finding (#592). A lock held by another thread at analysis, or a lock that did not
 * create the condition, cannot be read; nothing is reported from it and the report says so. With
 * a condition registered without its lock, a stuck waiter is still a recorded await with no
 * recorded exit.
 *
 * <p>The lock is read once the round's workers have quiesced. The runner interrupts a worker that
 * outlives the round timeout before analysis, and an interrupted await leaves the condition's
 * queue, so a worker parked on the condition until the timeout is not in the lock's count: its
 * round fails with the timeout instead. A waiter the body started on a thread of its own, still
 * parked when the run ends, is.
 *
 * <p><strong>Rounds.</strong> A platform worker is reused by the next invocation round, and a
 * thread cannot be inside two awaits at once. An await a thread recorded in an earlier round and
 * never exited, followed by an await from the same thread in a later round, is an abandoned wait:
 * it is counted as a stuck waiter from that earlier round (a note when the lock is registered, as
 * above), and the new await starts with no wakeup owed to it (#593). A second await from the same
 * thread within one round is the same wait continuing (a {@code while} loop that records its exit
 * once, after the loop), and a waiter that stays parked across a round boundary is not abandoned
 * by the boundary alone.
 *
 * <p><strong>What is not a finding.</strong> A signal made while nobody waits is how correct
 * code runs whenever the producer gets there first: the consumer tests its predicate before it
 * awaits and never waits at all. The count is shown in the report as a note and does not decide
 * {@link ConditionVariableReport#hasIssues()}. Telling a stuck waiter from an idle consumer needs
 * the predicate, which the {@code registerCondition(lock, condition, ready, name)} overloads pass
 * in (#643): a thread parked while {@code ready} is false is an idle consumer and only a note.
 *
 * <p><strong>Recording contract.</strong> Record a signal while holding the condition's lock,
 * before or right after calling {@code signal()}, so no waiter can record its exit first. Record
 * an exit only for the thread that recorded the await, with {@code timedOut} set from the timed
 * await's return value ({@code !condition.await(t, unit)}, or {@code awaitNanos(...) <= 0}).
 * A spurious wakeup recorded as woken reads as a missing signal; the JDK permits them, so
 * record the exit only once the loop's predicate check is done, or treat such a finding as a
 * prompt rather than a verdict.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectConditionVariableIssues = true)
 * void consumerAndProducer() throws InterruptedException {
 *     var monitor = AsyncTestContext.conditionVariableDetector();
 *     monitor.registerCondition(lock, ready, "data-ready");   // lock: the ReentrantLock that made it
 *
 *     lock.lock();
 *     try {
 *         while (!dataReady) {                       // consumer
 *             monitor.recordAwait(ready, "data-ready");
 *             boolean signalled = ready.await(50, TimeUnit.MILLISECONDS);
 *             monitor.recordAwaitExit(ready, "data-ready", !signalled);
 *             if (!signalled) {
 *                 return;                            // gave up: not a finding
 *             }
 *         }
 *     } finally {
 *         lock.unlock();
 *     }
 * }
 *
 * // Producer, elsewhere, under the same lock:
 * //   dataReady = true;
 * //   monitor.recordSignal(ready, "data-ready", true);
 * //   ready.signalAll();
 * }</pre>
 */
public class ConditionVariableDetector {

    /** {@code waitQueue} result: the lock was held by another thread, so it was not read. */
    private static final int LOCK_HELD = -1;
    /** {@code waitQueue} result: the registered lock did not create the condition. */
    private static final int NOT_OWNED = -2;

    /** Query result for a condition's wait queue and its waiter predicate. */
    private static final class WaitQueueResult {
        final int parked;
        final boolean hasPredicate;
        final boolean predicateSatisfied;
        /** What the predicate threw when evaluated, or {@code null} when it returned. */
        final @Nullable String predicateFailure;

        WaitQueueResult(int parked, boolean hasPredicate, boolean predicateSatisfied) {
            this(parked, hasPredicate, predicateSatisfied, null);
        }

        WaitQueueResult(int parked, boolean hasPredicate, boolean predicateSatisfied,
                        @Nullable String predicateFailure) {
            this.parked = parked;
            this.hasPredicate = hasPredicate;
            this.predicateSatisfied = predicateSatisfied;
            this.predicateFailure = predicateFailure;
        }
    }

    @FunctionalInterface
    private interface WaitQueueQuery {
        WaitQueueResult query();
    }

    /** Everything recorded about one condition; every field is guarded by the state's monitor. */
    private static final class ConditionState {
        final String name;
        /**
         * Threads currently inside a recorded await on this condition, each with the invocation
         * epoch its await began in.
         */
        final Map<Long, Long> openAwaits = new HashMap<>();
        /** Wakeups owed by signals to current waiters and not yet settled by an exit. */
        int owedWakeups;
        int awaitCount;
        int signalCount;
        int signalAllCount;
        int signalsWithNoWaiter;
        int timedOutAwaits;
        int unsignalledWakeups;
        /** Awaits from an earlier round that the same thread replaced with a new one (#593). */
        int abandonedAwaits;
        final Set<Long> signallingThreads = new HashSet<>();
        long lastSignalNanos;
        boolean signalled;
        /**
         * Reads the owning lock's wait queue length for this condition, or {@link #LOCK_HELD} /
         * {@link #NOT_OWNED}; {@code null} when the condition was registered without its lock.
         */
        @Nullable WaitQueueQuery waitQueue;
        boolean hasPredicate;

        ConditionState(Condition condition, @Nullable String name) {
            this.name = name != null ? name : "condition@" + System.identityHashCode(condition);
        }
    }

    private final Map<IdentityKey, ConditionState> conditions = new ConcurrentHashMap<>();
    /** Bumped at the start of every invocation round; read on the recording threads. */
    private final AtomicLong invocationEpoch = new AtomicLong();
    private volatile boolean enabled = true;

    /**
     * Register a Condition for monitoring. A stuck waiter on a condition registered this way is a
     * recorded await with no recorded exit; register the owning lock as well to have the lock
     * decide it.
     *
     * @param condition the Condition to monitor
     * @param name a descriptive name for reporting
     */
    public void registerCondition(Condition condition, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        stateFor(condition, name);
    }

    /**
     * Register a Condition together with the {@link ReentrantLock} that created it, so a stuck
     * waiter is read from the lock's wait queue at analysis rather than from the recorded awaits.
     * Registering the lock after a plain {@link #registerCondition(Condition, String)} keeps what
     * was already recorded.
     *
     * @param lock the lock whose {@code newCondition()} made {@code condition}; {@code null}
     *             registers the condition without a lock
     * @param condition the Condition to monitor
     * @param name a descriptive name for reporting
     * @since 1.12.1
     */
    public void registerCondition(@Nullable ReentrantLock lock, Condition condition, String name) {
        registerCondition(lock, condition, null, name);
    }

    /**
     * Register a Condition made by a {@link ReentrantReadWriteLock}'s write lock, so a stuck waiter
     * is read from the lock's wait queue at analysis rather than from the recorded awaits.
     *
     * @param lock the read-write lock whose {@code writeLock().newCondition()} made
     *             {@code condition}; {@code null} registers the condition without a lock
     * @param condition the Condition to monitor
     * @param name a descriptive name for reporting
     * @since 1.12.1
     */
    public void registerCondition(@Nullable ReentrantReadWriteLock lock, Condition condition, String name) {
        registerCondition(lock, condition, null, name);
    }

    /**
     * Register a Condition together with the {@link ReentrantLock} that created it and the waiter's
     * state predicate ({@code ready}), so a stuck waiter is confirmed only when the lock shows a thread
     * parked on the condition at analysis <em>while</em> {@code ready.getAsBoolean()} already holds.
     *
     * <p>A thread parked on the condition while {@code ready} is false is an idle consumer waiting
     * for work and is noted in the report as context without failing the run (#643).
     *
     * @param lock the lock whose {@code newCondition()} made {@code condition}; {@code null}
     *             registers the condition without a lock
     * @param condition the Condition to monitor
     * @param ready supplier evaluated under the lock at analysis; returns {@code true} when the
     *              condition the waiter waits for is already satisfied. A supplier that throws
     *              leaves the parked threads unconfirmed, with the exception in the report. It is
     *              not evaluated when {@code lock} is {@code null}, since the lock is what shows
     *              a thread parked
     * @param name a descriptive name for reporting
     * @since 1.12.1
     */
    public void registerCondition(@Nullable ReentrantLock lock, Condition condition,
                                  @Nullable BooleanSupplier ready, String name) {
        ConditionState state = stateFor(condition, name);
        if (state != null && lock != null) {
            attachWaitQueue(state, waitQueueOf(lock, condition, ready), ready != null);
        }
    }

    /**
     * Register a Condition made by a {@link ReentrantReadWriteLock}'s write lock together with the
     * waiter's state predicate ({@code ready}), so a stuck waiter is confirmed only when the lock
     * shows a thread parked on the condition at analysis while {@code ready.getAsBoolean()} already holds.
     *
     * <p>A thread parked on the condition while {@code ready} is false is an idle consumer waiting
     * for work and is noted in the report as context without failing the run (#643).
     *
     * @param lock the read-write lock whose {@code writeLock().newCondition()} made
     *             {@code condition}; {@code null} registers the condition without a lock
     * @param condition the Condition to monitor
     * @param ready supplier evaluated under the lock at analysis; returns {@code true} when the
     *              condition the waiter waits for is already satisfied. A supplier that throws
     *              leaves the parked threads unconfirmed, with the exception in the report. It is
     *              not evaluated when {@code lock} is {@code null}, since the lock is what shows
     *              a thread parked
     * @param name a descriptive name for reporting
     * @since 1.12.1
     */
    public void registerCondition(@Nullable ReentrantReadWriteLock lock, Condition condition,
                                  @Nullable BooleanSupplier ready, String name) {
        ConditionState state = stateFor(condition, name);
        if (state != null && lock != null) {
            attachWaitQueue(state, waitQueueOf(lock, condition, ready), ready != null);
        }
    }

    /** Reads {@code lock}'s wait queue for {@code condition} and evaluates {@code ready} without blocking. */
    private static WaitQueueQuery waitQueueOf(ReentrantLock lock, Condition condition,
                                              @Nullable BooleanSupplier ready) {
        return () -> {
            if (!lock.tryLock()) {
                return new WaitQueueResult(LOCK_HELD, ready != null, false);
            }
            try {
                int parked = lock.getWaitQueueLength(condition);
                boolean satisfied = false;
                String failure = null;
                if (parked > 0 && ready != null) {
                    try {
                        satisfied = ready.getAsBoolean();
                    } catch (RuntimeException | Error thrown) {
                        // Neither satisfied nor idle: reported as unconfirmed, never silently idle.
                        failure = thrown.toString();
                    }
                }
                return new WaitQueueResult(parked, ready != null, ready == null || satisfied, failure);
            } catch (IllegalArgumentException notThisLocksCondition) {
                return new WaitQueueResult(NOT_OWNED, ready != null, false);
            } finally {
                lock.unlock();
            }
        };
    }

    /** Reads the write lock's wait queue for {@code condition} and evaluates {@code ready} without blocking. */
    private static WaitQueueQuery waitQueueOf(ReentrantReadWriteLock lock, Condition condition,
                                              @Nullable BooleanSupplier ready) {
        return () -> {
            if (!lock.writeLock().tryLock()) {
                return new WaitQueueResult(LOCK_HELD, ready != null, false);
            }
            try {
                int parked = lock.getWaitQueueLength(condition);
                boolean satisfied = false;
                String failure = null;
                if (parked > 0 && ready != null) {
                    try {
                        satisfied = ready.getAsBoolean();
                    } catch (RuntimeException | Error thrown) {
                        // Neither satisfied nor idle: reported as unconfirmed, never silently idle.
                        failure = thrown.toString();
                    }
                }
                return new WaitQueueResult(parked, ready != null, ready == null || satisfied, failure);
            } catch (IllegalArgumentException notThisLocksCondition) {
                return new WaitQueueResult(NOT_OWNED, ready != null, false);
            } finally {
                lock.writeLock().unlock();
            }
        };
    }

    private @Nullable ConditionState stateFor(@Nullable Condition condition, @Nullable String name) {
        if (!enabled || condition == null) {
            return null;
        }
        ConditionState fresh = new ConditionState(condition, name);
        ConditionState prior = conditions.putIfAbsent(new IdentityKey(condition), fresh);
        return prior != null ? prior : fresh;
    }

    private static void attachWaitQueue(ConditionState state, WaitQueueQuery waitQueue, boolean hasPredicate) {
        synchronized (state) {
            if (state.waitQueue == null || (!state.hasPredicate && hasPredicate)) {
                state.waitQueue = waitQueue;
                state.hasPredicate = hasPredicate;
            }
        }
    }

    /**
     * Internal: called at the start of each invocation round, so an await a pooled worker left
     * open in an earlier round is not merged into its await in this one (#593). Open awaits are
     * kept: a waiter may legitimately stay parked across the boundary.
     *
     * @since 1.12.1
     */
    public void markInvocationStart() {
        invocationEpoch.incrementAndGet();
    }

    /**
     * Record an await() call, made by the thread that is about to wait.
     *
     * @param condition the condition being awaited, tracked by identity
     * @param name the condition name (should match registration)
     */
    public void recordAwait(Condition condition, String name) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        long epoch = invocationEpoch.get();
        synchronized (state) {
            Long openedIn = state.openAwaits.put(thread, epoch);
            if (openedIn == null) {
                state.awaitCount++;
            } else if (openedIn.longValue() != epoch) {
                // A thread cannot be inside two awaits. Its open one began in an earlier round
                // whose body ended without recording the exit: that wait was abandoned, and this
                // await starts with no wakeup owed to it (#593).
                state.abandonedAwaits++;
                state.awaitCount++;
                state.owedWakeups = Math.min(state.owedWakeups, state.openAwaits.size() - 1);
            }
            // Same round: a loop awaiting again before its one recorded exit, the same wait.
        }
    }

    /**
     * Record an await() exit, made by the thread that recorded the await.
     *
     * @param condition the condition that was awaited, tracked by identity
     * @param name the condition name (should match registration)
     * @param timedOut true if the await gave up because its time ran out, false if it returned
     *                 as woken
     */
    public void recordAwaitExit(Condition condition, String name, boolean timedOut) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        synchronized (state) {
            if (state.openAwaits.remove(thread) == null) {
                return; // no open await on this thread: nothing to pair
            }
            if (timedOut) {
                state.timedOutAwaits++;
            } else if (state.owedWakeups > 0) {
                state.owedWakeups--;
            } else {
                state.unsignalledWakeups++;
            }
            // A signal can only be owed to a waiter that is still waiting. A timed-out waiter
            // may have been the one a signal() counted; the JDK hands that signal on to the
            // next waiter, so keep the debt but never more of it than there are waiters.
            state.owedWakeups = Math.min(state.owedWakeups, state.openAwaits.size());
        }
    }

    /**
     * Record a signal() or signalAll() call, made while holding the condition's lock.
     *
     * @param condition the condition being signalled, tracked by identity
     * @param name the condition name (should match registration)
     * @param isSignalAll true if signalAll(), false if signal()
     */
    public void recordSignal(Condition condition, String name, boolean isSignalAll) {
        if (!enabled || condition == null) {
            return;
        }
        ConditionState state = conditions.get(new IdentityKey(condition));
        if (state == null) {
            return;
        }
        long thread = Thread.currentThread().threadId();
        synchronized (state) {
            if (isSignalAll) {
                state.signalAllCount++;
            } else {
                state.signalCount++;
            }
            state.signallingThreads.add(thread);
            state.lastSignalNanos = System.nanoTime();
            state.signalled = true;

            int unwoken = state.openAwaits.size() - state.owedWakeups;
            if (unwoken <= 0) {
                state.signalsWithNoWaiter++;
            } else {
                state.owedWakeups += isSignalAll ? unwoken : 1;
            }
        }
    }

    /**
     * Analyze Condition usage for issues.
     *
     * @return a report of detected issues
     */
    public ConditionVariableReport analyze() {
        ConditionVariableReport report = new ConditionVariableReport();
        report.enabled = enabled;

        for (ConditionState state : conditions.values()) {
            synchronized (state) {
                analyze(state, report);
            }
        }

        return report;
    }

    private static void analyze(ConditionState state, ConditionVariableReport report) {
        if (state.unsignalledWakeups > 0) {
            report.missingSignals.add(String.format(
                "%s: %d await(s) returned as woken with no signal()/signalAll() recorded while "
                    + "they waited (%d signal(s) recorded in total, %d with nobody waiting)",
                state.name, state.unsignalledWakeups,
                state.signalCount + state.signalAllCount, state.signalsWithNoWaiter));
        }

        String lastSignal = state.signalled
            ? "last signal " + (System.nanoTime() - state.lastSignalNanos) / 1_000_000 + "ms ago"
            : "never signalled";
        int recordedOpen = state.openAwaits.size();
        WaitQueueQuery waitQueue = state.waitQueue;
        if (waitQueue == null) {
            if (recordedOpen > 0) {
                report.stuckWaiters.add(String.format(
                    "%s: %d thread(s) still waiting at analysis (%s)", state.name, recordedOpen, lastSignal));
            }
            if (state.abandonedAwaits > 0) {
                report.stuckWaiters.add(String.format(
                    "%s: %d await(s) recorded in an earlier round never exited: the same thread "
                        + "awaited again in a later round, so that round's body ended inside the wait",
                    state.name, state.abandonedAwaits));
            }
        } else {
            // tryLock() inside: never blocks, so holding the state's monitor here cannot deadlock
            // against a recording thread that holds the lock and waits for the monitor.
            WaitQueueResult result = waitQueue.query();
            int parked = result.parked;
            if (parked > 0) {
                if (result.hasPredicate) {
                    if (result.predicateFailure != null) {
                        report.unconfirmedWaits.add(String.format(
                            "%s: %d thread(s) parked on the condition at analysis, but its predicate threw "
                                + "%s, so they are neither confirmed stuck nor idle (%s)",
                            state.name, parked, result.predicateFailure, lastSignal));
                    } else if (result.predicateSatisfied) {
                        report.stuckWaiters.add(String.format(
                            "%s: %d thread(s) parked in await() at analysis while its predicate is satisfied, "
                                + "read from the lock (%s; %d recorded await(s) still open)",
                            state.name, parked, lastSignal, recordedOpen));
                    } else {
                        report.unconfirmedWaits.add(String.format(
                            "%s: %d thread(s) parked on the condition at analysis, but its predicate is false "
                                + "(idle consumer; %s)",
                            state.name, parked, lastSignal));
                    }
                } else {
                    report.stuckWaiters.add(String.format(
                        "%s: %d thread(s) parked in await() at analysis, read from the lock (%s; %d "
                            + "recorded await(s) still open)", state.name, parked, lastSignal, recordedOpen));
                }
            } else if (parked == LOCK_HELD) {
                report.unconfirmedWaits.add(String.format(
                    "%s: the lock was held by another thread at analysis, so its waiters could not "
                        + "be read; %d recorded await(s) still open are not reported",
                    state.name, recordedOpen + state.abandonedAwaits));
            } else if (parked == NOT_OWNED) {
                report.unconfirmedWaits.add(String.format(
                    "%s: the registered lock does not own this condition, so its waiters could not "
                        + "be read; register the lock whose newCondition() made it", state.name));
            }
            int unconfirmed = Math.max(0, recordedOpen - Math.max(parked, 0)) + state.abandonedAwaits;
            if (parked >= 0 && unconfirmed > 0) {
                report.unconfirmedWaits.add(String.format(
                    "%s: %d recorded await(s) never exited, but the lock does not show them parked "
                        + "on the condition: the waiter left without recording its exit (it threw, was "
                        + "interrupted, or never called await())", state.name, unconfirmed));
            }
        }

        if (state.signalsWithNoWaiter > 0) {
            report.signalsWithNoWaiter.add(String.format(
                "%s: %d signal(s) made with nobody waiting",
                state.name, state.signalsWithNoWaiter));
        }

        if (state.awaitCount > 0 || !state.signallingThreads.isEmpty()) {
            report.threadActivity.put(state.name, String.format(
                "%d awaits (%d timed out, %d abandoned in an earlier round), %d signalling threads, "
                    + "%d signals, %d signalAll",
                state.awaitCount, state.timedOutAwaits, state.abandonedAwaits,
                state.signallingThreads.size(),
                state.signalCount,
                state.signalAllCount));
        }
    }

    /**
     * Report class for Condition variable analysis.
     */
    public static class ConditionVariableReport {
        private boolean enabled = true;
        final java.util.List<String> stuckWaiters = new java.util.ArrayList<>();
        final java.util.List<String> missingSignals = new java.util.ArrayList<>();
        /** Notes, not findings: a signal with nobody waiting is normal predicate-guarded code. */
        final java.util.List<String> signalsWithNoWaiter = new java.util.ArrayList<>();
        /**
         * Notes, not findings: recorded awaits the registered lock does not show parked, and locks
         * that could not be read (#592).
         */
        final java.util.List<String> unconfirmedWaits = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         *
         * @return {@code true} when an await returned with no signal behind it, or a thread was
         *         waiting at analysis (read from the lock when it was registered, otherwise a
         *         recorded await with no exit, including one abandoned in an earlier round)
         */
        public boolean hasIssues() {
            return !stuckWaiters.isEmpty() || !missingSignals.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ConditionVariableReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("CONDITION VARIABLE ISSUES DETECTED:\n");

            if (!missingSignals.isEmpty()) {
                sb.append("  Missing Signals (await woke with no signal behind it):\n");
                for (String issue : missingSignals) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!stuckWaiters.isEmpty()) {
                sb.append("  Stuck Waiters:\n");
                for (String issue : stuckWaiters) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!unconfirmedWaits.isEmpty()) {
                sb.append("  Note, not a finding (the registered lock decides stuck waiters):\n");
                for (String note : unconfirmedWaits) {
                    sb.append("    - ").append(note).append("\n");
                }
            }

            if (!signalsWithNoWaiter.isEmpty()) {
                sb.append("  Note, not a finding (signals with nobody waiting; correct when the "
                        + "consumer tests its predicate before it awaits):\n");
                for (String note : signalsWithNoWaiter) {
                    sb.append("    - ").append(note).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("""
  Why: A thread still parked in await() at the end of the run is waiting for a signal nobody sent:
     the producer signalled a different condition, signalled before changing the state, or used
     signal() where two waiters needed waking. An await that returns as woken with no signal behind
     it means a signal site is not recorded, or a spurious wakeup was taken as a signal.
  Fix:
    - Always await() in a while loop: while (!ready) { condition.await(); }
    - Change the state, then signal the condition its waiters are parked on: ready = true; condition.signalAll();
    - Prefer signalAll() over signal() unless every waiter is interchangeable and exactly one should wake\
""");
            return sb.toString();
        }
    }
}
