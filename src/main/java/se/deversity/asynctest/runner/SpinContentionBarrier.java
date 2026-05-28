package se.deversity.asynctest.runner;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free busy-spin barrier for maximum thread collision precision.
 *
 * <p>{@link java.util.concurrent.CyclicBarrier} parks threads via {@code LockSupport.park()},
 * which requires an OS scheduler wakeup. Threads are released staggered across 20–100 µs,
 * dispersing the exact instant of execution and reducing the probability of triggering
 * true microsecond-level memory-ordering races.
 *
 * <p>This barrier replaces OS-level parking with a VarHandle acquire/release spin, keeping
 * all threads hot on their CPU cores. The last arriving thread performs a single
 * {@code VarHandle.setRelease} which all spinners observe within a sub-microsecond window
 * via {@code VarHandle.getAcquire}, providing the memory-ordering guarantees of a
 * volatile write/read pair without lock acquisition.
 *
 * <p><strong>Cache-line isolation:</strong> manual {@code long} padding fields surround
 * both {@code arrivalCount} and {@code currentPhase} to prevent false sharing between
 * the two hot fields and adjacent heap objects.
 *
 * <p><strong>Thread safety:</strong> all N threads call {@link #await()} concurrently;
 * the barrier is re-usable across invocation rounds.
 *
 * <p><strong>Interruption:</strong> the spin loop checks {@link Thread#interrupted()} every
 * 64 iterations and throws {@link InterruptedException} if the flag is set, ensuring
 * virtual-thread cooperative yield points and clean test teardown.
 *
 * @since 1.6.0
 */
public final class SpinContentionBarrier {

    private static final VarHandle PHASE_VH;

    static {
        try {
            PHASE_VH = MethodHandles.lookup()
                    .findVarHandle(SpinContentionBarrier.class, "currentPhase", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int totalThreads;

    // Padding prevents false sharing between arrivalCount and currentPhase.
    // Each group occupies its own 64-byte cache line.
    @SuppressWarnings("unused")
    private final AtomicInteger arrivalCount = new AtomicInteger(0);
    @SuppressWarnings("unused")
    private long pad1, pad2, pad3, pad4, pad5, pad6;

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private volatile int currentPhase = 0;
    @SuppressWarnings("unused")
    private long pad7, pad8, pad9, pad10, pad11, pad12;

    public SpinContentionBarrier(int totalThreads) {
        if (totalThreads < 1) {
            throw new IllegalArgumentException("totalThreads must be >= 1, got: " + totalThreads);
        }
        this.totalThreads = totalThreads;
    }

    /**
     * Arrives at the barrier and waits until all other threads have arrived.
     *
     * <p>The last thread to arrive resets the arrival counter and publishes the new phase
     * with a release fence. All waiting threads observe the phase change via an acquire
     * fence and return simultaneously.
     *
     * @throws InterruptedException if the calling thread is interrupted while spinning
     */
    public void await() throws InterruptedException {
        // Read phase before incrementing so the targetPhase is consistent with this
        // arrival cycle even if a very fast previous cycle already advanced currentPhase.
        int targetPhase = (int) PHASE_VH.getAcquire(this) + 1;

        if (arrivalCount.incrementAndGet() == totalThreads) {
            // Last thread: reset counter and release all spinners atomically.
            // set(0) before setRelease is safe: spinners only exit when phase changes,
            // so no thread can re-enter await() and corrupt the fresh counter until
            // after setRelease fires.
            arrivalCount.set(0);
            PHASE_VH.setRelease(this, targetPhase);
        } else {
            // Spin until the last thread publishes the new phase.
            int spins = 0;
            while (targetPhase - (int) PHASE_VH.getAcquire(this) > 0) {
                // Emit PAUSE (x86) / YIELD (ARM) to reduce pipeline stalls and
                // power consumption during the spin.
                Thread.onSpinWait();
                // Periodically check for interruption so virtual threads can yield
                // and so tests can be torn down cleanly on timeout.
                if ((++spins & 63) == 0 && Thread.interrupted()) {
                    throw new InterruptedException(
                            "SpinContentionBarrier interrupted while waiting for phase " + targetPhase);
                }
            }
        }
    }
}
