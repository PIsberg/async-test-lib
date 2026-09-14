package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

import org.jspecify.annotations.Nullable;

/**
 * Detects {@link StampedLock} misuse:
 * <ul>
 *   <li>an optimistic read whose stamp the same thread never passed to {@code validate()};</li>
 *   <li>a {@code validate()} that failed and was followed by neither a read lock, a write lock nor
 *       a retried optimistic read, on the same thread and the same lock instance;</li>
 *   <li>a read or write stamp recorded as acquired and never recorded as released, on a lock that
 *       is still held when the run is analysed.</li>
 * </ul>
 *
 * <p>A failed {@code validate()} is not itself a finding. {@code StampedLock} exists for the case
 * where a writer intervenes, and the documented answer is to fall back to {@code readLock()} or to
 * retry the optimistic read; reporting the failure would fire stochastically on exactly the code
 * that handles it correctly. The finding is a failed validation that is followed by neither, on
 * the same thread and lock, which is the shape in which the stale value was used as read. A zero
 * stamp from {@code tryOptimisticRead()} (the lock was write-held) is treated the same way: it can
 * never validate, so it needs the same fallback.
 *
 * <p>A leak is decided by asking the lock. An acquisition nobody matched with
 * {@link #recordUnlock} is reported only while {@link StampedLock#isWriteLocked()} or
 * {@link StampedLock#isReadLocked()} still says so at analysis, and a
 * {@link #recordStampNotReleased} declaration stands in for the unmatched acquisition under the
 * same condition. Neither is enough alone: an unmatched record on a free lock is a gap in the
 * recording, and a declaration on a free lock is contradicted by the lock (#588).
 *
 * <p>Matching is per thread and per lock instance, never per lock name and never on counts pooled
 * across threads: two locks that share a label keep separate state, and validations on one thread
 * do not cancel a read another thread never validated. Worker threads are pooled across
 * invocation rounds, so {@link #markInvocationStart()} seals whatever a round left unvalidated or
 * unsettled; a read lock taken in a later round is never the fallback for a validation that failed
 * in an earlier one.
 *
 * <p>Not modelled: a wrong stamp passed to an unlock method, which the lock itself refuses with
 * {@code IllegalMonitorStateException} in the caller's own thread, and mode conversions
 * ({@code tryConvertToWriteLock} and its siblings), which the recording API has no method for.
 */
public class StampedLockDetector {

    private final Map<StampedLock, LockInfo> lockRegistry = new ConcurrentHashMap<>();
    /**
     * Optimistic reads and failed validations of the round in progress, per thread and lock
     * instance. Only the owning thread records into an entry; analysis and the round boundary
     * read it from the runner's thread, which is why {@link ThreadState} synchronizes.
     */
    private final Map<ThreadLock, ThreadState> inFlight = new ConcurrentHashMap<>();
    /** Names the caller declared unreleased; corroborated against the registered locks at analysis. */
    private final Set<String> declaredNotReleased = ConcurrentHashMap.newKeySet();

    /**
     * Register a StampedLock for monitoring.
     *
     * <p>Registration is optional: every record method registers a lock it has not seen under the
     * name it was given. Registering first only fixes the label.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param name a label identifying the lock in the report
     */
    public void registerLock(StampedLock lock, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        if (lock != null) {
            lockRegistry.putIfAbsent(lock, new LockInfo(name));
        }
    }

    /**
     * Record an optimistic read stamp acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordOptimisticRead(StampedLock lock, String lockName, long stamp) {
        ThreadState state = stateOf(lock, lockName);
        if (state != null) {
            state.optimisticRead(stamp);
        }
    }

    /**
     * Record validation of optimistic read.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     * @param validated the {@code validated} flag
     */
    public void recordOptimisticValidation(StampedLock lock, String lockName, long stamp, boolean validated) {
        ThreadState state = stateOf(lock, lockName);
        if (state != null) {
            state.validation(stamp, validated);
        }
    }

    /**
     * Record a read lock acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation; zero, as
     *              {@code tryReadLock} returns when it fails, is not an acquisition
     */
    public void recordReadLock(StampedLock lock, String lockName, long stamp) {
        recordAcquisition(lock, lockName, stamp);
    }

    /**
     * Record a write lock acquisition.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation; zero, as
     *              {@code tryWriteLock} returns when it fails, is not an acquisition
     */
    public void recordWriteLock(StampedLock lock, String lockName, long stamp) {
        recordAcquisition(lock, lockName, stamp);
    }

    private void recordAcquisition(StampedLock lock, String lockName, long stamp) {
        ThreadState state = stateOf(lock, lockName);
        if (state == null) {
            return;
        }
        // Taking the lock is the documented fallback for a failed validation, whether or not
        // this particular try succeeded in getting it.
        state.locked();
        if (stamp != 0L) {
            state.info.acquired(stamp);
        }
    }

    /**
     * Record a lock release.
     *
     * @param lock the lock being recorded, tracked by identity rather than equality
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation
     */
    public void recordUnlock(StampedLock lock, String lockName, long stamp) {
        if (lock != null) {
            infoOf(lock, lockName).released(stamp);
        }
    }

    /**
     * Declare that a stamp on the lock registered under this name was not released.
     *
     * <p>A declaration is corroborated, not taken on trust: it is reported only if a lock
     * registered under {@code lockName} is still read- or write-held when the run is analysed.
     * Prefer recording the acquisition itself, which the detector matches against
     * {@link #recordUnlock} without any declaration.
     *
     * @param lockName a label identifying the lock in the report
     * @param stamp the stamp returned by the {@code StampedLock} operation (informational)
     */
    public void recordStampNotReleased(String lockName, long stamp) {
        declaredNotReleased.add(String.valueOf(lockName));
    }

    /**
     * Marks the start of a new invocation round.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round, after the previous round's
     * workers have finished. Optimistic reads a round left unvalidated, and failed validations it
     * never fell back from, are sealed as findings, so a pooled worker's lock acquisition in the
     * next round cannot settle them.
     *
     * @since 1.12.1
     */
    public void markInvocationStart() {
        inFlight.entrySet().removeIf(entry -> {
            entry.getValue().seal();
            return true;
        });
    }

    /**
     * Analyze StampedLock usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public StampedLockReport analyze() {
        Map<LockInfo, int[]> pending = new HashMap<>();
        for (ThreadState state : inFlight.values()) {
            state.addPendingTo(pending.computeIfAbsent(state.info, k -> new int[2]));
        }

        Set<String> unvalidated = new HashSet<>();
        Set<String> notReleased = new HashSet<>();
        for (Map.Entry<StampedLock, LockInfo> entry : lockRegistry.entrySet()) {
            StampedLock lock = entry.getKey();
            LockInfo info = entry.getValue();
            int[] open = pending.getOrDefault(info, new int[2]);

            int neverValidated = info.neverValidated.get() + open[0];
            if (neverValidated > 0) {
                unvalidated.add(info.name + " (" + neverValidated
                        + " optimistic read(s) never validated)");
            }
            int ignoredFailures = info.ignoredFailures.get() + open[1];
            if (ignoredFailures > 0) {
                unvalidated.add(info.name + " (" + ignoredFailures + " failed or zero-stamp "
                        + "validation(s) followed by no read lock, write lock or retried optimistic read)");
            }

            String leak = describeLeak(lock, info);
            if (leak != null) {
                notReleased.add(leak);
            }
        }
        return new StampedLockReport(unvalidated, notReleased);
    }

    /**
     * A leak needs two independent facts: the lock is held now, and the detector either saw an
     * acquisition nobody released or the caller declared one.
     */
    private @Nullable String describeLeak(StampedLock lock, LockInfo info) {
        boolean writeHeld = lock.isWriteLocked();
        int readers = lock.getReadLockCount();
        if (!writeHeld && readers == 0) {
            return null;
        }
        int unreleased = info.unreleased();
        boolean declared = declaredNotReleased.contains(String.valueOf(info.name));
        if (unreleased == 0 && !declared) {
            return null;
        }
        String held = writeHeld ? "write-locked" : "read-locked by " + readers + " reader(s)";
        String evidence = unreleased > 0
                ? unreleased + " recorded acquisition(s) never released"
                : "declared unreleased by the caller";
        return info.name + " (" + held + " at analysis; " + evidence + ")";
    }

    private @Nullable ThreadState stateOf(@Nullable StampedLock lock, String lockName) {
        if (lock == null) {
            return null;
        }
        LockInfo info = infoOf(lock, lockName);
        return inFlight.computeIfAbsent(
                new ThreadLock(Thread.currentThread().threadId(), lock),
                k -> new ThreadState(info));
    }

    private LockInfo infoOf(StampedLock lock, String lockName) {
        return lockRegistry.computeIfAbsent(lock, k -> new LockInfo(lockName));
    }

    /**
     * Report class for StampedLock analysis.
     */
    public static class StampedLockReport {
        private final Set<String> unvalidatedOptimisticReads;
        private final Set<String> stampNotReleased;
        /**
         * Creates a StampedLockReport.
         *
         * @param unvalidatedOptimisticReads the optimistic reads whose stamp was never validated
         * @param stampNotReleased the stamps acquired but never released
         */
        public StampedLockReport(
            Set<String> unvalidatedOptimisticReads,
            Set<String> stampNotReleased
        ) {
            this.unvalidatedOptimisticReads = Collections.unmodifiableSet(new HashSet<>(unvalidatedOptimisticReads));
            this.stampNotReleased = Collections.unmodifiableSet(new HashSet<>(stampNotReleased));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !unvalidatedOptimisticReads.isEmpty() || !stampNotReleased.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("STAMPEDLOCK ISSUES DETECTED:\n");

            if (!unvalidatedOptimisticReads.isEmpty()) {
                sb.append("  Unvalidated Optimistic Reads:\n");
                for (String lockInfo : unvalidatedOptimisticReads) {
                    sb.append("    - ").append(lockInfo).append("\n");
                }
                sb.append("  Problem: Optimistic read used without a successful validate() or a fallback\n");
                sb.append("""
  Why: An optimistic read acquires no lock. A concurrent writer may update the fields between the read
       and the validate() call, meaning the read values are a torn snapshot from two different states.
       Using data from a failed validation produces silently wrong results.
""");
                sb.append("  Fix: Always call lock.validate(stamp) before using optimistically-read data:\n");
                sb.append("    long stamp = lock.tryOptimisticRead();\n");
                sb.append("    int x = field;  // read — may be torn\n");
                sb.append("    if (!lock.validate(stamp)) { stamp = lock.readLock(); try { x = field; } finally { lock.unlockRead(stamp); } }\n");
            }

            if (!stampNotReleased.isEmpty()) {
                sb.append("  Stamps Not Released:\n");
                for (String lockInfo : stampNotReleased) {
                    sb.append("    - ").append(lockInfo).append("\n");
                }
                sb.append("""
  Why: An unreleased StampedLock read or write lock blocks all subsequent writers (or all readers
       for a leaked write lock) indefinitely, causing the application to hang.
""");
                sb.append("  Fix: Always release stamps in a finally block:\n");
                sb.append("    long stamp = lock.readLock();\n");
                sb.append("    try { /* read fields */ } finally { lock.unlockRead(stamp); }\n");
            }

            if (!hasIssues()) {
                sb.append("  No StampedLock issues detected.\n");
            }

            return sb.toString();
        }
    }

    /** One recording thread's view of one lock instance; the lock itself compares by identity. */
    private record ThreadLock(long threadId, StampedLock lock) {
    }

    /**
     * Per-lock state: the label, the acquisitions not yet matched by a release, and the counts
     * sealed from finished rounds.
     */
    static final class LockInfo {
        final String name;
        /** Stamp to outstanding count. Read stamps are not unique across concurrent readers. */
        private final Map<Long, Integer> outstanding = new HashMap<>();
        final AtomicInteger neverValidated = new AtomicInteger();
        final AtomicInteger ignoredFailures = new AtomicInteger();

        LockInfo(String name) {
            this.name = name;
        }

        synchronized void acquired(long stamp) {
            outstanding.merge(stamp, 1, Integer::sum);
        }

        synchronized void released(long stamp) {
            outstanding.computeIfPresent(stamp, (s, count) -> count > 1 ? count - 1 : null);
        }

        synchronized int unreleased() {
            int total = 0;
            for (int count : outstanding.values()) {
                total += count;
            }
            return total;
        }
    }

    /** What one thread has left open on one lock in the round in progress. */
    private static final class ThreadState {
        final LockInfo info;
        /** Optimistic stamps read and not yet validated, to count. */
        private final Map<Long, Integer> unvalidated = new HashMap<>();
        /** Stamps whose validation failed (or that were zero) with no fallback yet. */
        private final Set<Long> failed = new HashSet<>();

        ThreadState(LockInfo info) {
            this.info = info;
        }

        synchronized void optimisticRead(long stamp) {
            // A retried optimistic read is the other documented fallback.
            failed.clear();
            if (stamp == 0L) {
                // The lock was write-held: this stamp can never validate, so it needs a fallback.
                failed.add(stamp);
            } else {
                unvalidated.merge(stamp, 1, Integer::sum);
            }
        }

        synchronized void validation(long stamp, boolean validated) {
            unvalidated.computeIfPresent(stamp, (s, count) -> count > 1 ? count - 1 : null);
            if (!validated) {
                failed.add(stamp);
            }
        }

        synchronized void locked() {
            failed.clear();
        }

        synchronized void addPendingTo(int[] totals) {
            for (int count : unvalidated.values()) {
                totals[0] += count;
            }
            totals[1] += failed.size();
        }

        synchronized void seal() {
            int[] totals = new int[2];
            addPendingTo(totals);
            info.neverValidated.addAndGet(totals[0]);
            info.ignoredFailures.addAndGet(totals[1]);
            unvalidated.clear();
            failed.clear();
        }
    }
}
