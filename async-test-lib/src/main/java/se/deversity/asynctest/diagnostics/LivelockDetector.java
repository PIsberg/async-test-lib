package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says what a worker was doing when its round timed out, and reads snapshots a body takes itself.
 *
 * <p><strong>The runner's feed: a round that timed out.</strong> A livelock is a body that does not
 * finish, so the one observation that can show it is that the body did not finish. The runner marks
 * each worker in and out of its body ({@link #workerEnteredBody}, {@link #workerLeftBody}) and calls
 * {@link #roundTimedOut()} before it interrupts anyone. A worker still in its body then is sampled
 * {@value #TIMEOUT_SAMPLES} times, {@value #TIMEOUT_SAMPLE_GAP_MS} ms apart, through
 * {@link Thread#getState()}, which unlike a thread dump answers for a virtual thread too: RUNNABLE in
 * any sample is a livelock candidate (a spin, a retry that never succeeds, a long computation or a
 * blocking native call), every sample BLOCKED or WAITING is starvation, and anything else, such as a
 * retry loop asleep in TIMED_WAITING, is no progress. A round that finishes is never reported, and the
 * only threshold is the {@code timeoutMs} the test chose.
 *
 * <p>The runner used to sample from each worker's own {@code finally} instead. A worker saw itself
 * RUNNABLE there and its finished peers WAITING in the pool's queue, so a healthy platform-thread run
 * alternated between the two and read as rapid state cycling at CRITICAL, on a body that only
 * increments a counter.
 *
 * <p><strong>A body's own snapshots.</strong> {@link #captureSnapshot()} is still public for a body
 * that samples its peers. Those rules are thresholds: starvation is every recent snapshot BLOCKED or
 * WAITING with flat CPU time, and rapid state cycling is five state changes in ten snapshots. It takes
 * {@code ThreadMXBean.dumpAllThreads} and keeps only the threads that have called it, and a virtual
 * thread is not in that dump. {@code madeProgress} returns true for any RUNNABLE thread, deliberately,
 * because a busy worker's measured CPU time can look flat when several snapshots land inside one clock
 * tick, so a snapshot cannot report a spin; {@code LivelockDetectorTest} pins that.
 */
public class LivelockDetector {

    /** State changes within the last {@value #RECENT_SNAPSHOTS} snapshots that read as rapid cycling (#756). */
    private static final int STATE_CHANGE_THRESHOLD = 5;

    /** How many of the latest snapshots the state changes are counted over. */
    private static final int RECENT_SNAPSHOTS = 10;

    /** How many times a worker still in its body is sampled when its round times out. */
    private static final int TIMEOUT_SAMPLES = 5;

    /** The gap between those samples, in milliseconds. */
    private static final long TIMEOUT_SAMPLE_GAP_MS = 10;

    private static final Set<Thread.State> BLOCKED_OR_WAITING =
            EnumSet.of(Thread.State.BLOCKED, Thread.State.WAITING);
    
    private static final class ThreadSnapshot {
        final String threadName;
        final Thread.State state;
        final long cpuTime;

        ThreadSnapshot(String threadName, Thread.State state, long cpuTime) {
            this.threadName = threadName;
            this.state = state;
            this.cpuTime = cpuTime;
        }
    }
    
    private final Map<Long, List<ThreadSnapshot>> threadHistory = new ConcurrentHashMap<>();
    /**
     * Ids of the threads that have called {@link #captureSnapshot()}. Snapshots are kept only for
     * these: a JVM daemon parked in WAITING is not a starved test worker.
     */
    private final Set<Long> observedThreads = ConcurrentHashMap.newKeySet();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private volatile boolean enabled = true;

    /** The workers inside a test body right now, as the runner marks them. */
    private final Set<Thread> workersInBody = ConcurrentHashMap.newKeySet();

    /** Workers found running when a round timed out, by name. */
    private final Set<String> timedOutRunning = ConcurrentHashMap.newKeySet();

    /** Workers found BLOCKED or WAITING in every sample when a round timed out, by name. */
    private final Set<String> timedOutBlocked = ConcurrentHashMap.newKeySet();

    /** Workers found in any other state when a round timed out, by name. */
    private final Set<String> timedOutElsewhere = ConcurrentHashMap.newKeySet();

    /**
     * Marks a worker as inside the test body. The runner calls this after the round's barrier.
     *
     * @param worker the calling worker
     * @since 1.14.1
     */
    public void workerEnteredBody(Thread worker) {
        if (enabled) {
            workersInBody.add(worker);
        }
    }

    /**
     * Marks a worker as out of the test body, however the body ended.
     *
     * @param worker the calling worker
     * @since 1.14.1
     */
    public void workerLeftBody(Thread worker) {
        workersInBody.remove(worker);
    }

    /**
     * Classifies every worker still in its body. The runner calls this when a round has timed out,
     * before it interrupts them, so that what is sampled is what the body was doing.
     *
     * @since 1.14.1
     */
    public void roundTimedOut() {
        Map<Thread, Set<Thread.State>> seen = new HashMap<>();
        for (int sample = 0; sample < TIMEOUT_SAMPLES; sample++) {
            for (Thread worker : workersInBody) {
                seen.computeIfAbsent(worker, k -> EnumSet.noneOf(Thread.State.class)).add(worker.getState());
            }
            try {
                Thread.sleep(TIMEOUT_SAMPLE_GAP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Map.Entry<Thread, Set<Thread.State>> entry : seen.entrySet()) {
            // Finished while it was being sampled: the deadline was close, and it was not stuck.
            if (!workersInBody.contains(entry.getKey())) {
                continue;
            }
            Set<Thread.State> states = entry.getValue();
            String name = entry.getKey().getName();
            if (states.contains(Thread.State.RUNNABLE)) {
                timedOutRunning.add(name);
            } else if (BLOCKED_OR_WAITING.containsAll(states)) {
                timedOutBlocked.add(name);
            } else {
                timedOutElsewhere.add(name);
            }
        }
    }
    
    /**
     * Capture current thread state. Call periodically during test execution.
     */
    public void captureSnapshot() {
        if (!enabled) return;

        // Whoever is calling is a thread running the test. That is how we learn which threads are
        // ours.
        observedThreads.add(Thread.currentThread().threadId());

        ThreadInfo[] allThreads = threadMXBean.dumpAllThreads(false, false);

        for (ThreadInfo ti : allThreads) {
            long threadId = ti.getThreadId();

            // Only the threads running the test can be starved by the code under test. The JVM's
            // own daemons (Finalizer, Reference Handler, Common-Cleaner, JUnit infrastructure)
            // are parked in WAITING with flat CPU time forever — exactly the signature isStarved()
            // looks for — so recording them reported starvation against a perfectly healthy JVM
            // on essentially every run.
            if (!observedThreads.contains(threadId)) continue;

            long cpuTime = threadMXBean.getThreadCpuTime(threadId);

            ThreadSnapshot snapshot = new ThreadSnapshot(
                ti.getThreadName(),
                ti.getThreadState(),
                cpuTime
            );
            
            threadHistory.computeIfAbsent(threadId, k -> 
                Collections.synchronizedList(new ArrayList<>())
            ).add(snapshot);
        }
    }
    
    /**
     * Analyze captured snapshots for livelock and starvation patterns.
     *
     * @return the findings this detector collected during the run
     */
    public LivelockReport analyzeLivelocks() {
        LivelockReport report = new LivelockReport();
        
        for (Map.Entry<Long, List<ThreadSnapshot>> entry : threadHistory.entrySet()) {
            List<ThreadSnapshot> snapshots = entry.getValue();
            if (snapshots.size() < 2) continue;
            
            String threadName = snapshots.get(0).threadName;
            
            // Check for starvation: thread is always BLOCKED/WAITING, never gets CPU
            if (isStarved(snapshots)) {
                report.starvedThreads.add(threadName);
            }
            
            // Check for rapid state changes (livelock indicator)
            if (isRapidStateChanger(snapshots)) {
                report.livelockCandidates.add(threadName);
            }
            
            // Check if thread made no progress (CPU time didn't increase)
            if (!madeProgress(snapshots)) {
                report.noProgressThreads.add(threadName);
            }
        }
        
        report.livelockCandidates.addAll(timedOutRunning);
        report.starvedThreads.addAll(timedOutBlocked);
        report.noProgressThreads.addAll(timedOutElsewhere);

        report.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Standardized alias for {@link #analyzeLivelocks()}.
     *
     * @return the findings this detector collected during the run
     */
    public LivelockReport analyze() {
        return analyzeLivelocks();
    }

    private boolean isStarved(List<ThreadSnapshot> snapshots) {
        if (snapshots.size() < 3) return false;
        
        // If all recent snapshots show BLOCKED or WAITING
        int recent = Math.min(5, snapshots.size());
        for (int i = snapshots.size() - recent; i < snapshots.size(); i++) {
            Thread.State state = snapshots.get(i).state;
            if (state != Thread.State.BLOCKED && state != Thread.State.WAITING) {
                return false;
            }
        }
        
        // And CPU time didn't increase
        ThreadSnapshot first = snapshots.get(0);
        ThreadSnapshot last = snapshots.get(snapshots.size() - 1);
        return last.cpuTime == first.cpuTime;
    }
    
    private boolean isRapidStateChanger(List<ThreadSnapshot> snapshots) {
        if (snapshots.size() < RECENT_SNAPSHOTS) return false;
        
        // Count state changes in recent snapshots
        int recent = Math.min(RECENT_SNAPSHOTS, snapshots.size());
        int stateChanges = 0;
        
        for (int i = snapshots.size() - recent; i < snapshots.size() - 1; i++) {
            if (snapshots.get(i).state != snapshots.get(i + 1).state) {
                stateChanges++;
            }
        }
        
        return stateChanges >= STATE_CHANGE_THRESHOLD;
    }
    
    private boolean madeProgress(List<ThreadSnapshot> snapshots) {
        if (snapshots.size() < 2) return true;
        
        ThreadSnapshot first = snapshots.get(0);
        ThreadSnapshot last = snapshots.get(snapshots.size() - 1);

        // A RUNNABLE thread is on the CPU: it is making progress by definition, and cannot be
        // starved or livelocked. Its measured CPU time can still look flat, because
        // getThreadCpuTime has a coarse tick and several snapshots can land inside one — which
        // is exactly how a busy worker was being reported as making no progress.
        if (last.state == Thread.State.RUNNABLE) return true;

        // Otherwise: progress means CPU time advanced, or the thread moved between states.
        return last.cpuTime > first.cpuTime || first.state != last.state;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        threadHistory.clear();
        observedThreads.clear();
        workersInBody.clear();
        timedOutRunning.clear();
        timedOutBlocked.clear();
        timedOutElsewhere.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }
    
    public static class LivelockReport {
        /** Threads that never got to run during the observation window. */
        public final Set<String> starvedThreads = new HashSet<>();
        /** Threads that kept running while making no progress. */
        public final Set<String> livelockCandidates = new HashSet<>();
        /** Threads that reported no progress for the whole observation window. */
        public final Set<String> noProgressThreads = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !starvedThreads.isEmpty() || !livelockCandidates.isEmpty() || !noProgressThreads.isEmpty();
        }
        
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding, worded as its text line (#801); called once before the report is returned. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.CRITICAL);
            for (String thread : starvedThreads) {
                structuredViolations.add(new Violation("Livelocks", severity,
                        thread + ": starved, never got CPU time", List.of(), Map.of(), Instant.now()));
            }
            for (String thread : livelockCandidates) {
                structuredViolations.add(new Violation("Livelocks", severity,
                        thread + ": livelock candidate, active without finishing",
                        List.of(), Map.of(), Instant.now()));
            }
            for (String thread : noProgressThreads) {
                structuredViolations.add(new Violation("Livelocks", severity,
                        thread + ": no progress", List.of(), Map.of(), Instant.now()));
            }
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No livelock or starvation issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("LIVELOCK / STARVATION ISSUES DETECTED:\n");
            
            if (!starvedThreads.isEmpty()) {
                sb.append("\nStarved Threads (never get CPU time):\n");
                for (String thread : starvedThreads) {
                    sb.append("  - ").append(thread).append("\n");
                }
            }
            
            if (!livelockCandidates.isEmpty()) {
                sb.append("\nLivelock Candidates (still running when the round timed out, or rapid state changes):\n");
                for (String thread : livelockCandidates) {
                    sb.append("  - ").append(thread).append("\n");
                }
                sb.append("  → Threads keep changing state without making progress\n");
            }
            
            if (!noProgressThreads.isEmpty()) {
                sb.append("\nThreads with No Progress:\n");
                for (String thread : noProgressThreads) {
                    sb.append("  - ").append(thread).append("\n");
                }
            }

            sb.append("\nWhy: ");
            if (!livelockCandidates.isEmpty()) {
                sb.append("Livelock threads appear active (CPU time accumulates) but make no real progress — they keep reacting to each other's state changes in a tight cycle, burning CPU indefinitely without completing any work. ");
            }
            if (!starvedThreads.isEmpty()) {
                sb.append("Starved threads are permanently blocked or never scheduled because other threads monopolize CPU or locks, causing tasks to silently stall or never execute. ");
            }
            if (!noProgressThreads.isEmpty()) {
                sb.append("Threads with no progress are neither completing work nor making forward state changes — a sign of an unnoticed block or incorrect loop condition. ");
            }
            sb.append("\nFix:\n");
            sb.append("  - Livelock: introduce randomised back-off between retries — e.g. Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50)) — so threads yield to each other rather than thrashing in lock-step\n");
            sb.append("  - Starvation: use a fair lock (new ReentrantLock(true)) so threads acquire in arrival order; reduce lock hold time; avoid priority inversion\n");
            sb.append("  - No-progress threads: check for missed notify() calls, broken loop exit conditions, or tasks submitted to a saturated executor");

            return sb.toString();
        }
    }
}
