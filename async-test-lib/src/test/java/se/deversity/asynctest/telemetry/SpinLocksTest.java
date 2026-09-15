package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SpinLocks: compare-and-swap spinlock lifecycle and reconfirmation (#621)")
class SpinLocksTest {

    private static final VarHandle INT_HANDLE;

    static {
        try {
            INT_HANDLE = MethodHandles.lookup().findVarHandle(HolderBean.class, "busy", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final class HolderBean {
        volatile int busy;
    }

    @BeforeEach
    @AfterEach
    void resetSpinLocks() {
        SpinLocks.resetForTesting();
        HeldLocks.clear();
    }

    @Test
    @DisplayName("stale holder is revoked when another thread swaps before writing holder (#621)")
    void staleHolderIsRevokedWhenAnotherThreadSwapsBeforeWritingHolder() throws Exception {
        AtomicBoolean flag = new AtomicBoolean();
        assertTrue(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true));
        SpinLocks.Lock lock = SpinLocks.lockFor(flag);
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock after acquire");

        // Thread A releases unobserved (weaver not notified, flag becomes false).
        flag.set(false);

        CountDownLatch swapDone = new CountDownLatch(1);
        CountDownLatch verifyDone = new CountDownLatch(1);
        SpinLocks.setTestHookBeforeWonBy(() -> {
            swapDone.countDown();
            try {
                assertTrue(verifyDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread threadB = new Thread(() -> {
            assertTrue(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true));
        });
        threadB.start();

        assertTrue(swapDone.await(5, TimeUnit.SECONDS));
        // At this point, Thread B has completed the swap (flag is true), but has not run wonBy yet.
        assertTrue(flag.get(), "flag is locked by Thread B");
        assertFalse(HeldLocks.holds(lock),
                "Thread A must be revoked: B won the swap even before writing itself as holder (#621)");
        assertFalse(lock.stillHeld(), "stillHeld() must return false on thread A");

        verifyDone.countDown();
        threadB.join();
    }

    @Test
    @DisplayName("VarHandle stale holder is revoked when another thread swaps (#621)")
    void varHandleStaleHolderIsRevokedWhenAnotherThreadSwaps() throws Exception {
        HolderBean bean = new HolderBean();
        assertTrue(TelemetryRegistry.compareAndSetInt(INT_HANDLE, bean, 0, 1));
        String field = SpinLocks.fieldOf(INT_HANDLE);
        SpinLocks.Lock lock = SpinLocks.lockFor(bean, field);
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock after acquire");

        // Thread A releases unobserved.
        bean.busy = 0;

        CountDownLatch swapDone = new CountDownLatch(1);
        CountDownLatch verifyDone = new CountDownLatch(1);
        SpinLocks.setTestHookBeforeWonBy(() -> {
            swapDone.countDown();
            try {
                assertTrue(verifyDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread threadB = new Thread(() -> {
            assertTrue(TelemetryRegistry.compareAndSetInt(INT_HANDLE, bean, 0, 1));
        });
        threadB.start();

        assertTrue(swapDone.await(5, TimeUnit.SECONDS));
        assertTrue(bean.busy == 1, "busy flag was swapped to 1 by Thread B");
        assertFalse(HeldLocks.holds(lock),
                "Thread A must be revoked: B won the swap even before writing itself as holder (#621)");
        assertFalse(lock.stillHeld(), "stillHeld() must return false on thread A");

        verifyDone.countDown();
        threadB.join();
    }

    @Test
    @DisplayName("AtomicIntegerFieldUpdater stale holder is revoked when another thread swaps (#621)")
    void updaterStaleHolderIsRevokedWhenAnotherThreadSwaps() throws Exception {
        AtomicIntegerFieldUpdater<HolderBean> updater =
                AtomicIntegerFieldUpdater.newUpdater(HolderBean.class, "busy");
        TelemetryRegistry.atomicUpdaterBound(updater, "HolderBean.busy");
        HolderBean bean = new HolderBean();
        assertTrue(TelemetryRegistry.compareAndSetIntUpdater(updater, bean, 0, 1));
        SpinLocks.Lock lock = SpinLocks.lockFor(bean, "HolderBean.busy");
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock after acquire");

        // Thread A releases unobserved.
        bean.busy = 0;

        CountDownLatch swapDone = new CountDownLatch(1);
        CountDownLatch verifyDone = new CountDownLatch(1);
        SpinLocks.setTestHookBeforeWonBy(() -> {
            swapDone.countDown();
            try {
                assertTrue(verifyDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread threadB = new Thread(() -> {
            assertTrue(TelemetryRegistry.compareAndSetIntUpdater(updater, bean, 0, 1));
        });
        threadB.start();

        assertTrue(swapDone.await(5, TimeUnit.SECONDS));
        assertTrue(bean.busy == 1, "busy flag was swapped to 1 by Thread B");
        assertFalse(HeldLocks.holds(lock),
                "Thread A must be revoked: B won the swap even before writing itself as holder (#621)");
        assertFalse(lock.stillHeld(), "stillHeld() must return false on thread A");

        verifyDone.countDown();
        threadB.join();
    }

    @Test
    @DisplayName("active holder is not revoked by failed swap attempt from another thread (#621)")
    void activeHolderIsNotRevokedByFailedSwapAttemptFromAnotherThread() throws Exception {
        AtomicBoolean flag = new AtomicBoolean();
        assertTrue(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true));
        SpinLocks.Lock lock = SpinLocks.lockFor(flag);
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock");

        Thread threadB = new Thread(() -> {
            assertFalse(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true),
                    "CAS must fail because flag is already held");
        });
        threadB.start();
        threadB.join();

        assertTrue(HeldLocks.holds(lock), "Thread A's hold must remain valid after failed CAS attempt");
        assertTrue(lock.stillHeld(), "stillHeld() must return true for active holder");
    }

    @Test
    @DisplayName("AtomicInteger stale holder is revoked when another thread swaps (#621)")
    void atomicIntegerStaleHolderIsRevokedWhenAnotherThreadSwaps() throws Exception {
        AtomicInteger count = new AtomicInteger();
        assertTrue(TelemetryRegistry.compareAndSetAtomicInteger(count, 0, 1));
        SpinLocks.Lock lock = SpinLocks.lockFor(count);
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock after acquire");

        // Thread A releases unobserved.
        count.set(0);

        CountDownLatch swapDone = new CountDownLatch(1);
        CountDownLatch verifyDone = new CountDownLatch(1);
        SpinLocks.setTestHookBeforeWonBy(() -> {
            swapDone.countDown();
            try {
                assertTrue(verifyDone.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread threadB = new Thread(() -> {
            assertTrue(TelemetryRegistry.compareAndSetAtomicInteger(count, 0, 1));
        });
        threadB.start();

        assertTrue(swapDone.await(5, TimeUnit.SECONDS));
        assertTrue(count.get() == 1, "count was swapped to 1 by Thread B");
        assertFalse(HeldLocks.holds(lock),
                "Thread A must be revoked: B won the swap even before writing itself as holder (#621)");
        assertFalse(lock.stillHeld(), "stillHeld() must return false on thread A");

        verifyDone.countDown();
        threadB.join();
    }
}
