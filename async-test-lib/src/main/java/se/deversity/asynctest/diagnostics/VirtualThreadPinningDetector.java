package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects virtual thread pinning issues.
 *
 * <p>Virtual threads are "pinned" to their carrier platform threads when they block
 * inside certain constructs. <strong>What pins depends on the JDK version:</strong>
 * <ul>
 *   <li><b>{@code synchronized} blocks/methods and {@code Object.wait()}</b> — pinned
 *       up to JDK 23; <em>no longer pin since JDK 24</em> (JEP 491: Synchronize Virtual
 *       Threads without Pinning).</li>
 *   <li><b>Waiting for class initialization</b> ({@code <clinit>} on another thread) —
 *       pinned up to JDK 25; <em>no longer pins since JDK 26</em> (virtual threads now
 *       unmount while waiting for class init).</li>
 *   <li><b>Blocking native calls</b> (JNI / FFM downcalls) — still pin on all JDK
 *       versions.</li>
 * </ul>
 *
 * <p>This detector classifies each recorded event by cause and JDK version: events whose
 * cause no longer pins on the running JDK are kept in the report but annotated as
 * obsolete, so tests written against JDK 21–23 behavior don't report phantom pinning on
 * JDK 24+.
 *
 * <p>Pinned virtual threads lose their scalability advantage because they hold onto
 * carrier threads that could otherwise be used by other virtual threads.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 10, useVirtualThreads = true, detectVirtualThreadPinning = true)
 * void testVirtualThreadPinning() {
 *     AsyncTestContext.virtualThreadPinningDetector()
 *         .startMonitoring();
 *
 *     // Code that may cause pinning
 *     synchronized(lock) {
 *         Thread.sleep(100);
 *     }
 *
 *     PinningReport report = AsyncTestContext.virtualThreadPinningDetector()
 *         .analyzePinning();
 *     if (report.hasPinningIssues()) {
 *         // Handle pinning detected
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> This detector requires Java 21+ with virtual thread support.
 * On earlier Java versions, it will report no issues.
 *
 * @since 1.2.0
 */
public class VirtualThreadPinningDetector {

    /**
     * Classification of what caused a virtual thread to pin. Determines whether the
     * event still pins on the running JDK version.
     *
     * @since 1.7.0
     */
    public enum PinningCause {
        /** {@code synchronized} / monitor / {@code Object.wait()} — no longer pins since JDK 24 (JEP 491). */
        MONITOR,
        /** Waiting for another thread's class initialization — no longer pins since JDK 26. */
        CLASS_INIT,
        /** Blocking native call (JNI / FFM downcall) — pins on all JDK versions. */
        NATIVE,
        /** Unrecognized description — conservatively treated as still pinning. */
        OTHER
    }

    /**
     * Classifies a caller-supplied blocking-operation description.
     *
     * @since 1.7.0
     */
    public static PinningCause classifyOperation(String blockingOperation) {
        if (blockingOperation == null) return PinningCause.OTHER;
        String op = blockingOperation.toLowerCase(java.util.Locale.ROOT);
        if (op.contains("synchronized") || op.contains("monitor") || op.contains("object.wait")
                || op.contains("wait()")) {
            return PinningCause.MONITOR;
        }
        if (op.contains("clinit") || op.contains("class init") || op.contains("class-init")
                || op.contains("static initializer")) {
            return PinningCause.CLASS_INIT;
        }
        if (op.contains("native") || op.contains("jni") || op.contains("ffm")
                || op.contains("foreign")) {
            return PinningCause.NATIVE;
        }
        return PinningCause.OTHER;
    }

    /**
     * Whether the given cause still pins a virtual thread on the given JDK feature
     * version (e.g. {@code 21}, {@code 24}, {@code 26}).
     *
     * @since 1.7.0
     */
    public static boolean stillPinsOn(PinningCause cause, int jdkFeatureVersion) {
        return switch (cause) {
            case MONITOR    -> jdkFeatureVersion < 24;  // JEP 491 (JDK 24)
            case CLASS_INIT -> jdkFeatureVersion < 26;  // JDK 26 unmounts on class-init waits
            case NATIVE, OTHER -> true;
        };
    }

    private static class PinningEvent {
        final long virtualThreadId;
        final String virtualThreadName;
        final long startTimeNanos;
        final String blockingOperation;
        final StackTraceElement[] stackTrace;
        final PinningCause cause;
        final boolean obsoleteOnCurrentJdk;

        PinningEvent(long virtualThreadId, String virtualThreadName,
                     String blockingOperation, StackTraceElement[] stackTrace) {
            this.virtualThreadId = virtualThreadId;
            this.virtualThreadName = virtualThreadName;
            this.startTimeNanos = System.nanoTime();
            this.blockingOperation = blockingOperation;
            this.stackTrace = stackTrace;
            this.cause = classifyOperation(blockingOperation);
            this.obsoleteOnCurrentJdk = !stillPinsOn(this.cause, Runtime.version().feature());
        }

        long getDurationMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        }
    }

    private final List<PinningEvent> pinningEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger currentPinnedCount = new AtomicInteger(0);
    private final AtomicInteger maxPinnedCount = new AtomicInteger(0);
    private volatile boolean monitoring = false;

    /**
     * Start monitoring for virtual thread pinning.
     */
    public void startMonitoring() {
        monitoring = true;
    }

    /**
     * Stop monitoring.
     */
    public void stopMonitoring() {
        monitoring = false;
    }

    /**
     * Record a pinning event.
     *
     * @param thread the pinned virtual thread
     * @param blockingOperation description of the blocking operation (e.g., "synchronized block")
     */
    public void recordPinningEvent(Thread thread, String blockingOperation) {
        if (!monitoring || !isVirtualThread(thread)) {
            return;
        }

        StackTraceElement[] stackTrace = thread.getStackTrace();
        PinningEvent event = new PinningEvent(
            thread.threadId(),
            thread.getName(),
            blockingOperation,
            stackTrace
        );

        pinningEvents.add(event);
        currentPinnedCount.incrementAndGet();
        maxPinnedCount.updateAndGet(max -> Math.max(max, currentPinnedCount.get()));
    }

    /**
     * Record that a previously pinned thread is now unpinned.
     *
     * @param thread the unpinned virtual thread
     */
    public void recordUnpinEvent(Thread thread) {
        if (!monitoring || !isVirtualThread(thread)) {
            return;
        }
        currentPinnedCount.decrementAndGet();
    }

    /**
     * Analyze virtual thread pinning.
     *
     * @return report containing pinning statistics and events
     */
    public PinningReport analyzePinning() {
        List<PinningEventSnapshot> events = new ArrayList<>();
        synchronized (pinningEvents) {
            for (PinningEvent event : pinningEvents) {
                events.add(new PinningEventSnapshot(
                    event.virtualThreadId,
                    event.virtualThreadName,
                    event.blockingOperation,
                    event.getDurationMillis(),
                    event.stackTrace,
                    event.cause,
                    event.obsoleteOnCurrentJdk
                ));
            }
        }

        return new PinningReport(
            events,
            maxPinnedCount.get(),
            isVirtualThreadSupported()
        );
    }

    /**
     * Standardized alias for {@link #analyzePinning()}.
     *
     * @return report containing pinning statistics and events
     */
    public PinningReport analyze() {
        return analyzePinning();
    }

    /**
     * Check if pinning was detected.
     *
     * @return true if any pinning events were recorded
     */
    public boolean hasPinningIssues() {
        return !pinningEvents.isEmpty();
    }

    /**
     * Get the number of pinning events.
     *
     * @return count of recorded pinning events
     */
    public int getPinningEventCount() {
        return pinningEvents.size();
    }

    /**
     * Clear all recorded events.
     */
    public void clear() {
        pinningEvents.clear();
        currentPinnedCount.set(0);
        maxPinnedCount.set(0);
    }

    /**
     * Check if virtual threads are supported (Java 21+).
     *
     * @return true if virtual threads are available
     */
    public static boolean isVirtualThreadSupported() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Check if a thread is a virtual thread.
     *
     * @param thread the thread to check
     * @return true if it's a virtual thread
     */
    public static boolean isVirtualThread(Thread thread) {
        if (!isVirtualThreadSupported()) {
            return false;
        }
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * Report of virtual thread pinning analysis.
     */
    public static class PinningReport {
        private final List<PinningEventSnapshot> events;
        private final int maxPinnedCount;
        private final boolean virtualThreadSupported;

        PinningReport(List<PinningEventSnapshot> events, int maxPinnedCount, boolean virtualThreadSupported) {
            this.events = events;
            this.maxPinnedCount = maxPinnedCount;
            this.virtualThreadSupported = virtualThreadSupported;
        }

        /**
         * Returns all captured pinning event snapshots.
         *
         * @return list of pinning event snapshots
         */
        public List<PinningEventSnapshot> getEvents() {
            return Collections.unmodifiableList(events);
        }

        /**
         * Returns the peak number of virtual threads that were pinned at the same time.
         *
         * @return maximum number of threads pinned simultaneously
         */
        public int getMaxPinnedCount() {
            return maxPinnedCount;
        }

        /**
         * Indicates whether virtual threads are available on the current JVM.
         *
         * @return true if virtual threads are supported on this JVM
         */
        public boolean isVirtualThreadSupported() {
            return virtualThreadSupported;
        }

        /**
         * Indicates whether any virtual thread pinning was observed.
         *
         * @return true if pinning issues were detected
         */
        public boolean hasPinningIssues() {
            return virtualThreadSupported && !events.isEmpty();
        }

        /**
         * Indicates whether pinning that still applies on the running JDK was observed.
         * Events whose cause no longer pins ({@code synchronized} on JDK 24+ per JEP 491,
         * class-init waits on JDK 26+) are excluded.
         *
         * @return true if any recorded event still pins on the current JDK
         * @since 1.7.0
         */
        public boolean hasEffectivePinningIssues() {
            return virtualThreadSupported
                && events.stream().anyMatch(e -> !e.isObsoleteOnCurrentJdk());
        }

        /**
         * Returns how many recorded events no longer pin on the running JDK.
         *
         * @since 1.7.0
         */
        public long getObsoleteEventCount() {
            return events.stream().filter(PinningEventSnapshot::isObsoleteOnCurrentJdk).count();
        }

        @Override
        public String toString() {
            if (!virtualThreadSupported) {
                return "VirtualThreadPinningReport: Virtual threads not supported (requires Java 21+)";
            }

            if (events.isEmpty()) {
                return "VirtualThreadPinningReport: No pinning detected - virtual threads are running efficiently";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.MEDIUM.format())
              .append(": ")
              .append(events.size())
              .append(" virtual thread pinning event(s) detected — a pinned virtual thread cannot unmount from its carrier platform thread, so that carrier is unavailable to other virtual threads for the entire duration of the block (max concurrent pinned: ")
              .append(maxPinnedCount)
              .append(")\n");

            long obsolete = getObsoleteEventCount();
            if (obsolete > 0) {
                sb.append("  Note: ").append(obsolete)
                  .append(" event(s) no longer pin on JDK ")
                  .append(Runtime.version().feature())
                  .append(" (synchronized/Object.wait stopped pinning in JDK 24 per JEP 491; ")
                  .append("class-init waits stopped pinning in JDK 26) — annotated below.\n");
            }

            for (int i = 0; i < Math.min(5, events.size()); i++) {
                PinningEventSnapshot event = events.get(i);
                sb.append("\n  [").append(i + 1).append("] ")
                  .append(event.threadName)
                  .append(" (id=").append(event.threadId).append(")")
                  .append("\n      Blocking operation: ").append(event.blockingOperation)
                  .append(event.isObsoleteOnCurrentJdk()
                          ? " [no longer pins on this JDK]" : "")
                  .append("\n      Duration: ").append(event.durationMillis).append("ms");

                if (event.stackTrace != null && event.stackTrace.length > 0) {
                    sb.append("\n      Stack trace:");
                    for (int j = 0; j < Math.min(3, event.stackTrace.length); j++) {
                        sb.append("\n        at ").append(event.stackTrace[j]);
                    }
                }
            }

            if (events.size() > 5) {
                sb.append("\n  ... and ").append(events.size() - 5).append(" more events");
            }

            // Add learning content and auto-fix
            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(LearningContent.getVirtualThreadPinningExplanation());
            sb.append(AutoFix.getVirtualThreadPinningFix());
            sb.append("=".repeat(60));

            return sb.toString();
        }
    }

    /**
     * Snapshot of a pinning event.
     */
    public static class PinningEventSnapshot {
        private final long threadId;
        private final String threadName;
        private final String blockingOperation;
        private final long durationMillis;
        private final StackTraceElement[] stackTrace;
        private final PinningCause cause;
        private final boolean obsoleteOnCurrentJdk;

        PinningEventSnapshot(long threadId, String threadName, String blockingOperation,
                            long durationMillis, StackTraceElement[] stackTrace,
                            PinningCause cause, boolean obsoleteOnCurrentJdk) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.blockingOperation = blockingOperation;
            this.durationMillis = durationMillis;
            this.stackTrace = stackTrace;
            this.cause = cause;
            this.obsoleteOnCurrentJdk = obsoleteOnCurrentJdk;
        }

        /**
         * Returns the classified cause of this pinning event.
         *
         * @since 1.7.0
         */
        public PinningCause getCause() {
            return cause;
        }

        /**
         * Whether this event's cause no longer pins on the JDK the test ran on
         * ({@code synchronized}/{@code Object.wait} on JDK 24+ per JEP 491, class-init
         * waits on JDK 26+).
         *
         * @since 1.7.0
         */
        public boolean isObsoleteOnCurrentJdk() {
            return obsoleteOnCurrentJdk;
        }

        /**
         * Returns the ID of the pinned virtual thread.
         *
         * @return virtual thread ID
         */
        public long getThreadId() {
            return threadId;
        }

        /**
         * Returns the name of the pinned virtual thread.
         *
         * @return virtual thread name
         */
        public @Nullable String getThreadName() {
            return threadName;
        }

        /**
         * Returns a description of the operation that caused the virtual thread to pin.
         *
         * @return description of blocking operation
         */
        public @Nullable String getBlockingOperation() {
            return blockingOperation;
        }

        /**
         * Returns how long the virtual thread was pinned to its carrier.
         *
         * @return duration in milliseconds
         */
        public long getDurationMillis() {
            return durationMillis;
        }

        /**
         * Returns the stack trace captured at the point the virtual thread became pinned.
         *
         * @return stack trace at pinning point
         */
        public StackTraceElement @Nullable [] getStackTrace() {
            return stackTrace == null ? null : stackTrace.clone();
        }
    }
}
