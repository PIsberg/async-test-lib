package com.github.asynctest.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * Enhanced deadlock detector that analyzes thread dumps and identifies
 * circular lock dependencies, thread states, and provides actionable diagnostics.
 */
public class DeadlockDetector {

    private static final int LOCK_CHAIN_MAX_DEPTH = 20;

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

            for (long threadId : deadlockedThreads) {
                ThreadInfo ti = threadMap.get(threadId);
                if (ti != null) {
                    printLockChain(ti, threadInfos, threadMap);
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

    private static void printLockChain(ThreadInfo thread, ThreadInfo[] allThreads, Map<Long, ThreadInfo> threadMap) {
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
     */
    public static boolean hasDeadlock() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        return deadlockedThreads != null && deadlockedThreads.length > 0;
    }

    /**
     * Get a summary of current lock contention.
     */
    public static String getLockContentionSummary() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
        
        int blocked = 0, waiting = 0, running = 0;
        for (ThreadInfo ti : threadInfos) {
            switch (ti.getThreadState()) {
                case BLOCKED -> blocked++;
                case WAITING, TIMED_WAITING -> waiting++;
                case RUNNABLE -> running++;
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
}
