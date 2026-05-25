package se.deversity.asynctest.example.service;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Synchronizes data between pairs of threads using an {@link Exchanger}.
 *
 * <p><strong>Bug:</strong> An {@code Exchanger} requires exactly two threads to
 * call {@link Exchanger#exchange} at the same time. When the number of concurrent
 * callers is odd, one thread waits indefinitely for a partner that never arrives,
 * causing a timeout (or deadlock without one).
 *
 * <p><strong>Fix:</strong> Ensure the exchanger is always called by an even
 * number of threads, or replace it with a {@link java.util.concurrent.SynchronousQueue}
 * combined with explicit producer/consumer roles.
 */
public class DataSyncService {

    private final Exchanger<String> exchanger = new Exchanger<>();

    /**
     * Attempts to exchange {@code data} with another thread within 100 ms.
     *
     * @param data the payload to send
     * @return the payload received from the partner thread, or {@code "[timeout]"} on timeout
     */
    public String exchangeData(String data) {
        try {
            return exchanger.exchange(data, 100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "[timeout]"; // no partner arrived in time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[interrupted]";
        }
    }

    /** Returns the underlying exchanger for instrumentation in tests. */
    public Exchanger<String> getExchanger() {
        return exchanger;
    }
}
