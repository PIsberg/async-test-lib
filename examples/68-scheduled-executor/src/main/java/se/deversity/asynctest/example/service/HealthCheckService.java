package se.deversity.asynctest.example.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BUGGY service that demonstrates ScheduledExecutorService leak.
 *
 * BUG: startChecks() creates a ScheduledExecutorService on every call and
 *      schedules a fixed-rate health-check task. shutdown() is never called —
 *      not in a finally block, not via AutoCloseable, not anywhere. Each call
 *      leaks one background thread for the lifetime of the JVM.
 *
 * FIX: Implement AutoCloseable and shut down the scheduler in close(), or
 *      expose a stop() method and call it in @AfterEach / a shutdown hook.
 */
public class HealthCheckService {

    // BUG: new executor created per startChecks() call, never shut down
    private ScheduledExecutorService scheduler;
    private final AtomicInteger checkCount = new AtomicInteger(0);

    /**
     * Start periodic health checks. Creates an executor that is never stopped.
     */
    public void startChecks() {
        scheduler = Executors.newSingleThreadScheduledExecutor(); // BUG: leaked
        scheduler.scheduleAtFixedRate(
                () -> checkCount.incrementAndGet(),
                0, 1, TimeUnit.SECONDS
        );
        // BUG: no shutdown() call anywhere — thread leaks permanently
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public int getCheckCount() {
        return checkCount.get();
    }
}
