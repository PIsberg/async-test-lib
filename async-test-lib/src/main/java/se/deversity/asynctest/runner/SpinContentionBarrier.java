package se.deversity.asynctest.runner;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

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
 * both the arrival state and {@code currentPhase} to prevent false sharing between
 * the two hot fields and adjacent heap objects.
 *
 * <p><strong>Thread safety:</strong> all N threads call {@link #await()} concurrently;
 * the barrier is re-usable across invocation rounds.
 *
 * <p><strong>Interruption:</strong> the spin loop checks {@link Thread#interrupted()} every
 * 64 iterations and throws {@link InterruptedException} if the flag is set, so cancelled
 * rounds tear down cleanly. The interrupted thread first takes back its arrival, so its peers
 * keep waiting for a full set instead of being released short, and its own next await counts
 * once (#1504). An interrupt that lands as the last thread releases the phase is not lost: the
 * thread returns as released, with its interrupt status set again. Note that neither {@link Thread#onSpinWait()} nor the
 * interrupted-flag check is a <em>virtual-thread scheduling point</em>: a spinning virtual
 * thread never unmounts from its carrier, which is why {@code ConcurrencyRunner} always
 * selects the {@code CyclicBarrier} path for {@code useVirtualThreads = true} runs
 * regardless of the spin-barrier system property.
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

    // Padding prevents false sharing between the arrival state and currentPhase.
    // Each group occupies its own 64-byte cache line.
    /**
     * The phase in the high 32 bits and the arrivals in it in the low 32, in one word so that an
     * interrupted thread can take back its arrival only while its phase is still open (#1504).
     */
    private final AtomicLong arrivals = new AtomicLong(0);
    @SuppressWarnings("unused") private long pad1;
    @SuppressWarnings("unused") private long pad2;
    @SuppressWarnings("unused") private long pad3;
    @SuppressWarnings("unused") private long pad4;
    @SuppressWarnings("unused") private long pad5;
    @SuppressWarnings("unused") private long pad6;

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private volatile int currentPhase = 0;
    @SuppressWarnings("unused") private long pad7;
    @SuppressWarnings("unused") private long pad8;
    @SuppressWarnings("unused") private long pad9;
    @SuppressWarnings("unused") private long pad10;
    @SuppressWarnings("unused") private long pad11;
    @SuppressWarnings("unused") private long pad12;

    /**
     * Creates a barrier that releases once {@code totalThreads} threads have arrived.
     *
     * <p>Unlike {@link java.util.concurrent.CyclicBarrier}, arriving threads spin rather than
     * park, so they resume within nanoseconds of the last arrival instead of waiting to be
     * scheduled. That is what makes the collision tight enough for a race to reproduce.
     *
     * @param totalThreads how many threads must arrive before any is released; must be at least 1
     * @throws IllegalArgumentException if {@code totalThreads} is less than 1
     */
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
        // One atomic step takes an arrival and reads the phase it belongs to.
        long state = arrivals.getAndIncrement();
        int phase = (int) (state >>> 32);
        int targetPhase = phase + 1;

        if ((int) state + 1 == totalThreads) {
            // Last thread: open the next phase with no arrivals, then release all spinners.
            // Spinners only exit when currentPhase changes, so no thread can re-enter await()
            // and arrive in the next phase until after setRelease fires.
            arrivals.set((long) targetPhase << 32);
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
                spins++;
                if ((spins & 63) == 0 && Thread.interrupted()) {
                    if (withdraw(phase)) {
                        throw new InterruptedException(
                                "SpinContentionBarrier interrupted while waiting for phase " + targetPhase);
                    }
                    // The phase was released as the interrupt landed: this thread is through.
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** {@return whether this thread's arrival in {@code phase} was taken back, which it is while the phase is open} */
    private boolean withdraw(int phase) {
        while (true) {
            long state = arrivals.get();
            if ((int) (state >>> 32) != phase) {
                return false;
            }
            if (arrivals.compareAndSet(state, state - 1)) {
                return true;
            }
        }
    }
}
