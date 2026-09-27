package se.deversity.asynctest.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measures what a detector's record call allocates once its instance is tracked (#812).
 *
 * <p>The count is the calling thread's own, from the HotSpot per-thread allocation counter, so the
 * compiler threads and the rest of the JVM are not in it, and it is exact rather than sampled. The
 * calls run on a fresh thread whose id is past the {@link Long} cache, -128 to 127, as a run's
 * workers' ids are, so a thread id boxed on every call shows up as the 24 bytes it costs there.
 */
final class RecordPathAllocation {

    /**
     * Calls made before measuring: the first builds the state, the thread's key and its boxed id,
     * and the rest let the JVM finish warming the record path up. A probe that ran first in its JVM
     * read a one-off 1,696 to 12,784 bytes after 1,000 or 5,000 warm-up calls, the same number on
     * every run, and 0 after 20,000 or when a second probe of the same path followed (#849).
     */
    static final int WARMUP_CALLS = 20_000;

    /** Calls measured. */
    static final int MEASURED_CALLS = 10_000;

    /**
     * The most bytes a record path may allocate over {@value #MEASURED_CALLS} measured calls: 8 per
     * call. The regressions these tests exist for cost a whole object per call, 16 bytes or more,
     * so 160,000 or more here, and a stack walk costs about 1,152 per call. A path that allocates
     * nothing per call can still read a one-off amount that depends on the JIT and the platform:
     * 12,688 bytes on JDK 25 on Linux in CI, after the warm-up above. A ceiling of one byte per call
     * failed on that one-off; this one absorbs it and still fails at half the smallest regression.
     */
    static final long CEILING = 8L * MEASURED_CALLS;

    private RecordPathAllocation() {
    }

    /**
     * {@return the bytes the calling thread allocated over {@value #MEASURED_CALLS} calls of
     * {@code call}, after {@value #WARMUP_CALLS} unmeasured ones}
     *
     * @param call one record call against an instance the detector will already track; it runs on
     *             one fresh thread, so it must not rely on the test thread's state
     * @throws InterruptedException if the test thread is interrupted while it waits for the calls
     */
    static long measuredBytes(Runnable call) throws InterruptedException {
        var mx = ManagementFactory.getThreadMXBean();
        assumeTrue(mx instanceof com.sun.management.ThreadMXBean,
                "needs the HotSpot ThreadMXBean for per-thread allocation counters");
        var bean = (com.sun.management.ThreadMXBean) mx;
        assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting is off on this JVM");

        AtomicLong allocated = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable calls = () -> {
            try {
                for (int i = 0; i < WARMUP_CALLS; i++) {
                    call.run();
                }
                long before = bean.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < MEASURED_CALLS; i++) {
                    call.run();
                }
                allocated.set(bean.getCurrentThreadAllocatedBytes() - before);
            } catch (Throwable t) { // NOPMD AvoidCatchingThrowable - rethrown on the test thread
                failure.set(t);
            }
        };
        // A thread gets its id when it is constructed, so constructing enough of them moves past
        // the cache without starting any.
        Thread worker = new Thread(calls);
        while (worker.threadId() <= 127) {
            worker = new Thread(calls);
        }
        worker.start();
        worker.join();
        if (failure.get() != null) {
            throw new AssertionError("the record call failed", failure.get());
        }
        return allocated.get();
    }
}
