package com.github.asynctest.diagnostics;

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
 * @AsyncTest(threads = 20, useVirtualThreads = true, detectVirtualThreadCarrierExhaustion = true)
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
 * @since 0.8.0
 */
public class VirtualThreadCarrierExhaustionDetector {

    private final int carrierCount;

    private final AtomicInteger concurrentlyBlocked = new AtomicInteger(0);
    private final AtomicInteger peakConcurrentlyBlocked = new AtomicInteger(0);
    private final AtomicInteger exhaustionEvents = new AtomicInteger(0);
    private final List<String> exhaustionDetails = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, String> activeBlocksByThread = new ConcurrentHashMap<>();

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
     * Record that the specified thread is entering a blocking operation.
     *
     * @param reason  description of the blocking operation
     * @param thread  the thread entering the blocking state
     */
    public void recordBlockingStart(String reason, Thread thread) {
        if (!isVirtual(thread)) return;

        activeBlocksByThread.put(thread.threadId(), reason != null ? reason : "unknown");
        int current = concurrentlyBlocked.incrementAndGet();
        peakConcurrentlyBlocked.updateAndGet(max -> Math.max(max, current));

        if (current >= carrierCount) {
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
        if (!isVirtual(thread)) return;
        activeBlocksByThread.remove(thread.threadId());
        concurrentlyBlocked.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * Analyze recorded blocking events for carrier exhaustion patterns.
     *
     * @return a report describing any detected exhaustion risks
     */
    public CarrierExhaustionReport analyze() {
        // Any threads still active at analysis time
        if (!activeBlocksByThread.isEmpty()) {
            for (Map.Entry<Long, String> entry : activeBlocksByThread.entrySet()) {
                exhaustionDetails.add(String.format(
                    "Virtual thread (id=%d) still blocked in '%s' at analysis time",
                    entry.getKey(), entry.getValue()
                ));
            }
        }

        return new CarrierExhaustionReport(
            new ArrayList<>(exhaustionDetails),
            peakConcurrentlyBlocked.get(),
            exhaustionEvents.get(),
            carrierCount
        );
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return thread.isVirtual();
        } catch (NoSuchMethodError e) {
            return false;
        }
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

        /** @return true if carrier exhaustion was reached or approached */
        public boolean hasIssues() {
            return exhaustionEventCount > 0;
        }

        public List<String> getExhaustionDetails()   { return exhaustionDetails; }
        public int          getPeakConcurrentlyBlocked() { return peakConcurrentlyBlocked; }
        public int          getExhaustionEventCount() { return exhaustionEventCount; }
        public int          getCarrierCount()         { return carrierCount; }

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
