package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors ThreadLocal lifecycle usage to detect leaks and poor cleanup.
 */
public class ThreadLocalMonitor {

    private static class ThreadLocalState {
        final String threadLocalName;
        /** Threads that touched this thread-local in the round in progress; folded at each round start. */
        final Set<Long> threadsThatUsed = ConcurrentHashMap.newKeySet();
        /**
         * Threads that set or read this thread-local in the round in progress and have not
         * removed it since. A value lives in one thread's map, so only that thread's
         * {@code remove()} clears it: one flag for the whole run let a remove on one thread, or
         * in one round, stand in for every other (#565).
         */
        final Set<Long> holding = ConcurrentHashMap.newKeySet();
        /** The widest single round seen so far, which is what a finding reports. */
        volatile int maxRoundThreads;
        volatile boolean initialized;
        /** Whether any round ended with a thread still holding a value. */
        volatile boolean leftHeld;

        ThreadLocalState(String threadLocalName) {
            this.threadLocalName = threadLocalName;
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
            if (!holding.isEmpty()) {
                leftHeld = true;
            }
            holding.clear();
        }
    }

    /**
     * Keyed by identity. A bare {@code System.identityHashCode} key merged two thread-locals whose
     * hashes collided into one entry, so one's cleanup covered the other's leak (#564).
     */
    private final Map<ThreadLocal<?>, ThreadLocalState> threadLocals =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Per thread, the thread-locals it holds a value for right now: a removed one is not retained. */
    private final Map<Long, Set<ThreadLocalState>> threadLocalsByThread = new ConcurrentHashMap<>();
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
        ThreadLocalState state = threadLocals.computeIfAbsent(threadLocal, ignored -> new ThreadLocalState(resolvedName));
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
        ThreadLocalState state = threadLocals.computeIfAbsent(threadLocal, ignored -> new ThreadLocalState("ThreadLocal-" + id));
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

        ThreadLocalState state = threadLocals.get(threadLocal);
        if (state != null) {
            long threadId = Thread.currentThread().threadId();
            state.threadsThatUsed.add(threadId);
            state.holding.remove(threadId);
            Set<ThreadLocalState> held = threadLocalsByThread.get(threadId);
            if (held != null) {
                held.remove(state);
            }
        }
    }

    private void recordThreadUsage(ThreadLocalState state, long threadId) {
        state.threadsThatUsed.add(threadId);
        state.holding.add(threadId);
        threadLocalsByThread.computeIfAbsent(threadId, ignored -> ConcurrentHashMap.newKeySet()).add(state);
    }

    private List<ThreadLocalState> states() {
        synchronized (threadLocals) {
            return new ArrayList<>(threadLocals.values());
        }
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
     * @since 1.10.0
     */
    public void markInvocationStart() {
        for (ThreadLocalState state : states()) {
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

        for (ThreadLocalState state : states()) {
            // The final round has not been folded by a round start.
            state.foldRound();
            int threads = state.maxRoundThreads;

            if (state.initialized && state.leftHeld) {
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

        for (Map.Entry<Long, Set<ThreadLocalState>> entry : threadLocalsByThread.entrySet()) {
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
