package se.deversity.asynctest.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects thread starvation and livelocks.
 * 
 * Livelock: Threads keep changing state in response to each other without making progress.
 * Starvation: Some threads never get CPU time due to greedy threads monopolizing it.
 * 
 * This detector monitors:
 * - Thread state transitions
 * - CPU usage (via getThreadCpuTime)
 * - Lock contention patterns
 * - Detection of circular waiting without actual deadlock
 */
public class LivelockDetector {
    
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
     * Ids of the threads running the test. Populated by {@link #captureSnapshot()}, which the
     * runner calls from each worker's own finally block. Snapshots are kept only for these —
     * a JVM daemon parked in WAITING is not a starved test worker.
     */
    private final Set<Long> observedThreads = ConcurrentHashMap.newKeySet();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private volatile boolean enabled = true;
    
    /**
     * Capture current thread state. Call periodically during test execution.
     */
    public void captureSnapshot() {
        if (!enabled) return;

        // The runner calls this from each worker's own finally block, so whoever is calling is
        // a thread running the test. That is how we learn which threads are ours.
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
        
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeLivelocks()}.
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
        if (snapshots.size() < 10) return false;
        
        // Count state changes in recent snapshots
        int recent = Math.min(10, snapshots.size());
        int stateChanges = 0;
        
        for (int i = snapshots.size() - recent; i < snapshots.size() - 1; i++) {
            if (snapshots.get(i).state != snapshots.get(i + 1).state) {
                stateChanges++;
            }
        }
        
        // 5+ state changes in 10 snapshots suggests rapid cycling (potential livelock)
        return stateChanges >= 5;
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
        /** The starved threads. */
        public final Set<String> starvedThreads = new HashSet<>();
        /** The livelock candidates. */
        public final Set<String> livelockCandidates = new HashSet<>();
        /** The no progress threads. */
        public final Set<String> noProgressThreads = new HashSet<>();
        
        /** {@return whether there are issues} */
        public boolean hasIssues() {
            return !starvedThreads.isEmpty() || !livelockCandidates.isEmpty() || !noProgressThreads.isEmpty();
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
                sb.append("\nLivelock Candidates (rapid state changes):\n");
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
