package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import java.util.function.Supplier;
import java.util.stream.Stream;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    static class ResolveBase {
        volatile int state;
    }

    static final class ResolveSub extends ResolveBase {
        volatile int busy;
    }

    private static final String SUB_BUSY = ResolveSub.class.getName() + ".busy";
    private static final String BASE_STATE = ResolveBase.class.getName() + ".state";

    private static AtomicIntegerFieldUpdater<ResolveBase> baseStateUpdater() {
        return AtomicIntegerFieldUpdater.newUpdater(ResolveBase.class, "state");
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
        SpinLocks.setTestHookAfterSwap(() -> {
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
        SpinLocks.setTestHookAfterSwap(() -> {
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
        SpinLocks.setTestHookAfterSwap(() -> {
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
        SpinLocks.setTestHookAfterSwap(() -> {
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

    @Test
    @DisplayName("a contender that saw the flag free does not revoke a holder that won after it looked (#621)")
    void contenderThatSawTheFlagFreeDoesNotRevokeAHolderThatWonAfterItLooked() throws Exception {
        AtomicBoolean flag = new AtomicBoolean();
        assertTrue(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true));
        SpinLocks.Lock lock = SpinLocks.lockFor(flag);
        assertTrue(lock != null && HeldLocks.holds(lock), "Thread A holds lock after acquire");

        // Thread A releases unobserved, leaving a stale holder behind for B to find.
        flag.set(false);

        CountDownLatch contenderChecked = new CountDownLatch(1);
        CountDownLatch holderWon = new CountDownLatch(1);
        Thread threadB = new Thread(() ->
                assertFalse(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true),
                        "A took the flag while B was paused, so B's swap must fail"),
                "spin-lock-contender");
        SpinLocks.setTestHookBeforeRevoke(() -> {
            if (Thread.currentThread() != threadB) {
                return;
            }
            // B has read the stale holder and seen the flag free; A now wins and declares.
            contenderChecked.countDown();
            try {
                assertTrue(holderWon.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        threadB.start();
        assertTrue(contenderChecked.await(5, TimeUnit.SECONDS));

        assertTrue(TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true),
                "A wins the flag while B is paused between its check and its revocation");
        holderWon.countDown();
        threadB.join();

        assertTrue(flag.get(), "A still holds the flag");
        assertTrue(lock.stillHeld(),
                "B saw the flag free before A won, so what B may revoke is only the hold that had "
                        + "already ended; revoking A's live hold would report A's guarded writes");
        assertTrue(HeldLocks.holds(lock), "A's lockset must still contain the spinlock");
    }

    /**
     * #658: a contender reads the flag locked, and before its swap lands the holder releases
     * through a value-returning form. The release must be observed, so that from the contender's
     * swap until it writes its stamp the old holder does not pass re-confirmation.
     */
    @TestFactory
    @DisplayName("a release through a value-returning form between a contender's check and its swap revokes the holder (#658)")
    Stream<DynamicTest> releaseBetweenAContendersCheckAndItsSwapRevokesTheHolder() {
        return Stream.of(
                atomicIntegerCase("AtomicInteger.getAndSet(0)",
                        count -> TelemetryRegistry.getAndSetAtomicInteger(count, 0)),
                atomicIntegerCase("AtomicInteger.getAndAdd(-1)",
                        count -> TelemetryRegistry.getAndAddAtomicInteger(count, -1)),
                atomicIntegerCase("AtomicInteger.addAndGet(-1)",
                        count -> TelemetryRegistry.addAndGetAtomicInteger(count, -1)),
                atomicIntegerCase("AtomicInteger.getAndDecrement()",
                        TelemetryRegistry::getAndDecrementAtomicInteger),
                atomicIntegerCase("AtomicInteger.decrementAndGet()",
                        TelemetryRegistry::decrementAndGetAtomicInteger),
                atomicIntegerCase("AtomicInteger.compareAndExchange(1, 0)",
                        count -> TelemetryRegistry.compareAndExchangeAtomicInteger(count, 1, 0)),
                atomicIntegerCase("AtomicInteger.weakCompareAndSetPlain(1, 0)", count -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetPlainAtomicInteger(count, 1, 0))),
                atomicIntegerCase("AtomicInteger.weakCompareAndSetVolatile(1, 0)", count -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetVolatileAtomicInteger(count, 1, 0))),
                atomicBooleanCase("AtomicBoolean.compareAndExchange(true, false)",
                        flag -> TelemetryRegistry.compareAndExchangeAtomicBoolean(flag, true, false)),
                atomicBooleanCase("AtomicBoolean.weakCompareAndSetPlain(true, false)", flag -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetPlainAtomicBoolean(flag, true, false))),
                atomicBooleanCase("AtomicBoolean.weakCompareAndSetVolatile(true, false)", flag -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetVolatileAtomicBoolean(flag, true, false))),
                varHandleCase("VarHandle.getAndSet(bean, 0)",
                        bean -> TelemetryRegistry.getAndSetInt(INT_HANDLE, bean, 0)),
                varHandleCase("VarHandle.getAndAdd(bean, -1)",
                        bean -> TelemetryRegistry.getAndAddInt(INT_HANDLE, bean, -1)),
                varHandleCase("VarHandle.compareAndExchange(bean, 1, 0)",
                        bean -> TelemetryRegistry.compareAndExchangeInt(INT_HANDLE, bean, 1, 0)),
                varHandleCase("VarHandle.weakCompareAndSet(bean, 1, 0)", bean -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetInt(INT_HANDLE, bean, 1, 0))),
                varHandleCase("VarHandle.weakCompareAndSetPlain(bean, 1, 0)", bean -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetPlainInt(INT_HANDLE, bean, 1, 0))),
                updaterCase("AtomicIntegerFieldUpdater.getAndSet(bean, 0)",
                        (updater, bean) -> TelemetryRegistry.getAndSetIntUpdater(updater, bean, 0)),
                updaterCase("AtomicIntegerFieldUpdater.getAndAdd(bean, -1)",
                        (updater, bean) -> TelemetryRegistry.getAndAddIntUpdater(updater, bean, -1)),
                updaterCase("AtomicIntegerFieldUpdater.addAndGet(bean, -1)",
                        (updater, bean) -> TelemetryRegistry.addAndGetIntUpdater(updater, bean, -1)),
                updaterCase("AtomicIntegerFieldUpdater.getAndDecrement(bean)",
                        TelemetryRegistry::getAndDecrementIntUpdater),
                updaterCase("AtomicIntegerFieldUpdater.decrementAndGet(bean)",
                        TelemetryRegistry::decrementAndGetIntUpdater),
                updaterCase("AtomicIntegerFieldUpdater.weakCompareAndSet(bean, 1, 0)", (updater, bean) -> spinUntil(
                        () -> TelemetryRegistry.weakCompareAndSetIntUpdater(updater, bean, 1, 0))));
    }

    private static void spinUntil(BooleanSupplier attempt) {
        while (!attempt.getAsBoolean()) {
            Thread.onSpinWait();
        }
    }

    private static DynamicTest atomicIntegerCase(String form, Consumer<AtomicInteger> release) {
        return DynamicTest.dynamicTest(form, () -> {
            AtomicInteger count = new AtomicInteger();
            assertReleaseBetweenCheckAndSwapRevokes(form,
                    () -> TelemetryRegistry.compareAndSetAtomicInteger(count, 0, 1),
                    () -> SpinLocks.lockFor(count), () -> release.accept(count));
        });
    }

    private static DynamicTest atomicBooleanCase(String form, Consumer<AtomicBoolean> release) {
        return DynamicTest.dynamicTest(form, () -> {
            AtomicBoolean flag = new AtomicBoolean();
            assertReleaseBetweenCheckAndSwapRevokes(form,
                    () -> TelemetryRegistry.compareAndSetAtomicBoolean(flag, false, true),
                    () -> SpinLocks.lockFor(flag), () -> release.accept(flag));
        });
    }

    private static DynamicTest varHandleCase(String form, Consumer<HolderBean> release) {
        return DynamicTest.dynamicTest(form, () -> {
            HolderBean bean = new HolderBean();
            assertReleaseBetweenCheckAndSwapRevokes(form,
                    () -> TelemetryRegistry.compareAndSetInt(INT_HANDLE, bean, 0, 1),
                    () -> SpinLocks.lockFor(bean, SpinLocks.fieldOf(INT_HANDLE)),
                    () -> release.accept(bean));
        });
    }

    private static DynamicTest updaterCase(String form,
            BiConsumer<AtomicIntegerFieldUpdater<HolderBean>, HolderBean> release) {
        return DynamicTest.dynamicTest(form, () -> {
            AtomicIntegerFieldUpdater<HolderBean> updater =
                    AtomicIntegerFieldUpdater.newUpdater(HolderBean.class, "busy");
            HolderBean bean = new HolderBean();
            assertReleaseBetweenCheckAndSwapRevokes(form,
                    () -> {
                        TelemetryRegistry.atomicUpdaterBound(updater, "HolderBean.busy");
                        return TelemetryRegistry.compareAndSetIntUpdater(updater, bean, 0, 1);
                    },
                    () -> SpinLocks.lockFor(bean, "HolderBean.busy"),
                    () -> release.accept(updater, bean));
        });
    }

    /**
     * Runs the #658 interleaving: A holds; a contender reads the flag locked and pauses before its
     * swap; A releases through {@code release}; the contender's swap lands and it pauses before
     * writing its stamp. In that span A must neither pass re-confirmation nor keep the lock in its
     * lockset.
     */
    private static void assertReleaseBetweenCheckAndSwapRevokes(String form, BooleanSupplier acquire,
            Supplier<SpinLocks.Lock> lockOf, Runnable release) throws InterruptedException {
        SpinLocks.resetForTesting();
        HeldLocks.clear();
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch swapped = new CountDownLatch(1);
        CountDownLatch verified = new CountDownLatch(1);
        AtomicBoolean contenderWon = new AtomicBoolean();
        Thread contender = new Thread(() -> contenderWon.set(acquire.getAsBoolean()),
                "spin-lock-contender");
        try {
            assertTrue(acquire.getAsBoolean(), "A takes the flag");
            SpinLocks.Lock lock = lockOf.get();
            assertTrue(lock != null && HeldLocks.holds(lock), "A holds the lock after its acquire");

            SpinLocks.setTestHookAfterCheck(() -> pauseOn(contender, checked, released));
            SpinLocks.setTestHookAfterSwap(() -> pauseOn(contender, swapped, verified));
            contender.start();
            assertTrue(checked.await(5, TimeUnit.SECONDS), "the contender checked the flag while A held it");

            release.run();
            released.countDown();
            assertTrue(swapped.await(5, TimeUnit.SECONDS), "the contender's swap landed after A's release");

            boolean stillHeld = lock.stillHeld();
            boolean inLockset = HeldLocks.holds(lock);
            verified.countDown();
            contender.join(5_000);

            assertFalse(stillHeld, "A released through " + form + " after the contender checked the "
                    + "flag and before its swap; from that swap until the contender's stamp write, A "
                    + "must not pass re-confirmation, or its unguarded accesses look guarded (#658)");
            assertFalse(inLockset, "the observed release through " + form + " must leave A's lockset");
            assertTrue(contenderWon.get(), "the contender's swap must win once A released");
        } finally {
            released.countDown();
            verified.countDown();
            contender.join(5_000);
            SpinLocks.resetForTesting();
            HeldLocks.clear();
        }
    }

    private static void pauseOn(Thread thread, CountDownLatch reached, CountDownLatch resume) {
        if (Thread.currentThread() != thread) {
            return;
        }
        reached.countDown();
        try {
            assertTrue(resume.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    @Test
    @DisplayName("a pre-attach updater resolves when its whole hierarchy is scanned and records one field (#619)")

    void preAttachUpdaterResolvesWhenTheWholeHierarchyIsScannedWithOneField() {
        SpinLocks.recordScannedClass(ResolveBase.class.getName());
        SpinLocks.recordScannedClass(ResolveSub.class.getName());
        SpinLocks.recordUpdaterField(ResolveSub.class.getName(), SUB_BUSY);

        assertEquals(SUB_BUSY, SpinLocks.fieldOf(
                AtomicIntegerFieldUpdater.newUpdater(ResolveSub.class, "busy"), new ResolveSub()));
    }

    @Test
    @DisplayName("an updater from a superclass the weaver never scanned is not resolved to the subclass's field (#619)")
    void updaterFromAnUnscannedSuperclassIsNotResolvedToTheSubclassField() {
        // ResolveBase binds its own updater, but the weaver never saw it: only the subclass's field
        // is on record, and naming it for a swap through the base's updater is the wrong flag.
        SpinLocks.recordScannedClass(ResolveSub.class.getName());
        SpinLocks.recordUpdaterField(ResolveSub.class.getName(), SUB_BUSY);

        assertNull(SpinLocks.fieldOf(baseStateUpdater(), new ResolveSub()));
    }

    @Test
    @DisplayName("a hierarchy that records two updater fields resolves neither (#619)")
    void hierarchyWithTwoRecordedFieldsResolvesNeither() {
        SpinLocks.recordScannedClass(ResolveBase.class.getName());
        SpinLocks.recordScannedClass(ResolveSub.class.getName());
        SpinLocks.recordUpdaterField(ResolveBase.class.getName(), BASE_STATE);
        SpinLocks.recordUpdaterField(ResolveSub.class.getName(), SUB_BUSY);

        assertNull(SpinLocks.fieldOf(baseStateUpdater(), new ResolveSub()));
    }

    @Test
    @DisplayName("a hierarchy with an updater the weaver could not read resolves nothing (#619)")
    void hierarchyWithAnUnreadableUpdaterResolvesNothing() {
        SpinLocks.recordScannedClass(ResolveBase.class.getName());
        SpinLocks.recordScannedClass(ResolveSub.class.getName());
        // The only thing on record is that the base makes an updater nobody could read.
        SpinLocks.recordUpdaterField(ResolveBase.class.getName(), SpinLocks.UNREADABLE);

        assertNull(SpinLocks.fieldOf(baseStateUpdater(), new ResolveSub()));
    }
}
