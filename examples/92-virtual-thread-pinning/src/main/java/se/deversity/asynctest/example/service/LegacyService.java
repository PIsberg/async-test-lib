package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A legacy service that uses {@code synchronized} for thread safety.
 *
 * <p>BUG: {@link #fetchData(String)} is a {@code synchronized} method and
 * calls {@link Thread#sleep(long)} inside it to simulate a blocking network
 * call. When a virtual thread invokes this method the JVM pins it to the
 * carrier thread for the duration of the sleep. The carrier cannot serve other
 * virtual threads until the sleep completes, which defeats the purpose of using
 * virtual threads.
 */
public class LegacyService {

    private final AtomicInteger fetchCount = new AtomicInteger(0);

    /**
     * Fetch data from a remote source.
     *
     * <p>BUG: {@code synchronized} combined with a blocking call pins the
     * carrier thread when called from a virtual thread.
     *
     * @param url the URL to fetch
     * @return simulated response body
     */
    public synchronized String fetchData(String url) {
        try {
            // Simulates blocking network I/O inside the synchronized block.
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        fetchCount.incrementAndGet();
        return "response-from:" + url;
    }

    public int getFetchCount() {
        return fetchCount.get();
    }
}
