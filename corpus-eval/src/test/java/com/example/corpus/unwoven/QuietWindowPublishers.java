package com.example.corpus.unwoven;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import se.deversity.asynctest.telemetry.TelemetryEventBuffer;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

/**
 * Counts the telemetry events each thread publishes while it listens, so a tripped quiet window
 * names the threads that published rather than the ones that happened to be runnable (#1480).
 *
 * <p><strong>Why this package.</strong> The callback runs on the registry's drain thread. In a lane
 * with the agent attached, a woven callback publishes an event for its own field read and map
 * update, which the drain hands back to it, which publishes again: the first version, in
 * {@code CorpusGates}, turned a quiet window into 16,369 events, 16,356 of them from the drain
 * thread. Every lane's agent line in {@code corpus-eval/pom.xml} excludes
 * {@code com.example.corpus.unwoven.}, so nothing here is woven; keep anything that runs on the
 * drain thread in this package.
 *
 * <p>Registered as the registry's drain callback for the window only. Between subjects no run
 * holds the slot (each run's bridge releases it), and {@link #stop()} releases this one with
 * {@code clearCallbackIf}, so it cannot take a registration away from anything else.
 */
public final class QuietWindowPublishers implements TelemetryEventBuffer.DrainCallback {

    /** The package prefix every lane's agent excludes; pinned by {@code CorpusGatesTest}. */
    public static final String UNWOVEN_PREFIX = "com.example.corpus.unwoven.";

    private final Map<Long, Integer> byThread = new ConcurrentHashMap<>();

    /** The JVM's reference-processing threads, by name; {@link #isJvmReferenceThread} also checks the group. */
    private static final java.util.Set<String> JVM_REFERENCE_THREADS =
            java.util.Set.of("Finalizer", "Reference Handler", "Common-Cleaner");

    /** Events {@link #stop()} found the JVM's reference threads had published, which no subject owns. */
    private long jvmOwned;

    private QuietWindowPublishers() {
    }

    /**
     * {@return a counter registered as the drain callback from now on} Drains the buffer first, so
     * events the last subjects published before the window are not credited to it: without the
     * drain, CI read events=0 beside "main=511" (#1485).
     */
    public static QuietWindowPublishers listen() {
        TelemetryRegistry.flush();
        QuietWindowPublishers publishers = new QuietWindowPublishers();
        TelemetryRegistry.setCallback(publishers);
        return publishers;
    }

    @Override
    public void onEvent(long threadId, String targetField, boolean isWrite) {
        byThread.merge(threadId, 1, Integer::sum);
    }

    /**
     * Drains what is still buffered, stops listening, and {@return each publishing thread with its
     * event count, busiest first}, as {@code {name (id N)=count, ...}}; a thread that has ended by
     * then is named "ended thread"
     */
    public String stop() {
        TelemetryRegistry.flush();
        TelemetryRegistry.clearCallbackIf(this);
        Map<Long, String> names = new HashMap<>();
        Map<Long, Thread> threads = new HashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            names.put(thread.threadId(), thread.getName());
            threads.put(thread.threadId(), thread);
        }
        jvmOwned = byThread.entrySet().stream()
                .filter(e -> isJvmReferenceThread(threads.get(e.getKey())))
                .mapToLong(Map.Entry::getValue)
                .sum();
        return byThread.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(e -> names.getOrDefault(e.getKey(), "ended thread") + " (id " + e.getKey() + ")="
                        + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /**
     * {@return the events {@link #stop()} found the JVM's reference threads had published} They
     * run finalizers and cleaners when the garbage collector hands them work, so their events
     * land in the window on GC's schedule, not because a subject left something running: CI saw
     * 232 from {@code Finalizer} alone (#1546). The quiet-window gate leaves them out.
     */
    public long jvmOwnedEvents() {
        return jvmOwned;
    }

    /**
     * {@return whether {@code thread} is one of the JVM's own reference-processing threads} The
     * name alone could be anyone's, so the group decides: {@code Finalizer} and {@code Reference
     * Handler} sit directly in the {@code system} group, and the {@code Common-Cleaner} in the
     * JDK's {@code InnocuousThreadGroup}; a thread a subject starts lands in {@code main} or below.
     * {@code null}, a thread that has ended, cannot be checked and so counts.
     */
    public static boolean isJvmReferenceThread(@org.jspecify.annotations.Nullable Thread thread) {
        if (thread == null || !thread.isDaemon() || !JVM_REFERENCE_THREADS.contains(thread.getName())) {
            return false;
        }
        ThreadGroup group = thread.getThreadGroup();
        return group != null && (group.getParent() == null || "InnocuousThreadGroup".equals(group.getName()));
    }
}
