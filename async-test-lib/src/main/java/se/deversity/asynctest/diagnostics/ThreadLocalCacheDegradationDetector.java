package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a {@link ThreadLocal} that was a cache under a thread pool and became an allocator
 * under virtual threads.
 *
 * <p>{@code ThreadLocal<SimpleDateFormat>} is the standard fix for a non-thread-safe helper, and
 * on a pool it is a good one: eight workers means eight formatters, created once and reused for
 * the life of the process. The instance count is bounded by the pool, which is why nobody counts
 * it.
 *
 * <p>Virtual threads remove that bound. A thread per task means an instance per task, so the same
 * line of code now allocates a formatter, a buffer or a parser for every request and throws it
 * away - and each one is retained for as long as its thread lives. Nothing fails; the object is
 * still confined to one thread, so it is still correct. It is simply no longer a cache, and the
 * code reads exactly as it did when it was one.
 *
 * <p>{@code VIRTUAL_THREAD_CONTEXT_LEAKS} does not see this. It counts distinct
 * {@code ThreadLocal} <em>keys</em> per thread and reports a thread carrying too many of them,
 * which is a different question: here there is one key, and the problem is how many
 * <em>instances</em> that one key produced.
 *
 * <p>The finding is a count of distinct instances, taken by identity: a key whose value is the
 * same object on every thread is a shared constant and is never reported, and a key touched only
 * by platform threads is out of scope, because on a pool the bound is real.
 *
 * <p>Usage inside {@code @AsyncTest} - record the value each thread ends up with:
 * <pre>{@code
 * private static final ThreadLocal<SimpleDateFormat> FORMAT =
 *         ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
 *
 * SimpleDateFormat f = FORMAT.get();
 * AsyncTestContext.threadLocalCacheDegradationDetector()
 *         .recordCachedValue("FORMAT", f, Thread.currentThread());
 * }</pre>
 *
 * @since 1.9.5
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per ThreadLocal name in a ConcurrentHashMap; instance identities and "
             + "thread ids are concurrent key-set views, so counting is idempotent under repeated "
             + "recording from the same thread - a value read twice adds nothing.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ThreadLocalCacheDegradationDetectorTest.java"
)
public final class ThreadLocalCacheDegradationDetector {

    /**
     * How many distinct instances one key must produce on virtual threads before it is reported.
     *
     * <p>Low enough to fire in a test that runs a handful of virtual threads, since the shape is
     * what matters and production will multiply it by the request rate.
     */
    public static final int DEFAULT_INSTANCE_THRESHOLD = 4;

    private static final class CacheState {
        final String    name;
        final Set<Integer> virtualInstanceIds  = ConcurrentHashMap.newKeySet();
        final Set<Integer> platformInstanceIds = ConcurrentHashMap.newKeySet();
        final Set<Long>    virtualThreadIds    = ConcurrentHashMap.newKeySet();
        final Set<Long>    platformThreadIds   = ConcurrentHashMap.newKeySet();
        volatile String    valueType = "?";

        CacheState(String name) { this.name = name; }
    }

    private final Map<String, CacheState> caches = new ConcurrentHashMap<>();
    private final int                     instanceThreshold;
    private volatile boolean              enabled = true;

    /** Creates a detector with the default instance threshold. */
    public ThreadLocalCacheDegradationDetector() {
        this(DEFAULT_INSTANCE_THRESHOLD);
    }

    /**
     * Creates a detector with an explicit threshold.
     *
     * @param instanceThreshold how many distinct instances one key must produce on virtual threads
     *                          before it is reported; values below 2 are raised to 2
     */
    public ThreadLocalCacheDegradationDetector(int instanceThreshold) {
        this.instanceThreshold = Math.max(instanceThreshold, 2);
    }

    /**
     * Record the value a thread obtained from a {@link ThreadLocal}.
     *
     * <p>Call this after {@code get()}, with the value itself: the detector counts distinct
     * instances by identity, so recording the same object repeatedly from the same thread costs
     * nothing and changes no count.
     *
     * @param threadLocalName a label identifying the ThreadLocal in the report
     * @param value           the value this thread holds; {@code null} is ignored
     * @param thread          the holding thread
     */
    public void recordCachedValue(String threadLocalName, Object value, Thread thread) {
        if (!enabled || value == null || thread == null) return;
        String name = threadLocalName != null ? threadLocalName : "threadLocal";
        CacheState s = caches.computeIfAbsent(name, CacheState::new);
        s.valueType = value.getClass().getSimpleName();
        if (isVirtual(thread)) {
            s.virtualInstanceIds.add(System.identityHashCode(value));
            s.virtualThreadIds.add(thread.threadId());
        } else {
            s.platformInstanceIds.add(System.identityHashCode(value));
            s.platformThreadIds.add(thread.threadId());
        }
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return thread.isVirtual();
        } catch (Throwable t) {
            return false;   // pre-21 runtime: no virtual threads to find
        }
    }

    /** Turn recording off; already-recorded state is kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded values and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (CacheState s : caches.values()) {
            int instances = s.virtualInstanceIds.size();
            int threads   = s.virtualThreadIds.size();
            if (instances < instanceThreshold || threads < instanceThreshold) continue;

            // One instance per virtual thread is the degenerate case: nothing is being reused.
            // Fewer instances than threads means something is shared, which is the point of a cache.
            if (instances < threads) continue;

            String platformNote = s.platformThreadIds.isEmpty()
                    ? ""
                    : String.format(" The same key produced %d instance(s) across %d platform thread(s), "
                                    + "which is the bounded behaviour this replaced.",
                                    s.platformInstanceIds.size(), s.platformThreadIds.size());

            String msg = String.format(
                    "ThreadLocal '%s' produced %d distinct %s instance(s) across %d virtual thread(s) - one "
                    + "per thread, so nothing is being reused. On a pool this key would hold at most one "
                    + "instance per worker and act as a cache; with a thread per task it allocates per task "
                    + "and retains each instance for that thread's life.%s",
                    s.name, instances, s.valueType, threads, platformNote);

            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "ThreadLocalCacheDegradation",
                    IssueSeverity.MEDIUM, msg, List.of(),
                    Map.of("threadLocal", s.name,
                           "valueType", s.valueType,
                           "virtualInstances", instances,
                           "virtualThreads", threads,
                           "platformInstances", s.platformInstanceIds.size(),
                           "platformThreads", s.platformThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "THREAD LOCAL CACHE DEGRADATION - clean";
            StringBuilder sb = new StringBuilder("THREAD LOCAL CACHE DEGRADATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: a ThreadLocal is a cache only because the thread count is bounded. A pool of eight\n")
              .append("       workers means eight instances for the life of the process; a thread per task means\n")
              .append("       one instance per task. The code is unchanged and still correct - the object is still\n")
              .append("       confined to one thread - it has just stopped amortising anything.\n")
              .append("  Fix:\n")
              .append("    - Prefer an immutable, shareable replacement and drop the ThreadLocal: DateTimeFormatter\n")
              .append("      for SimpleDateFormat, a compiled Pattern shared with per-use matchers\n")
              .append("    - Where the helper must be mutable, pool the helper rather than the thread: borrow from\n")
              .append("      a small bounded pool for the call and return it in a finally\n")
              .append("    - If the value is genuinely per-task and cheap, say so in a comment - the finding is\n")
              .append("      about a cache that no longer caches, not about per-task state as such\n");
            return sb.toString();
        }
    }
}
