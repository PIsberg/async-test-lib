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

        // Hang-guard: generous so pool spin-up on a stalled CI runner cannot trip it.
        boolean released = latch.await(5, TimeUnit.SECONDS);
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

        assertTrue(interruptedLatch.await(5, TimeUnit.SECONDS), "Worker thread did not catch InterruptedException in time");
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
        // The arrivals word carries the phase an arrival belongs to in its high half (#1504).
        Field arrivalsField = SpinContentionBarrier.class.getDeclaredField("arrivals");
        arrivalsField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) arrivalsField.get(barrier)).set((long) Integer.MAX_VALUE << 32);

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

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Barrier broke or got stuck during integer overflow");
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

        assertTrue(latch2.await(5, TimeUnit.SECONDS), "Barrier got stuck in the generation following integer overflow");
        assertEquals(Integer.MIN_VALUE + 1, phaseField.getInt(barrier));

        executor.shutdownNow();
    }

    /**
     * An interrupted arrival used to stay counted (#1504): the thread's next await was then taken
     * for the last of the two, returned at once without its peer, and left the peer spinning.
     */
    @Test
    void anInterruptedArrivalIsWithdrawn() throws Exception {
        SpinContentionBarrier barrier = new SpinContentionBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> interrupted = pool.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    barrier.await();
                    return false;
                } catch (InterruptedException expected) {
                    return true;
                }
            });
            assertTrue(interrupted.get(5, TimeUnit.SECONDS), "the interrupted arrival throws");

            CountDownLatch released = new CountDownLatch(1);
            Future<?> again = pool.submit(() -> {
                barrier.await();
                released.countDown();
                return null;
            });
            assertFalse(released.await(200, TimeUnit.MILLISECONDS),
                    "alone at a barrier of two, the second await must wait for its peer");

            Future<?> peer = pool.submit(() -> {
                barrier.await();
                return null;
            });
            peer.get(5, TimeUnit.SECONDS);
            again.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * An interrupt that lands while the last arrival is releasing the phase does not take back an
     * arrival that phase already counted: the thread goes through as released, its interrupt
     * status set again for whoever looks next (#1504).
     */
    @Test
    void anInterruptAsThePhaseReleasesLetsTheThreadThrough() throws Exception {
        SpinContentionBarrier barrier = new SpinContentionBarrier(2);
        Field arrivalsField = SpinContentionBarrier.class.getDeclaredField("arrivals");
        arrivalsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong arrivals =
                (java.util.concurrent.atomic.AtomicLong) arrivalsField.get(barrier);

        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        Thread spinner = new Thread(() -> {
            try {
                barrier.await();
                outcome.complete(Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
                outcome.completeExceptionally(e);
            }
        });
        spinner.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (arrivals.get() != 1L && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1L, arrivals.get(), "the spinner arrived in phase 0");
        // The last arrival's first step: phase 1 open, not yet published to the spinners.
        arrivals.set(1L << 32);
        spinner.interrupt();

        assertTrue(outcome.get(5, TimeUnit.SECONDS),
                "the thread returns as released, with its interrupt status set again");
        spinner.join(5_000);
    }
}
