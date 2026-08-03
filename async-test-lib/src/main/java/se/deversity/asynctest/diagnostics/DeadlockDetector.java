package se.deversity.asynctest.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Enhanced deadlock detector that analyzes thread dumps and identifies
 * circular lock dependencies, thread states, and provides actionable diagnostics.
 *
 * <p>Instances baseline the JVM at construction: threads already deadlocked when the
 * detector is created (e.g. leaked by an earlier test in a shared JVM) are excluded
 * from {@link #analyze()}, so only deadlocks that form during the monitored test are
 * reported. The static {@link #hasDeadlock()} remains a JVM-wide snapshot by design.
 */
public class DeadlockDetector {

    /**
     * Thread ids that were already deadlocked when this detector was created.
     * Deadlocked threads never terminate, so their ids cannot be recycled and
     * remain valid exclusion keys for the detector's lifetime.
     */
    private final Set<Long> preexistingDeadlockedThreads;
    /**
     * Creates a DeadlockDetector.
     */
    public DeadlockDetector() {
        long[] existing = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (existing == null || existing.length == 0) {
            preexistingDeadlockedThreads = Set.of();
        } else {
            Set<Long> ids = new HashSet<>();
            for (long id : existing) {
                ids.add(id);
            }
            preexistingDeadlockedThreads = Set.copyOf(ids);
        }
    }
    /**
     * Prints thread dump to the report output.
     */
    public static void printThreadDump() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        System.err.println("\n=======================================================");
        System.err.println("   ASYNC-TEST DEADLOCK / TIMEOUT DETECTED");
        System.err.println("   ENHANCED THREAD DUMP WITH LOCK ANALYSIS");
        System.err.println("=======================================================\n");

        // Get detailed thread info with locks
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

        // Print severity
        System.err.println(IssueSeverity.CRITICAL.format() + ": Application threads are deadlocked");
        System.err.println("Impact: " + IssueSeverity.CRITICAL.getDescription());
        System.err.println();

        // Print raw thread dump first
        System.err.println("=== RAW THREAD DUMP ===\n");
        for (ThreadInfo threadInfo : threadInfos) {
            System.err.println(threadInfo.toString());
        }

        System.err.println("\n=== LOCK ANALYSIS ===\n");

        // Analyze deadlocks and lock chains
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        if (deadlockedThreads != null && deadlockedThreads.length > 0) {
            System.err.println("*** CIRCULAR DEADLOCK DETECTED ***");
            System.err.println("Deadlocked threads: " + Arrays.toString(deadlockedThreads));
            System.err.println();

            // Get detailed info on deadlocked threads
            Map<Long, ThreadInfo> threadMap = new HashMap<>();
            for (ThreadInfo ti : threadInfos) {
                threadMap.put(ti.getThreadId(), ti);
            }

            System.err.println("┌────────────────────────────────────────────────────────┐");
            System.err.println("│               CIRCULAR DEADLOCK GRAPH                  │");
            System.err.println("└────────────────────────────────────────────────────────┘");

            Set<Long> visited = new HashSet<>();
            int cycleNum = 1;
            for (long threadId : deadlockedThreads) {
                if (visited.contains(threadId)) {
                    continue;
                }

                java.util.List<ThreadInfo> cycle = new java.util.ArrayList<>();
                long currentId = threadId;
                Set<Long> path = new java.util.LinkedHashSet<>();
                while (currentId >= 0 && !path.contains(currentId)) {
                    ThreadInfo ti = threadMap.get(currentId);
                    if (ti == null) break;
                    cycle.add(ti);
                    path.add(currentId);
                    currentId = ti.getLockOwnerId();
                }

                if (path.contains(currentId)) {
                    int startIdx = 0;
                    for (int i = 0; i < cycle.size(); i++) {
                        if (cycle.get(i).getThreadId() == currentId) {
                            startIdx = i;
                            break;
                        }
                    }
                    java.util.List<ThreadInfo> activeCycle = cycle.subList(startIdx, cycle.size());
                    for (ThreadInfo ti : activeCycle) {
                        visited.add(ti.getThreadId());
                    }

                    System.err.println("Cycle #" + cycleNum + ":");
                    cycleNum++;
                    for (ThreadInfo ti : activeCycle) {
                        System.err.println(String.format("  [Thread-%d \"%s\"]", ti.getThreadId(), ti.getThreadName()));
                        System.err.println(String.format("     │   waiting for lock: %s", ti.getLockName()));
                        System.err.println(String.format("     ▼   held by lock owner: Thread-%d", ti.getLockOwnerId()));
                    }
                    ThreadInfo first = activeCycle.get(0);
                    System.err.println(String.format("  [Thread-%d \"%s\"] (CYCLE START)", first.getThreadId(), first.getThreadName()));
                    System.err.println();
                }
            }
            
            // Also print original detailed thread information for completeness
            System.err.println("=== DETAILED DEADLOCKED THREAD INFOS ===\n");
            for (long threadId : deadlockedThreads) {
                ThreadInfo ti = threadMap.get(threadId);
                if (ti != null) {
                    printLockChain(ti, threadMap);
                }
            }
        } else {
            System.err.println("No circular deadlocks detected, but test timed out.");
            System.err.println("Possible causes: Thread starvation, livelock, or infinite loops.\n");
            
            // Print threads that are BLOCKED or WAITING
            System.err.println("--- Blocked/Waiting Threads ---");
            for (ThreadInfo ti : threadInfos) {
                if (ti.getThreadState() == Thread.State.BLOCKED ||
                    ti.getThreadState() == Thread.State.WAITING ||
                    ti.getThreadState() == Thread.State.TIMED_WAITING) {
                    System.err.println(ti.getThreadName() + " (" + ti.getThreadState() + ")");
                    if (ti.getLockName() != null) {
                        System.err.println("  Waiting on: " + ti.getLockName());
                    }
                    if (ti.getLockOwnerId() >= 0) {
                        System.err.println("  Lock owner: Thread-" + ti.getLockOwnerId());
                    }
                }
            }
        }
        
        System.err.println("\n=======================================================\n");
    }

    private static void printLockChain(ThreadInfo thread, Map<Long, ThreadInfo> threadMap) {
        System.err.println("Thread-" + thread.getThreadId() + " (" + thread.getThreadName() + "):");
        System.err.println("  State: " + thread.getThreadState());
        
        if (thread.getLockName() != null) {
            System.err.println("  Waiting for lock: " + thread.getLockName());
        }
        
        if (thread.getLockOwnerId() >= 0) {
            System.err.println("  Lock held by: Thread-" + thread.getLockOwnerId());
            
            ThreadInfo lockHolder = threadMap.get(thread.getLockOwnerId());
            if (lockHolder != null) {
                System.err.println("  -> Which is waiting for: " + 
                    (lockHolder.getLockName() != null ? lockHolder.getLockName() : "nothing"));
            }
        }
        
        // Print locked monitors
        MonitorInfo[] monitors = thread.getLockedMonitors();
        if (monitors != null && monitors.length > 0) {
            System.err.println("  Holds monitors:");
            for (MonitorInfo monitor : monitors) {
                System.err.println("    - " + monitor.getClassName() + "@" + monitor.getIdentityHashCode());
            }
        }
        
        System.err.println();
    }

    /**
     * Checks if any threads are in a deadlocked state (blocking on each other's locks).
     * Returns true if a deadlock is detected.
     *
     * <p>This is a JVM-wide snapshot: unlike {@link #analyze()}, it also reports deadlocks
     * that predate any particular detector instance.
     *
     * @return {@code true} when a lock-order cycle was observed
     */
    public static boolean hasDeadlock() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        return deadlockedThreads != null && deadlockedThreads.length > 0;
    }

    /**
     * Get a summary of current lock contention.
     *
     * @return a human-readable summary of lock contention, for the report
     */
    @SuppressWarnings("PMD.AssignmentInOperand") // counter++ in switch arrow case is idiomatic
    public static String getLockContentionSummary() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        
        int blocked = 0;
        int waiting = 0;
        int running = 0;
        for (ThreadInfo ti : threadInfos) {
            switch (ti.getThreadState()) {
                case BLOCKED -> blocked++;
                case WAITING, TIMED_WAITING -> waiting++;
                case RUNNABLE -> running++;
                default -> { /* NEW, TERMINATED — not counted */ }
            }
        }
        
        return String.format("Running: %d, Waiting: %d, Blocked: %d", running, waiting, blocked);
    }

    /**
     * Print learning content and auto-fix suggestions for deadlocks.
     */
    public static void printLearningAndFix() {
        System.err.println("\n" + "=".repeat(60));
        System.err.println(LearningContent.getDeadlockExplanation());
        System.err.println(AutoFix.getDeadlockFix());
        System.err.println("=".repeat(60) + "\n");
    }

    /**
     * Analyze current JVM thread state for deadlocks.
     * Queries the JVM via JMX and returns a report suitable for use in {@link se.deversity.asynctest.DetectorRegistry}.
     *
     * <p>Deadlocks that already existed when this detector was constructed are excluded,
     * so a report with issues always means the monitored test introduced a new deadlock.
     *
     * @return the findings this detector collected during the run
     */
    public DeadlockReport analyze() {
        long[] current = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (current == null) {
            return new DeadlockReport(false);
        }
        for (long id : current) {
            if (!preexistingDeadlockedThreads.contains(id)) {
                return new DeadlockReport(true);
            }
        }
        return new DeadlockReport(false);
    }

    public static class DeadlockReport {
        private final boolean deadlocked;
        /**
         * Creates a DeadlockReport.
         *
         * @param deadlocked the {@code deadlocked} flag
         */
        public DeadlockReport(boolean deadlocked) {
            this.deadlocked = deadlocked;
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return deadlocked;
        }

        @Override
        public String toString() {
            if (!deadlocked) {
                return "No deadlocks detected.";
            }
            // The severity marker must live in the report itself: the runner infers a
            // finding's severity with IssueSeverity.fromReport(), and an untagged report
            // falls through to the HIGH default — which failOn = CRITICAL does not trip.
            // A deadlock is the canonical CRITICAL finding, so it must say so here.
            return IssueSeverity.CRITICAL.format() + ": DEADLOCK DETECTED\n"
                + "  Circular lock dependency found between JVM threads.\n"
                + "  Why: Thread A holds lock X and waits for lock Y; Thread B holds lock Y and waits for lock X.\n"
                + "       Neither can proceed — both are blocked forever.\n"
                + "  Fix:\n"
                + "    - Establish a consistent global lock-ordering: always acquire locks in the same order\n"
                + "    - Use tryLock() with a timeout instead of unconditional lock() to break cycles\n"
                + "    - Reduce lock scope: restructure code to avoid holding one lock while acquiring another\n"
                + "  Run DeadlockDetector.printThreadDump() for a full lock-chain diagnosis.";
        }
    }
}
