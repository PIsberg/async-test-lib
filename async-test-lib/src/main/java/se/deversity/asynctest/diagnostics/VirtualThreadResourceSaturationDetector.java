package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects an unbounded fan-out of virtual threads queueing on a resource that is bounded.
 *
 * <p>JEP 444's guidance is to stop sizing the thread pool and start limiting the scarce thing
 * directly: "do not pool virtual threads; use a {@link java.util.concurrent.Semaphore} to limit
 * concurrent access to a limited resource." Skip that step and the limit does not disappear, it
 * moves. Ten thousand virtual threads against a ten-connection pool is not ten thousand
 * concurrent queries; it is ten queries and a queue nine thousand nine hundred and ninety deep,
 * which surfaces as connection-acquisition timeouts rather than as anything that looks like a
 * threading bug.
 *
 * <p>The old shape hid this. A fixed pool of eight platform threads could never ask for more than
 * eight connections, so the pool size was an accidental admission control. Removing the pool
 * removes the admission control with it, and nothing in the code says so.
 *
 * <p>One finding, and it is a count rather than an inference: {@link IssueSeverity#HIGH} when
 * more callers were waiting for the resource at one moment than the resource can ever serve at
 * once, with at least one of them a virtual thread. The fan-out outran the resource.
 *
 * <p>The detector deliberately does not report on how many callers <em>held</em> the resource at
 * once, though it would be the obvious second finding. It cannot know: a caller returns the
 * resource and then records having done so, and in that window the next caller can legitimately
 * be granted it, so an observed count above the capacity is instrumentation skew as often as it
 * is a real breach. Measuring the wait is skew-safe in the direction that matters - a thread
 * between "about to ask" and "got it" really was waiting.
 *
 * <p>A fan-out bounded by a semaphore of the resource's own size never queues more waiters than
 * the capacity, so the corrected shape produces no finding. Neither does a workload with no
 * virtual threads in it: a bounded platform pool cannot produce this hazard, and
 * {@code THREAD_POOL_DEADLOCK} already owns that ground.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.vthreadResourceSaturationDetector();
 * d.registerResource("connections", pool.getMaximumPoolSize());
 *
 * d.recordAcquireStart("connections", Thread.currentThread());   // about to queue
 * try (Connection c = pool.getConnection()) {
 *     d.recordAcquired("connections", Thread.currentThread());    // got one
 *     ...
 * }
 * }</pre>
 *
 * <p>The pair is enough: everything that started acquiring and has not yet acquired is waiting.
 *
 * @since 1.11.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per resource in a ConcurrentHashMap. Waiting and holding are atomic "
             + "counters and the peaks are maintained with a CAS retry loop, so a peak observed under "
             + "contention is never lower than the true peak.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadResourceSaturationDetectorTest.java"
)
public final class VirtualThreadResourceSaturationDetector {

    private static final class ResourceState {
        final String        name;
        final int           capacity;
        final AtomicInteger waiting     = new AtomicInteger();
        final AtomicInteger peakWaiting = new AtomicInteger();
        final Set<Long>     virtualAcquirers  = ConcurrentHashMap.newKeySet();
        final Set<Long>     platformAcquirers = ConcurrentHashMap.newKeySet();

        ResourceState(String name, int capacity) {
            this.name     = name;
            this.capacity = capacity;
        }
    }

    private final Map<String, ResourceState> resources = new ConcurrentHashMap<>();
    private volatile boolean                 enabled   = true;

    /**
     * Declare a bounded resource and how many callers it can serve at once.
     *
     * <p>The capacity is the real limit: a connection pool's maximum size, a semaphore's permit
     * count, a rate limiter's burst. Registering the same name twice keeps the first capacity.
     *
     * @param name     a label identifying the resource in the report
     * @param capacity how many callers the resource can serve concurrently; ignored when below 1
     */
    public void registerResource(String name, int capacity) {
        if (!enabled || name == null || capacity < 1) return;
        resources.computeIfAbsent(name, n -> new ResourceState(n, capacity));
    }

    /**
     * Record that a thread has begun waiting for the resource.
     *
     * <p>Call this immediately before the blocking acquisition, so the wait is visible even when
     * the caller never gets in.
     *
     * @param name   the resource, as registered
     * @param thread the waiting thread
     */
    public void recordAcquireStart(String name, Thread thread) {
        ResourceState s = state(name, thread);
        if (s == null) return;
        raise(s.peakWaiting, s.waiting.incrementAndGet());
        if (isVirtual(thread)) s.virtualAcquirers.add(thread.threadId());
        else s.platformAcquirers.add(thread.threadId());
    }

    /**
     * Record that a thread has obtained the resource.
     *
     * @param name   the resource, as registered
     * @param thread the acquiring thread
     */
    public void recordAcquired(String name, Thread thread) {
        ResourceState s = state(name, thread);
        if (s == null) return;
        s.waiting.decrementAndGet();
    }

    private @Nullable ResourceState state(String name, Thread thread) {
        if (!enabled || name == null || thread == null) return null;
        return resources.get(name);   // an unregistered resource has no capacity to compare against
    }

    /** Raises {@code peak} to {@code observed} if it is higher, retrying against concurrent raisers. */
    private static void raise(AtomicInteger peak, int observed) {
        int current = peak.get();
        while (observed > current && !peak.compareAndSet(current, observed)) {
            current = peak.get();
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
     * Analyses the recorded acquisitions and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (ResourceState s : resources.values()) {
            int peakWaiting = s.peakWaiting.get();
            int virtual  = s.virtualAcquirers.size();
            int platform = s.platformAcquirers.size();

            if (peakWaiting > s.capacity && virtual > 0) {
                String msg = String.format(
                        "Resource '%s' has capacity %d, and %d caller(s) were waiting for it at once "
                        + "(%d virtual thread(s), %d platform thread(s)). The virtual fan-out is unbounded "
                        + "and the resource is not, so the extra callers are queueing, not working - the "
                        + "cost shows up as acquisition timeouts rather than as a threading failure.",
                        s.name, s.capacity, peakWaiting, virtual, platform);
                r.violations.add(msg);
                r.structuredViolations.add(new Violation(
                        "VirtualThreadResourceSaturation",
                        IssueSeverity.HIGH, msg, List.of(),
                        Map.of("resource", s.name,
                               "capacity", s.capacity,
                               "peakWaiting", peakWaiting,
                               "virtualAcquirers", virtual,
                               "platformAcquirers", platform),
                        Instant.now()));
            }
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
            if (violations.isEmpty()) return "VIRTUAL THREAD RESOURCE SATURATION - clean";
            StringBuilder sb = new StringBuilder("VIRTUAL THREAD RESOURCE SATURATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: a fixed thread pool was accidental admission control - eight workers could never\n")
              .append("       ask for a ninth connection. Virtual threads remove the pool and remove that limit\n")
              .append("       with it, but the connection pool, the rate limiter and the downstream service are\n")
              .append("       all still bounded. The queue moves rather than disappearing.\n")
              .append("  Fix:\n")
              .append("    - Bound the scarce thing, not the threads: a Semaphore sized to the resource, acquired\n")
              .append("      around the call and released in a finally\n")
              .append("    - Size that semaphore from the resource itself (pool.getMaximumPoolSize()), so the two\n")
              .append("      cannot drift apart\n")
              .append("    - Do not reintroduce a pool of virtual threads to get the limit back - that is the\n")
              .append("      anti-pattern VIRTUAL_THREAD_POOLING reports\n");
            return sb.toString();
        }
    }
}
