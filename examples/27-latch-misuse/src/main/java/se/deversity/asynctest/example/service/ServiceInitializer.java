package se.deversity.asynctest.example.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the startup of multiple downstream microservices before
 * declaring the application ready.
 *
 * BUG: The catch block inside each service-start task calls
 * {@code latch.countDown()} in addition to the finally block.
 * On a failure path, countDown() is called twice per task — once in the
 * catch block and once in the finally block — so the latch reaches zero
 * before all services have successfully started, causing the caller to
 * proceed prematurely.
 *
 * The total countDown() calls become: successCount + 2 * failureCount,
 * which is greater than {@code serviceCount} whenever any service fails.
 *
 * FIX: Remove countDown() from the catch block and keep it only in finally,
 * so that exactly one countDown() occurs per task regardless of outcome.
 */
public class ServiceInitializer {

    private final ExecutorService startupPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "startup-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Start {@code serviceCount} downstream services concurrently and wait
     * until all have completed startup (or failed).
     *
     * @param serviceCount number of services to start
     * @return true if all services started successfully, false if any failed
     */
    public boolean initialize(int serviceCount) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(serviceCount);

        for (int i = 0; i < serviceCount; i++) {
            final int serviceId = i;
            startupPool.submit(() -> {
                try {
                    startService(serviceId);
                } catch (Exception e) {
                    // BUG: countDown() is called here AND in finally.
                    // A failing service decrements the latch twice,
                    // so it can reach zero before all services are done.
                    latch.countDown();
                    System.err.println("Service " + serviceId + " failed to start: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        return latch.await(10, TimeUnit.SECONDS);
    }

    private void startService(int serviceId) {
        // Simulate occasional startup failure on service 1
        if (serviceId == 1) {
            throw new RuntimeException("Connection refused: service-" + serviceId);
        }
        // Simulate brief startup time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        startupPool.shutdownNow();
    }
}
