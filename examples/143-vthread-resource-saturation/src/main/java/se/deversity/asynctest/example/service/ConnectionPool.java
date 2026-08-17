package se.deversity.asynctest.example.service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A connection pool with a hard limit, standing in for anything bounded that the application
 * cannot make bigger: a database pool, a rate-limited API, a licence-capped downstream service.
 *
 * <p>What matters here is that the limit exists whether or not the caller respects it. Ask for
 * more connections than the pool has and the extra callers wait; ask from ten thousand virtual
 * threads and they all wait at once.
 */
public final class ConnectionPool {

    private final int           maximumPoolSize;
    private final Semaphore     connections;
    private final AtomicInteger peakInUse = new AtomicInteger();
    private final AtomicInteger inUse     = new AtomicInteger();

    public ConnectionPool(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
        this.connections     = new Semaphore(maximumPoolSize);
    }

    /** {@return the pool's hard limit - the number to size any admission control against} */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Borrows a connection, waiting for one if none is free.
     *
     * @param timeoutMillis how long to wait before giving up, as a real pool would
     * @return {@code true} if a connection was obtained
     * @throws InterruptedException if the wait is interrupted
     */
    public boolean borrow(long timeoutMillis) throws InterruptedException {
        if (!connections.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return false;
        }
        int now = inUse.incrementAndGet();
        peakInUse.updateAndGet(peak -> Math.max(peak, now));
        return true;
    }

    /** Returns a connection to the pool. */
    public void release() {
        inUse.decrementAndGet();
        connections.release();
    }

    /** {@return the most connections ever in use at one time - never above the pool size} */
    public int peakInUse() {
        return peakInUse.get();
    }
}
