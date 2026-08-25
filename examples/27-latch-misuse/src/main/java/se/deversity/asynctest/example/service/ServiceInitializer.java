package se.deversity.asynctest.example.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Coordinates the startup of multiple downstream microservices before
 * declaring the application ready.
 *
 * <p>BUG: The catch block inside each service-start task calls
 * {@code latch.countDown()} in addition to the finally block.
 * On a failure path, countDown() is called twice per task, once in the
 * catch block and once in the finally block, so the latch reaches zero
 * before all services have successfully started and the caller proceeds
 * prematurely.
 *
 * <p>The total countDown() calls become: successCount + 2 * failureCount,
 * which is greater than {@code serviceCount} whenever any service fails.
 *
 * <p>FIX: Remove countDown() from the catch block and keep it only in finally,
 * so that exactly one countDown() occurs per task regardless of outcome.
 * {@link #initializeFixed(int)} is that same method with the extra call taken out.
 *
 * <p>INSTRUMENTATION: LatchMisuseDetector is recording-fed. It compares the number of
 * countDown() calls against the count the latch was built with, so it has to be told about
 * the construction and about every call. The three hooks below do that and default to no-ops,
 * so the production path never touches the test library. This is the seam, not the bug.
 */
public class ServiceInitializer {

    private final ExecutorService startupPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "startup-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile ObjIntConsumer<CountDownLatch> onLatchCreated = (latch, count) -> { };

    private volatile Consumer<CountDownLatch> onCountDown = latch -> { };

    private volatile Consumer<CountDownLatch> onAwait = latch -> { };

    /**
     * Start {@code serviceCount} downstream services concurrently and wait
     * until all have completed startup (or failed).
     *
     * @param serviceCount number of services to start
     * @return true if all services started successfully, false if any failed
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean initialize(int serviceCount) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(serviceCount);
        onLatchCreated.accept(latch, serviceCount);

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
                    onCountDown.accept(latch);
                } finally {
                    latch.countDown();
                    onCountDown.accept(latch);
                }
            });
        }

        onAwait.accept(latch);
        return latch.await(10, TimeUnit.SECONDS);
    }

    /**
     * The same startup, with the extra countDown() removed. Exactly one call per task,
     * whatever the outcome, so the latch reaches zero when the work is genuinely done.
     *
     * @param serviceCount number of services to start
     * @return true if the latch reached zero within the timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public boolean initializeFixed(int serviceCount) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(serviceCount);
        onLatchCreated.accept(latch, serviceCount);

        for (int i = 0; i < serviceCount; i++) {
            final int serviceId = i;
            startupPool.submit(() -> {
                try {
                    startService(serviceId);
                } catch (Exception e) {
                    // Failure is handled, not counted twice.
                } finally {
                    latch.countDown();
                    onCountDown.accept(latch);
                }
            });
        }

        onAwait.accept(latch);
        return latch.await(10, TimeUnit.SECONDS);
    }

    /**
     * Installs the hooks LatchMisuseDetector needs. No-ops by default.
     *
     * @param onCreated   called with each latch and the count it was built with
     * @param onDown      called after each countDown()
     * @param onWait      called before the coordinator awaits the latch
     */
    public void observeLatch(ObjIntConsumer<CountDownLatch> onCreated,
                             Consumer<CountDownLatch> onDown,
                             Consumer<CountDownLatch> onWait) {
        this.onLatchCreated = onCreated;
        this.onCountDown = onDown;
        this.onAwait = onWait;
    }

    private void startService(int serviceId) {
        // Simulate a startup failure on service 1
        if (serviceId == 1) {
            throw new IllegalStateException("Connection refused: service-" + serviceId);
        }
    }

    /** Stops the startup pool and drops anything still queued. */
    public void shutdown() {
        startupPool.shutdownNow();
    }
}
