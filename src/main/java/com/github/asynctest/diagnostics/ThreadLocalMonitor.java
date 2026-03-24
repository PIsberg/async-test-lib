package com.github.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors ThreadLocal lifecycle usage to detect leaks and poor cleanup.
 */
public class ThreadLocalMonitor {

    private static class ThreadLocalState {
        final String threadLocalName;
        final int threadLocalId;
        final Set<Long> threadsThatUsed = ConcurrentHashMap.newKeySet();
        volatile boolean initialized;
        volatile boolean cleanedUp;

        ThreadLocalState(String threadLocalName, int threadLocalId) {
            this.threadLocalName = threadLocalName;
            this.threadLocalId = threadLocalId;
        }
    }

    private final Map<Integer, ThreadLocalState> threadLocals = new ConcurrentHashMap<>();
    private final Map<Long, Set<Integer>> threadLocalsByThread = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    public void recordThreadLocalInit(ThreadLocal<?> threadLocal, String name) {
        if (!enabled || threadLocal == null) {
            return;
        }

        int id = System.identityHashCode(threadLocal);
        String resolvedName = (name == null || name.isBlank()) ? "ThreadLocal-" + id : name;
        ThreadLocalState state = threadLocals.computeIfAbsent(id, ignored -> new ThreadLocalState(resolvedName, id));
        state.initialized = true;
        recordThreadUsage(state, Thread.currentThread().threadId());
    }

    public void recordThreadLocalAccess(ThreadLocal<?> threadLocal) {
        if (!enabled || threadLocal == null) {
            return;
        }

        int id = System.identityHashCode(threadLocal);
        ThreadLocalState state = threadLocals.computeIfAbsent(id, ignored -> new ThreadLocalState("ThreadLocal-" + id, id));
        recordThreadUsage(state, Thread.currentThread().threadId());
    }

    public void recordThreadLocalCleanup(ThreadLocal<?> threadLocal) {
        if (!enabled || threadLocal == null) {
            return;
        }

        ThreadLocalState state = threadLocals.get(System.identityHashCode(threadLocal));
        if (state != null) {
            state.cleanedUp = true;
            recordThreadUsage(state, Thread.currentThread().threadId());
        }
    }

    private void recordThreadUsage(ThreadLocalState state, long threadId) {
        state.threadsThatUsed.add(threadId);
        threadLocalsByThread.computeIfAbsent(threadId, ignored -> ConcurrentHashMap.newKeySet()).add(state.threadLocalId);
    }

    public ThreadLocalReport analyzeThreadLocalLeaks() {
        ThreadLocalReport report = new ThreadLocalReport();

        for (ThreadLocalState state : threadLocals.values()) {
            if (state.initialized && !state.cleanedUp) {
                report.uncleanedThreadLocals.add(String.format(
                    "%s: accessed by %d thread(s) without remove()",
                    state.threadLocalName,
                    state.threadsThatUsed.size()
                ));
                if (state.threadsThatUsed.size() > 1) {
                    report.likelyLeaks.add(String.format(
                        "%s: value crossed %d reused thread(s)",
                        state.threadLocalName,
                        state.threadsThatUsed.size()
                    ));
                }
            }
        }

        for (Map.Entry<Long, Set<Integer>> entry : threadLocalsByThread.entrySet()) {
            if (entry.getValue().size() > 5) {
                report.threadLocalAccumulation.add(String.format(
                    "Thread %d retained %d distinct ThreadLocal values",
                    entry.getKey(),
                    entry.getValue().size()
                ));
            }
        }

        return report;
    }

    public void reset() {
        threadLocals.clear();
        threadLocalsByThread.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public static class ThreadLocalReport {
        public final Set<String> uncleanedThreadLocals = new HashSet<>();
        public final Set<String> likelyLeaks = new HashSet<>();
        public final Set<String> threadLocalAccumulation = new HashSet<>();

        public boolean hasIssues() {
            return !uncleanedThreadLocals.isEmpty()
                || !likelyLeaks.isEmpty()
                || !threadLocalAccumulation.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No ThreadLocal leaks detected.";
            }

            StringBuilder sb = new StringBuilder("THREADLOCAL LEAK RISKS DETECTED:\n");
            if (!likelyLeaks.isEmpty()) {
                sb.append("\nLikely leaks across reused threads:\n");
                for (String leak : likelyLeaks) {
                    sb.append("  - ").append(leak).append('\n');
                }
            }
            if (!uncleanedThreadLocals.isEmpty()) {
                sb.append("\nMissing cleanup:\n");
                for (String issue : uncleanedThreadLocals) {
                    sb.append("  - ").append(issue).append('\n');
                }
            }
            if (!threadLocalAccumulation.isEmpty()) {
                sb.append("\nAccumulation hotspots:\n");
                for (String accumulation : threadLocalAccumulation) {
                    sb.append("  - ").append(accumulation).append('\n');
                }
            }
            sb.append("\nFix: pair ThreadLocal.set/get with remove() in finally blocks");
            return sb.toString();
        }
    }
}
