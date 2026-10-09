package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects potential carrier thread exhaustion caused by concurrent blocking of virtual threads.
 *
 * <p>The virtual thread scheduler runs on a {@link java.util.concurrent.ForkJoinPool} whose
 * parallelism defaults to the number of available CPU cores. When multiple virtual threads are
 * simultaneously <em>pinned</em> (e.g. inside {@code synchronized} blocks) or blocked on
 * operations that cannot unmount them from the carrier, all carrier threads may become
 * occupied. This is <strong>carrier exhaustion</strong>: no carrier is free to schedule
 * other virtual threads, causing apparent deadlock or severe starvation even though no
 * classic deadlock exists.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Carrier exhaustion risk</b> — The peak number of concurrently blocked virtual
 *       threads approached or exceeded the available carrier thread count.</li>
 *   <li><b>Sustained exhaustion</b> — Multiple invocations reached the exhaustion threshold,
 *       suggesting a systemic problem rather than an occasional spike.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * private final Object lock = new Object();
 *
 * @AsyncTest(threads = 20, useVirtualThreads = true, includes = DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION)
 * void testPotentialExhaustion() {
 *     var detector = AsyncTestContext.virtualThreadCarrierExhaustionDetector();
 *     detector.recordBlockingStart("synchronized-lock");
 *     try {
 *         synchronized (lock) {
 *             Thread.sleep(10);
 *         }
 *     } finally {
 *         detector.recordBlockingEnd("synchronized-lock");
 *     }
 * }
 * }</pre>
 *
 * @since 0.7.0
 */
public class VirtualThreadCarrierExhaustionDetector {

    private final int carrierCount;

    private final AtomicInteger concurrentlyBlocked = new AtomicInteger(0);
    private final AtomicInteger peakConcurrentlyBlocked = new AtomicInteger(0);
    private final AtomicInteger exhaustionEvents = new AtomicInteger(0);
    private final List<String> exhaustionDetails = Collections.synchronizedList(new ArrayList<>());
    /**
     * The threads blocked now, each with its outermost reason and how many recorded blocks it is
     * nested in. A thread counts once however deep it is: a synchronized block around a native
     * call records two starts on one thread, and that is one pinned carrier, not two (#964).
     */
    private final Map<Long, Block> activeBlocksByThread = new ConcurrentHashMap<>();

    /** One thread's open blocks: the outermost reason and the nesting depth. */
    private record Block(String reason, int depth) {
        Block nested() {
            return new Block(reason, depth + 1);
        }
    }
    /**
     * Creates a VirtualThreadCarrierExhaustionDetector.
     */
    public VirtualThreadCarrierExhaustionDetector() {
        this(availableCarriers());
    }

    VirtualThreadCarrierExhaustionDetector(int carrierCount) {
        this.carrierCount = carrierCount;
    }

    /**
     * Record that the current virtual thread is entering a blocking operation that
     * may pin or hold a carrier thread.
     *
     * @param reason a short description of why the thread is blocking
     *               (e.g. {@code "synchronized-lock"}, {@code "native-call"})
     */
    public void recordBlockingStart(String reason) {
        recordBlockingStart(reason, Thread.currentThread());
    }

    /**
     * {@return how many virtual threads blocked at once read as exhausting the carriers: one per
     * carrier, since a pinned virtual thread holds its carrier while it blocks (#756)}
     */
    private int exhaustionThreshold() {
        return carrierCount;
    }

    /**
     * Record that the specified thread is entering a blocking operation.
     *
     * @param reason  description of the blocking operation
     * @param thread  the thread entering the blocking state
     */
    public void recordBlockingStart(String reason, Thread thread) {
        if (!thread.isVirtual()) return;

        Block block = activeBlocksByThread.compute(thread.threadId(), (id, open) -> open == null
                ? new Block(reason != null ? reason : "unknown", 1) : open.nested());
        if (block.depth() > 1) {
            return;   // already blocked, and already counted
        }
        int current = concurrentlyBlocked.incrementAndGet();
        peakConcurrentlyBlocked.updateAndGet(max -> Math.max(max, current));

        if (current >= exhaustionThreshold()) {
            exhaustionEvents.incrementAndGet();
            exhaustionDetails.add(String.format(
                "Carrier exhaustion risk: %d virtual threads concurrently blocked "
                + "(carrier count=%d, trigger reason='%s', thread id=%d)",
                current, carrierCount, reason != null ? reason : "unknown", thread.threadId()
            ));
        }
    }

    /**
     * Record that the current virtual thread has unblocked and released its carrier.
     *
     * @param reason the same reason passed to {@link #recordBlockingStart(String)}
     */
    public void recordBlockingEnd(String reason) {
        recordBlockingEnd(reason, Thread.currentThread());
    }

    /**
     * Record that the specified thread has unblocked.
     *
     * @param reason  the reason passed to {@link #recordBlockingStart(String, Thread)}
     * @param thread  the thread that has unblocked
     */
    public void recordBlockingEnd(String reason, Thread thread) {
        if (!thread.isVirtual()) return;
        long id = thread.threadId();
        if (activeBlocksByThread.get(id) == null) {
            return;   // nothing open on this thread, or a round boundary already ended it
        }
        Block left = activeBlocksByThread.computeIfPresent(id, (k, open) ->
                open.depth() > 1 ? new Block(open.reason(), open.depth() - 1) : null);
        if (left == null) {
            concurrentlyBlocked.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /**
     * Ends the round's blocks. A body that threw inside its blocking section never records the
     * end, and its virtual thread is gone by the next round, so counting it there would add a
     * carrier nobody holds to every later count (#964). The runner calls this once the previous
     * round's workers have finished.
     *
     * @since 1.13.1
     */
    public void markInvocationStart() {
        activeBlocksByThread.clear();
        concurrentlyBlocked.set(0);
    }

    /**
     * Analyze recorded blocking events for carrier exhaustion patterns.
     *
     * @return a report describing any detected exhaustion risks
     */
    public CarrierExhaustionReport analyze() {
        // Any threads still active at analysis time
        List<String> details = new ArrayList<>(exhaustionDetails);
        if (!activeBlocksByThread.isEmpty()) {
            for (Map.Entry<Long, Block> entry : activeBlocksByThread.entrySet()) {
                details.add(String.format(
                    "Virtual thread (id=%d) still blocked in '%s' at analysis time",
                    entry.getKey(), entry.getValue().reason()
                ));
            }
        }

        CarrierExhaustionReport report801 = new CarrierExhaustionReport(
            details,
            peakConcurrentlyBlocked.get(),
            exhaustionEvents.get(),
            carrierCount
        );
        report801.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report801);
    }

    private static int availableCarriers() {
        // The default ForkJoinPool for virtual thread scheduling uses
        // Runtime.getRuntime().availableProcessors() as its parallelism.
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Report of carrier thread exhaustion analysis.
     */
    public static class CarrierExhaustionReport {
        private final List<String> exhaustionDetails;
        private final int peakConcurrentlyBlocked;
        private final int exhaustionEventCount;
        private final int carrierCount;

        CarrierExhaustionReport(List<String> exhaustionDetails, int peakConcurrentlyBlocked,
                                int exhaustionEventCount, int carrierCount) {
            this.exhaustionDetails = exhaustionDetails;
            this.peakConcurrentlyBlocked = peakConcurrentlyBlocked;
            this.exhaustionEventCount = exhaustionEventCount;
            this.carrierCount = carrierCount;
        }

        /**
         * {@return true if carrier exhaustion was reached or approached}
         */
        public boolean hasIssues() {
            return exhaustionEventCount > 0;
        }

        /**
         * {@return the exhaustion details}
         */
        public List<String> getExhaustionDetails()   { return Collections.unmodifiableList(exhaustionDetails); }
        /**
         * {@return the peak concurrently blocked}
         */
        public int          getPeakConcurrentlyBlocked() { return peakConcurrentlyBlocked; }
        /**
         * {@return the exhaustion event count}
         */
        public int          getExhaustionEventCount() { return exhaustionEventCount; }
        /**
         * {@return the carrier count}
         */
        public int          getCarrierCount()         { return carrierCount; }

        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding, worded as its text line (#801); called once before the report is returned. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
            for (String detail : exhaustionDetails) {
                structuredViolations.add(new Violation("VirtualThreadCarrierExhaustion", severity,
                        detail, List.of(), Map.of(), Instant.now()));
            }
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return String.format(
                    "VirtualThreadCarrierExhaustionReport: No carrier exhaustion detected "
                    + "(peak concurrent blocked=%d, carriers=%d)",
                    peakConcurrentlyBlocked, carrierCount);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.HIGH.format())
              .append(": Virtual thread carrier exhaustion detected\n");
            sb.append("  Carrier threads=").append(carrierCount)
              .append(", peak concurrent blocked=").append(peakConcurrentlyBlocked)
              .append(", exhaustion events=").append(exhaustionEventCount).append("\n");

            for (String detail : exhaustionDetails) {
                sb.append("    - ").append(detail).append("\n");
            }

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: Virtual Thread Carrier Exhaustion

                Virtual threads are scheduled onto a small pool of platform threads called
                "carrier threads" (defaults to CPU core count). When a virtual thread parks
                (e.g. on I/O), it unmounts from its carrier so other virtual threads can run.

                However, some operations CANNOT unmount the virtual thread:
                  - synchronized blocks / methods (Java 21 — fixed in Java 24)
                  - native method calls that block
                  - some JVM internals

                If ALL carrier threads are simultaneously held by pinned/blocking virtual
                threads, no other virtual thread can be scheduled — the system stalls.

                Mitigation strategies:
                  1. Replace synchronized with ReentrantLock (always unmounts):
                       private final ReentrantLock lock = new ReentrantLock();
                       lock.lock();
                       try { criticalSection(); }
                       finally { lock.unlock(); }

                  2. Increase the carrier pool size (use sparingly):
                       System property: jdk.virtualThreadScheduler.parallelism=N

                  3. Limit the number of concurrent virtual threads that can block:
                       Semaphore gate = new Semaphore(carrierCount - 1);
                       gate.acquire();
                       try { blockingOperation(); }
                       finally { gate.release(); }

                  4. Java 24+ lifts the pinning restriction for synchronized — upgrading
                     the JDK is the cleanest long-term fix.
                """;
        }
    }
}
