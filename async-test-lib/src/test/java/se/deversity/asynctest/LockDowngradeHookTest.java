package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.LockOrderValidator;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The woven unlock of a read-write lock's write view says it released the exclusive side, so a
 * writer that downgraded to its read lock is a reader again in {@code LockOrderValidator} (#1510).
 * Driven through the hooks the agent substitutes, on real locks.
 */
class LockDowngradeHookTest {

    @Test
    void aDowngradeThroughTheHooksMakesTheHoldShared() throws InterruptedException {
        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().validateLockOrder(true).build());
        ReentrantReadWriteLock readWrite = new ReentrantReadWriteLock();
        // Taken through the hooks woven code calls for readLock() and writeLock(), which tie each
        // view to its owner: one lock, held shared or exclusive.
        ReentrantReadWriteLock.ReadLock read = AgentLockHooks.readLock(readWrite);
        ReentrantReadWriteLock.WriteLock write = AgentLockHooks.writeLock(readWrite);
        ReentrantLock a = new ReentrantLock();
        ReentrantLock b = new ReentrantLock();

        onNewThread(context, () -> {
            AgentLockHooks.lock(write);
            AgentLockHooks.lock(read);
            AgentLockHooks.unlock(write); // the downgrade
            nest(a, b);
            AgentLockHooks.unlock(read);
        });
        onNewThread(context, () -> {
            AgentLockHooks.lock(read);
            nest(b, a);
            AgentLockHooks.unlock(read);
        });

        AsyncTestContext.install(context);
        try {
            LockOrderValidator.LockOrderReport report = AsyncTestContext.lockOrderValidator().validateLockOrder();
            assertEquals(1, report.inconsistentOrderings.size(),
                    "both threads held only the read lock around their inverted nestings: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** Takes {@code outer} then {@code inner} and lets both go, through the hooks. */
    private static void nest(ReentrantLock outer, ReentrantLock inner) {
        AgentLockHooks.lock(outer);
        AgentLockHooks.lock(inner);
        AgentLockHooks.unlock(inner);
        AgentLockHooks.unlock(outer);
    }

    private static void onNewThread(AsyncTestContext context, Runnable body) throws InterruptedException {
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            AsyncTestContext.install(context);
            try {
                body.run();
            } catch (Throwable t) { // NOPMD - reported to the test thread
                failed.set(t);
            } finally {
                AsyncTestContext.uninstall();
            }
        });
        thread.start();
        thread.join(10_000);
        assertNull(failed.get(), () -> "the thread failed: " + failed.get());
    }
}
