package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SpinContentionBarrierTest {

    @Test
    void testBasicBarrierRelease() throws Exception {
        int threads = 4;
        SpinContentionBarrier barrier = new SpinContentionBarrier(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger activeThreads = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    activeThreads.incrementAndGet();
                    barrier.await();
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        // Wait up to 1 second for all threads to be released.
        boolean released = latch.await(1, TimeUnit.SECONDS);
        assertTrue(released, "Barrier failed to release all threads");
        assertEquals(4, activeThreads.get());

        executor.shutdownNow();
    }

    @Test
    void testBarrierInterruption() throws Exception {
        SpinContentionBarrier barrier = new SpinContentionBarrier(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch interruptedLatch = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Future<?> future = executor.submit(() -> {
            try {
                arrived.countDown();
                barrier.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                interruptedLatch.countDown();
            }
        });

        assertTrue(arrived.await(500, TimeUnit.MILLISECONDS));
        // Allow spinner to spin a bit.
        Thread.sleep(50);
        future.cancel(true); // Interrupt the thread

        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (CancellationException | ExecutionException e) {
            // expected
        }

        assertTrue(interruptedLatch.await(1, TimeUnit.SECONDS), "Worker thread did not catch InterruptedException in time");
        assertTrue(interrupted.get(), "Thread was not interrupted or did not throw InterruptedException");
        executor.shutdownNow();
    }

    @Test
    void testIntegerOverflowSafety() throws Exception {
        int threads = 3;
        SpinContentionBarrier barrier = new SpinContentionBarrier(threads);

        // Force the currentPhase field to Integer.MAX_VALUE via reflection.
        Field phaseField = SpinContentionBarrier.class.getDeclaredField("currentPhase");
        phaseField.setAccessible(true);
        phaseField.setInt(barrier, Integer.MAX_VALUE);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        // First round: phase goes from MAX_VALUE to MIN_VALUE (overflow).
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    latch.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Barrier broke or got stuck during integer overflow");
        assertEquals(Integer.MIN_VALUE, phaseField.getInt(barrier), "Phase did not overflow to Integer.MIN_VALUE");

        // Second round (after overflow): verify it continues to work seamlessly at MIN_VALUE.
        CountDownLatch latch2 = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();
                    latch2.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(latch2.await(1, TimeUnit.SECONDS), "Barrier got stuck in the generation following integer overflow");
        assertEquals(Integer.MIN_VALUE + 1, phaseField.getInt(barrier));

        executor.shutdownNow();
    }
}
