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

    private QuietWindowPublishers() {
    }

    /** {@return a counter registered as the drain callback from now on} */
    public static QuietWindowPublishers listen() {
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
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            names.put(thread.threadId(), thread.getName());
        }
        return byThread.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(e -> names.getOrDefault(e.getKey(), "ended thread") + " (id " + e.getKey() + ")="
                        + e.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
