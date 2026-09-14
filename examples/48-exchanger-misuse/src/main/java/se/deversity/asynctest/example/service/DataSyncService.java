package se.deversity.asynctest.example.service;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Synchronizes data between pairs of threads using an {@link Exchanger}.
 *
 * <p><strong>Bug:</strong> An {@code Exchanger} requires exactly two threads to
 * call {@link Exchanger#exchange} at the same time. {@link #exchangeData} waits
 * without a bound, so when the number of concurrent callers is odd, the caller
 * left over waits forever for a partner that never arrives.
 *
 * <p><strong>Fix:</strong> {@link #exchangeDataWithin} bounds the wait and hands
 * the caller a sentinel instead of blocking, or ensure callers always arrive in
 * pairs.
 */
public class DataSyncService {

    private final Exchanger<String> exchanger = new Exchanger<>();

    /**
     * Exchanges {@code data} with another thread, waiting as long as it takes.
     *
     * @param data the payload to send
     * @return the payload received from the partner thread
     * @throws InterruptedException if the caller is interrupted while waiting for a partner
     */
    public String exchangeData(String data) throws InterruptedException {
        return exchanger.exchange(data); // BUG: no partner means no return
    }

    /**
     * Attempts to exchange {@code data} with another thread within {@code timeoutMs}.
     *
     * @param data the payload to send
     * @param timeoutMs how long to wait for a partner
     * @return the payload received from the partner thread, or {@code "[timeout]"} on timeout
     */
    public String exchangeDataWithin(String data, long timeoutMs) {
        try {
            return exchanger.exchange(data, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "[timeout]"; // no partner arrived in time; the caller gives up and moves on
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
