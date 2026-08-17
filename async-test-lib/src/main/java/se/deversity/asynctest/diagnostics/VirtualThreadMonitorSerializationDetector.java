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
 * Detects a monitor that serialises a large virtual-thread fan-out - the hazard that outlived
 * pinning.
 *
 * <p>Before JDK 24, a {@code synchronized} block that blocked would pin its virtual thread to a
 * carrier, and {@link VirtualThreadPinningDetector} reports exactly that. JEP 491 removed the
 * pinning, and that detector now correctly marks monitor events obsolete on JDK 24 and later. The
 * scalability problem did not go with it: {@code synchronized} still means one thread at a time,
 * so ten thousand virtual threads reaching the same monitor are ten thousand threads in a queue.
 * The carrier is free, the throughput is not.
 *
 * <p>This is easy to miss precisely because the fix landed. The old symptom - carriers pinned,
 * the pool wedged - is gone, so a JDK 24 upgrade reads as "the pinning warnings went away" when
 * what actually happened is that the same bottleneck stopped announcing itself.
 *
 * <p>{@code LOCK_CONTENTION} does not cover this: it has no notion of a virtual thread, so it
 * scores four platform workers and four thousand virtual ones the same way. Here the queue depth
 * and the number of distinct virtual threads in it are the finding, and both are counts.
 *
 * <p>Fires when the peak number of threads waiting on one monitor reaches the contention
 * threshold (default {@value #DEFAULT_CONTENTION_THRESHOLD}) and at least two of the waiters were
 * virtual threads. A critical section short enough that waiters never pile up produces no
 * finding, and neither does a workload that reaches the monitor from one thread at a time.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.vthreadMonitorSerializationDetector();
 *
 * d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());   // about to queue
 * synchronized (lock) {
 *     d.recordMonitorAcquired(lock, Thread.currentThread());            // got in
 *     ...
 * }
 * }</pre>
 *
 * <p>The pair is enough: everything entered and not yet acquired is, by definition, queued.
 *
 * @since 1.11.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One state object per monitor identity in a ConcurrentHashMap. Queue depth is an atomic "
             + "counter and its peak is raised with a CAS retry loop, so a peak observed under contention "
             + "is never lower than the true peak.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/VirtualThreadMonitorSerializationDetectorTest.java"
)
public final class VirtualThreadMonitorSerializationDetector {

    /**
     * Peak queue depth at which a monitor counts as serialising the fan-out.
     *
     * <p>Two waiting threads is ordinary contention and says nothing about scale. Four at once on
     * one monitor is a queue, and under an unbounded virtual fan-out a queue only grows.
     */
    public static final int DEFAULT_CONTENTION_THRESHOLD = 4;

    private static final class MonitorState {
        final String        label;
        final AtomicInteger waiting     = new AtomicInteger();
        final AtomicInteger peakWaiting = new AtomicInteger();
        final AtomicInteger acquisitions = new AtomicInteger();
        final Set<Long>     virtualWaiters  = ConcurrentHashMap.newKeySet();
        final Set<Long>     platformWaiters = ConcurrentHashMap.newKeySet();

        MonitorState(String label) { this.label = label; }
    }

    private final Map<Integer, MonitorState> monitors = new ConcurrentHashMap<>();
    private final int                        contentionThreshold;
    private final int                        jdkFeatureVersion;
    private volatile boolean                 enabled = true;

    /** Creates a detector with the default threshold, evaluated against the running JDK. */
    public VirtualThreadMonitorSerializationDetector() {
        this(DEFAULT_CONTENTION_THRESHOLD, Runtime.version().feature());
    }

    /**
     * Creates a detector with an explicit threshold and JDK version.
     *
     * @param contentionThreshold peak queue depth at which a monitor is reported; values below 2
     *                            are raised to 2, since one waiter is not a queue
     * @param jdkFeatureVersion   the JDK the finding is phrased against, e.g. {@code 21} or {@code 24}
     */
    public VirtualThreadMonitorSerializationDetector(int contentionThreshold, int jdkFeatureVersion) {
        this.contentionThreshold = Math.max(contentionThreshold, 2);
        this.jdkFeatureVersion   = jdkFeatureVersion;
    }

    /**
     * Record that a thread is about to enter a monitor.
     *
     * <p>Call this immediately before the {@code synchronized} block, so a thread that queues is
     * counted even though it is not running.
     *
     * @param monitor the lock object, tracked by identity
     * @param label   a label identifying it in the report
     * @param thread  the entering thread
     */
    public void recordMonitorEnter(Object monitor, String label, Thread thread) {
        if (!enabled || monitor == null || thread == null) return;
        int id = System.identityHashCode(monitor);
        String name = label != null ? label : "monitor@" + id;
        MonitorState s = monitors.computeIfAbsent(id, k -> new MonitorState(name));
        raise(s.peakWaiting, s.waiting.incrementAndGet());
        if (isVirtual(thread)) s.virtualWaiters.add(thread.threadId());
        else s.platformWaiters.add(thread.threadId());
    }

    /**
     * Record that a thread is now inside the monitor.
     *
     * @param monitor the lock object, tracked by identity
     * @param thread  the acquiring thread
     */
    public void recordMonitorAcquired(Object monitor, Thread thread) {
        MonitorState s = state(monitor, thread);
        if (s == null) return;
        s.waiting.decrementAndGet();
        s.acquisitions.incrementAndGet();
    }

    private @Nullable MonitorState state(Object monitor, Thread thread) {
        if (!enabled || monitor == null || thread == null) return null;
        return monitors.get(System.identityHashCode(monitor));
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
     * Analyses the recorded monitor traffic and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (MonitorState s : monitors.values()) {
            int peak    = s.peakWaiting.get();
            int virtual = s.virtualWaiters.size();
            if (peak < contentionThreshold || virtual < 2) continue;

            String pinningNote = jdkFeatureVersion < 24
                    ? " On this JDK (" + jdkFeatureVersion + ") the monitor also pins the carrier, so "
                      + "VIRTUAL_THREAD_PINNING reports it too; from JDK 24 (JEP 491) only this finding remains."
                    : " On this JDK (" + jdkFeatureVersion + ") the monitor no longer pins the carrier, so "
                      + "nothing else reports it - the throughput limit is all that is left, and it is silent.";

            String msg = String.format(
                    "Monitor '%s' had %d thread(s) queued on it at once, %d of them virtual, across %d "
                    + "acquisition(s). synchronized admits one thread at a time, so an unbounded virtual "
                    + "fan-out onto this monitor is a queue rather than concurrency.%s",
                    s.label, peak, virtual, s.acquisitions.get(), pinningNote);

            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "VirtualThreadMonitorSerialization",
                    IssueSeverity.HIGH, msg, List.of(),
                    Map.of("monitor", s.label,
                           "peakWaiting", peak,
                           "virtualWaiters", virtual,
                           "platformWaiters", s.platformWaiters.size(),
                           "acquisitions", s.acquisitions.get(),
                           "jdkFeatureVersion", jdkFeatureVersion,
                           "stillPins", jdkFeatureVersion < 24),
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
            if (violations.isEmpty()) return "VIRTUAL THREAD MONITOR SERIALIZATION - clean";
            StringBuilder sb = new StringBuilder("VIRTUAL THREAD MONITOR SERIALIZATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: JEP 491 stopped synchronized from pinning a virtual thread to its carrier, which\n")
              .append("       removed the symptom and left the cause. One thread at a time is still one thread at\n")
              .append("       a time; with the pool gone there is nothing bounding how many threads arrive, so the\n")
              .append("       queue in front of the monitor is as long as the fan-out.\n")
              .append("  Fix:\n")
              .append("    - Shrink the critical section until only the state mutation is inside it; do the I/O,\n")
              .append("      the parsing and the logging outside\n")
              .append("    - Replace the monitor with something that admits concurrency: a ConcurrentHashMap, an\n")
              .append("      atomic, a striped lock, or per-task state that needs no sharing\n")
              .append("    - Where the serialisation is deliberate, bound the arrivals with a Semaphore so the\n")
              .append("      queue is explicit and has a size you chose\n");
            return sb.toString();
        }
    }
}
