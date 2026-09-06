package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Detects incorrect {@link java.util.concurrent.locks.ReentrantReadWriteLock} downgrade and
 * upgrade patterns.
 *
 * <h2>The unsafe downgrade (detected, when the gap is observed being used)</h2>
 * A downgrade is meant to hand a thread from the write lock to the read lock without letting go
 * of the lock in between. Releasing the write lock first opens a gap:
 * <pre>{@code
 * lock.writeLock().lock();
 * try { map.put(key, value); } finally { lock.writeLock().unlock(); }
 * // gap: another thread can take the write lock here
 * lock.readLock().lock();
 * try { return map.get(key); } finally { lock.readLock().unlock(); }
 * }</pre>
 * The caller can read back a value it did not write. The correct form acquires the read lock
 * <em>while still holding the write lock</em>, then releases the write lock, so there is no
 * moment at which the thread holds neither.
 *
 * <p><strong>The finding is evidence-gated, deliberately.</strong> The recorded sequence
 * "released write, then acquired read" is also what correct code produces when a thread writes
 * one thing, releases, and later reads something unrelated under the read lock; nothing in the
 * records distinguishes the two, and reporting on the shape alone would flag that correct code.
 * So the shape alone is <em>not</em> a finding. It becomes one only when another thread was seen
 * taking the write lock inside the gap, which is a fact about this run and the exact condition
 * that makes the downgrade unsafe. The cost is a false negative: a run in which nobody happened
 * to enter the gap reports nothing, even though the gap is there. Issue #355 is where that
 * decision is written down, and {@code docs/analysis/detector-accuracy-eval.md} publishes it.
 *
 * <h2>The upgrade problem (detected)</h2>
 * {@code ReentrantReadWriteLock} does <strong>not</strong> support read-to-write upgrade.
 * A thread holding a read lock that calls {@code writeLock().lock()} will deadlock immediately
 * because the write lock cannot be granted while any read lock is held — including the one held
 * by the same thread.
 *
 * <p>{@link LockUpgradeDeadlockDetector} watches the same condition, is named for it, and
 * produces a structured {@code Violation} for it. When the registry has both, this detector
 * forwards what it records there and leaves the upgrade out of its own report, so one upgrade is
 * one finding whichever recording API the caller used; see {@link #deferUpgradeReportingTo}.
 * When this detector is the only one enabled it keeps reporting the upgrade, because nothing
 * else would. Issue #361. Retiring one of the two {@code DetectorType} constants outright is an
 * API change and still belongs in a version that can carry one.
 *
 * <h2>Correct downgrade pattern (not flagged)</h2>
 * <ol>
 *   <li>Acquire write lock</li>
 *   <li>Acquire read lock (while still holding write lock)</li>
 *   <li>Release write lock</li>
 *   <li>Perform reads, then release read lock</li>
 * </ol>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectLockDowngrade = true)
 * void testLockUpgrade() {
 *     LockDowngradeDetector mon = AsyncTestContext.lockDowngradeMonitor();
 *     rwLock.readLock().lock();
 *     mon.recordReadLockAcquired(rwLock, "myLock");
 *     try {
 *         // BUG: upgrading read to write — will deadlock
 *         mon.recordWriteLockAcquired(rwLock, "myLock");
 *         rwLock.writeLock().lock();
 *     } finally {
 *         mon.recordWriteLockReleased(rwLock, "myLock");
 *         rwLock.writeLock().unlock();
 *         mon.recordReadLockReleased(rwLock, "myLock");
 *         rwLock.readLock().unlock();
 *     }
 * }
 * }</pre>
 */
public class LockDowngradeDetector {

    /**
     * Per-thread hold counters. A single "R or W" marker cannot represent the
     * mid-downgrade state where a thread holds both locks: the read acquire
     * overwrote the write marker (flagging a legal reentrant write acquire as an
     * upgrade), and the write release then erased the read record entirely
     * (missing a genuine read-to-write upgrade attempted after a downgrade).
     */
    private static final class Holds {
        int read;
        int write;
        /**
         * Set when this thread gave up its last write hold on this lock while holding no read
         * lock: the gap of an unsafe downgrade is open. Cleared by the next recorded event from
         * this thread on this lock, whatever it is, so only a read acquire that <em>immediately
         * follows</em> the release counts. A thread that records other work on the lock in
         * between has done something the records cannot tie back to the write.
         */
        boolean gapOpen;
        /** {@link LockState#writeAcquireGeneration} at the moment the gap opened. */
        long gapOpenedAtGeneration;
        /**
         * The invocation round the gap opened in. A gap is a claim about two adjacent operations
         * by one thread, and the runner's latch sits between rounds: a write released in round k
         * and a read taken in round k+n are not a downgrade, however many writers ran in between
         * (#499).
         */
        long gapOpenedInEpoch;
    }

    private static class LockState {
        final String name;
        final Map<Long, Holds> threadHolds = new ConcurrentHashMap<>();
        final AtomicInteger upgradeAttempts = new AtomicInteger(0);
        final AtomicReference<String> firstUpgradeThread = new AtomicReference<>();

        /**
         * Bumped on every write acquire, by any thread. A gap that opens at generation g and
         * closes at a generation greater than g had a writer inside it, which is the evidence
         * that turns a downgrade-shaped sequence into a finding.
         */
        final AtomicLong writeAcquireGeneration = new AtomicLong();
        /** Downgrade-shaped sequences seen: released write, then acquired read. Context, not a finding. */
        final AtomicInteger downgradeShapes = new AtomicInteger(0);
        /** Of those, the ones another thread was observed writing inside. This is the finding. */
        final AtomicInteger observedGaps = new AtomicInteger(0);
        final AtomicReference<String> firstObservedGapThread = new AtomicReference<>();

        LockState(String name) { this.name = name; }
    }

    private final Map<Integer, LockState> locks = new ConcurrentHashMap<>();
    /**
     * Current invocation round, bumped by {@link #markInvocationStart()}. Standalone use without
     * round marks leaves every gap in epoch 0, which preserves the single-run behaviour.
     */
    private final AtomicLong invocationEpoch = new AtomicLong();

    /**
     * Where to send upgrade observations instead of reporting them here, or {@code null} to
     * report them here.
     *
     * <p>{@link LockUpgradeDeadlockDetector} watches the same condition and is named for it, so
     * a run with both enabled and both fed reported it twice, once under a name that describes
     * the opposite operation. Deleting the finding from this class was not an option on its own:
     * the two have separate recording APIs, and a caller who instruments only this one and never
     * calls {@code LockUpgradeDeadlockDetector.record*} would have gone from a finding to a
     * clean report with nothing to say why.
     *
     * <p>So the registry hands this detector the other one when both are enabled, and from then
     * on every read acquire, read release and write acquire recorded here is also recorded there.
     * The finding then comes out once, from the detector that owns it, whichever API the caller
     * used. With no peer set - only this detector enabled, or direct construction in a unit test
     * - nothing changes and the upgrade is reported here, because nothing else will report it.
     *
     * <p>Issue #361. The full merge, which retires one of the two {@code DetectorType} constants,
     * is an API change and still belongs in a version that can carry one.
     */
    private volatile @Nullable LockUpgradeDeadlockDetector upgradeReporter;

    /**
     * Hands upgrade reporting to {@code peer}, which is named for that condition.
     *
     * <p>Called by {@code DetectorRegistry} when both detectors are enabled. After this, upgrade
     * observations are forwarded to {@code peer} and left out of this detector's own report; the
     * unsafe downgrade, which is this detector's own subject, is unaffected.
     *
     * @param peer the detector that will report read-to-write upgrades, or {@code null} to keep
     *             reporting them here
     */
    public void deferUpgradeReportingTo(@Nullable LockUpgradeDeadlockDetector peer) {
        this.upgradeReporter = peer;
    }

    /**
     * Mirrors one recorded event into {@link #upgradeReporter}, if there is one.
     *
     * <p>{@code LockUpgradeDeadlockDetector} takes a {@link ReentrantReadWriteLock} where this
     * detector takes the {@link ReadWriteLock} interface, so anything else is silently not
     * forwarded. That is not a gap worth closing: the upgrade deadlock is a property of
     * {@code ReentrantReadWriteLock}'s own contract, and another implementation is free to
     * support upgrading.
     */
    private void forward(ReadWriteLock lock, java.util.function.BiConsumer<
            LockUpgradeDeadlockDetector, ReentrantReadWriteLock> event) {
        LockUpgradeDeadlockDetector peer = upgradeReporter;
        if (peer != null && lock instanceof ReentrantReadWriteLock reentrant) {
            event.accept(peer, reentrant);
        }
    }


    private LockState stateFor(ReadWriteLock lock, String name) {
        return locks.computeIfAbsent(System.identityHashCode(lock), k -> {
            String resolved = name != null ? name : "rwlock@" + k;
            return new LockState(resolved);
        });
    }

    /**
     * Record that the current thread acquired the <em>read</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordReadLockAcquired(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        forward(lock, (peer, rw) -> peer.recordReadLockAcquired(rw, lockName, Thread.currentThread()));
        LockState state = stateFor(lock, lockName);
        long tid = Thread.currentThread().threadId();
        // Acquiring read while already holding write is the valid downgrade step —
        // recorded additively so the write hold is not forgotten.
        state.threadHolds.compute(tid, (k, h) -> {
            if (h == null) h = new Holds();
            if (h.gapOpen && h.read == 0 && h.write == 0
                    && h.gapOpenedInEpoch == invocationEpoch.get()) {
                // The second half of an unsafe downgrade: the write lock was released and the
                // read lock is being taken now, with nothing of this thread's in between.
                state.downgradeShapes.incrementAndGet();
                if (state.writeAcquireGeneration.get() > h.gapOpenedAtGeneration) {
                    state.observedGaps.incrementAndGet();
                    state.firstObservedGapThread.compareAndSet(null, Thread.currentThread().getName());
                }
            }
            h.gapOpen = false;
            h.read++;
            return h;
        });
    }

    /**
     * Record that the current thread released the <em>read</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordReadLockReleased(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        forward(lock, (peer, rw) -> peer.recordReadLockReleased(rw, Thread.currentThread()));
        stateFor(lock, lockName).threadHolds.computeIfPresent(
            Thread.currentThread().threadId(), (k, h) -> {
                if (h.read > 0) h.read--;
                h.gapOpen = false;
                return (h.read == 0 && h.write == 0) ? null : h;
            });
    }

    /**
     * Record that the current thread is about to acquire the <em>write</em> lock.
     * If the thread already holds the <em>read</em> lock on the same {@link ReadWriteLock},
     * an upgrade attempt is recorded.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordWriteLockAcquired(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        // Forwarded before this detector's own bookkeeping, because the peer decides whether
        // this is an upgrade from the read holders it has been told about, and those come from
        // the same forwarding.
        forward(lock, (peer, rw) ->
                peer.recordWriteLockAcquisitionAttempt(rw, lockName, Thread.currentThread()));
        LockState state = stateFor(lock, lockName);
        long tid = Thread.currentThread().threadId();
        // Bumped before the per-thread work so a gap that is open right now, on another
        // thread, closes against a generation that already counts this acquire.
        state.writeAcquireGeneration.incrementAndGet();
        state.threadHolds.compute(tid, (k, h) -> {
            if (h == null) h = new Holds();
            // Upgrade = acquiring write while holding read but NOT write. Holding
            // write too (mid-downgrade) makes this a legal reentrant acquire.
            if (h.read > 0 && h.write == 0) {
                state.upgradeAttempts.incrementAndGet();
                state.firstUpgradeThread.compareAndSet(null, Thread.currentThread().getName());
            }
            h.gapOpen = false;
            h.write++;
            return h;
        });
    }

    /**
     * Record that the current thread released the <em>write</em> lock.
     *
     * @param lock     the {@link ReadWriteLock} (null-safe)
     * @param lockName descriptive name for reports
     */
    public void recordWriteLockReleased(ReadWriteLock lock, String lockName) {
        if (lock == null) return;
        LockState state = stateFor(lock, lockName);
        state.threadHolds.computeIfPresent(
            Thread.currentThread().threadId(), (k, h) -> {
                if (h.write > 0) h.write--;
                if (h.write == 0 && h.read == 0) {
                    // The thread now holds neither lock, having just held the write lock: the
                    // gap of a downgrade that was not done in the safe order. A correct
                    // downgrade takes the read lock first, so h.read > 0 here and nothing opens.
                    h.gapOpen = true;
                    h.gapOpenedAtGeneration = state.writeAcquireGeneration.get();
                    h.gapOpenedInEpoch = invocationEpoch.get();
                    return h;
                }
                h.gapOpen = false;
                return h;
            });
    }

    /**
     * Marks the start of a new invocation round.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round. A downgrade gap opened in an
     * earlier round is no longer closed by this round's read lock.
     *
     * @since 1.11.2
     */
    public void markInvocationStart() {
        invocationEpoch.incrementAndGet();
    }

    /**
     * Analyze for invalid lock upgrade/downgrade patterns.
     *
     * @return report describing detected issues
     */
    public LockDowngradeReport analyze() {
        LockDowngradeReport report = new LockDowngradeReport();
        boolean upgradesReportedElsewhere = upgradeReporter != null;
        for (LockState state : locks.values()) {
            int upgrades = state.upgradeAttempts.get();
            // Counted either way, so a caller reading this detector directly still sees the
            // number; only the report line stands down, and only when the detector named for
            // the condition has been fed the same events. See deferUpgradeReportingTo.
            if (upgrades > 0 && !upgradesReportedElsewhere) {
                // One line per lock, with a count. It used to be one line per occurrence in a
                // CopyOnWriteArrayList: a stress test producing the same upgrade on every body
                // execution printed the same sentence hundreds of times, and each append copied
                // the whole array on the threads being watched. See issue #351.
                report.upgradeAttempts.add(String.format(
                    "Thread '%s' attempted read→write upgrade on lock '%s'%s — "
                    + "ReentrantReadWriteLock does not support upgrade; this will deadlock",
                    describe(state.firstUpgradeThread.get()), state.name,
                    upgrades > 1 ? " (x" + upgrades + ")" : ""));
            }

            int observed = state.observedGaps.get();
            if (observed > 0) {
                report.unsafeDowngrades.add(String.format(
                    "Lock '%s': %d unsafe downgrade(s) observed — a thread released the write "
                    + "lock and then acquired the read lock, and another thread took the write "
                    + "lock in between, so the read need not return what the writer wrote "
                    + "(first seen on thread '%s'). %d downgrade-shaped sequence(s) were "
                    + "recorded on this lock in all; the rest had no writer inside the gap and "
                    + "are not reported.",
                    state.name, observed, describe(state.firstObservedGapThread.get()),
                    state.downgradeShapes.get()));
            }
        }
        return report;
    }

    private static String describe(@Nullable String threadName) {
        return threadName != null ? threadName : "unknown";
    }

    /** Report produced by {@link #analyze()}. */
    public static class LockDowngradeReport {
        final List<String> upgradeAttempts = new ArrayList<>();
        /** Downgrades whose gap another thread was observed writing inside. */
        final List<String> unsafeDowngrades = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !upgradeAttempts.isEmpty() || !unsafeDowngrades.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LOCK UPGRADE/DOWNGRADE ISSUES DETECTED:\n");
            for (String issue : unsafeDowngrades) sb.append("  - ").append(issue).append("\n");
            for (String issue : upgradeAttempts) sb.append("  - ").append(issue).append("\n");
            sb.append("""
  Why: ReentrantReadWriteLock does not support upgrade (read→write). A thread holding a read lock that
       tries to acquire the write lock will deadlock against itself, since the write lock requires all
       read locks to be released first — including its own. The mirror image, releasing the write lock
       before taking the read lock, is not a deadlock but a lost guarantee: the lock is free in between,
       so what comes back from the read need not be what was written.
  Fix:
    - To upgrade: release the read lock, then acquire the write lock (re-validate the condition after)
    - For safe downgrade: acquire the read lock while still holding the write lock, THEN release the write lock
    - Consider StampedLock.tryConvertToWriteLock(stamp) if upgrade is a frequent pattern\
""");
            return sb.toString();
        }
    }
}
