package se.deversity.asynctest.diagnostics;

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
        /** Threads that touched this thread-local in the round in progress; folded at each round start. */
        final Set<Long> threadsThatUsed = ConcurrentHashMap.newKeySet();
        /** The widest single round seen so far, which is what a finding reports. */
        volatile int maxRoundThreads;
        volatile boolean initialized;
        volatile boolean cleanedUp;

        ThreadLocalState(String threadLocalName, int threadLocalId) {
            this.threadLocalName = threadLocalName;
            this.threadLocalId = threadLocalId;
        }

        /**
         * Folds the round in progress into the per-round maximum and starts the next one.
         *
         * <p>Without this the set accumulated thread ids across the whole run, and
         * {@code useVirtualThreads = true} - the default - gives every body execution a fresh
         * virtual thread with a fresh id. A {@code threads = 8, invocations = 20} run therefore
         * reported 160 threads, which is the number of body executions, not the number of
         * threads the reader configured. Called on the runner thread between rounds, when no
         * worker is running, and once more at analysis for the final round.
         */
        void foldRound() {
            int seen = threadsThatUsed.size();
            if (seen > maxRoundThreads) {
                maxRoundThreads = seen;
            }
            threadsThatUsed.clear();
        }
    }

    private final Map<Integer, ThreadLocalState> threadLocals = new ConcurrentHashMap<>();
    private final Map<Long, Set<Integer>> threadLocalsByThread = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    /**
     * Records thread local init so it can be analysed at the end of the run.
     *
     * @param threadLocal the thread-local being recorded, tracked by identity
     * @param name a label identifying the thread local in the report
     */
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
    /**
     * Records thread local access so it can be analysed at the end of the run.
     *
     * @param threadLocal the thread-local being recorded, tracked by identity
     */
    public void recordThreadLocalAccess(ThreadLocal<?> threadLocal) {
        if (!enabled || threadLocal == null) {
            return;
        }

        int id = System.identityHashCode(threadLocal);
        ThreadLocalState state = threadLocals.computeIfAbsent(id, ignored -> new ThreadLocalState("ThreadLocal-" + id, id));
        recordThreadUsage(state, Thread.currentThread().threadId());
    }
    /**
     * Records thread local cleanup so it can be analysed at the end of the run.
     *
     * @param threadLocal the thread-local being recorded, tracked by identity
     */
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

    /**
     * Marks the start of a new invocation round.
     *
     * <p>Threads are counted per round from here on: a thread that touched the thread-local in
     * an earlier round is not counted together with one from this round, because the harness
     * orders rounds and the two never coexisted. Called by {@code ConcurrencyRunner} before
     * every round; a caller that never calls it measures one round, which is what the manual
     * API did before.
     *
     * @since 1.9.9
     */
    public void markInvocationStart() {
        for (ThreadLocalState state : threadLocals.values()) {
            state.foldRound();
        }
    }
    /**
     * Analyses what has been recorded about thread local leaks and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadLocalReport analyzeThreadLocalLeaks() {
        ThreadLocalReport report = new ThreadLocalReport();

        for (ThreadLocalState state : threadLocals.values()) {
            // The final round has not been folded by a round start.
            state.foldRound();
            int threads = state.maxRoundThreads;

            if (state.initialized && !state.cleanedUp) {
                report.uncleanedThreadLocals.add(String.format(
                    "%s: accessed by %d thread(s) without remove()",
                    state.threadLocalName,
                    threads
                ));
                if (threads > 1) {
                    // Deliberately not "crossed N reused threads". Under the default
                    // useVirtualThreads = true runner nothing is reused: each body execution
                    // gets its own virtual thread, whose ThreadLocal map dies with it. The
                    // finding is still right - a set with no remove leaks the moment the code
                    // runs on a pooled platform thread - but arguing for it with reuse that did
                    // not happen made the evidence line false. See issue #349.
                    report.likelyLeaks.add(String.format(
                        "%s: set on %d thread(s) with no matching remove(); on a pooled thread "
                        + "the value outlives the task and the next task sees it",
                        state.threadLocalName,
                        threads
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

    /**
     * Standardized alias for {@link #analyzeThreadLocalLeaks()}.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadLocalReport analyze() {
        return analyzeThreadLocalLeaks();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        threadLocals.clear();
        threadLocalsByThread.clear();
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

    public static class ThreadLocalReport {
        /** Thread-locals never removed before the thread was returned to its pool. */
        public final Set<String> uncleanedThreadLocals = new HashSet<>();
        /** Thread-locals set by more than one thread in a round and never removed. */
        public final Set<String> likelyLeaks = new HashSet<>();
        /** Thread-locals whose stored value grew across reused threads. */
        public final Set<String> threadLocalAccumulation = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
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
                sb.append("\nSet without remove(), on more than one thread:\n");
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
