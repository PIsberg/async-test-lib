package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating TryLockMisuseDetector flagging unsafe unlock calls.
 */
class TryLockMisuseTest {

    private ReentrantLock lock;

    @BeforeEach
    void setUp() {
        lock = new ReentrantLock();
    }

    @Test
    void testCorrectTryLock_doesNotFlag() {
        boolean acquired = lock.tryLock();
        if (acquired) {
            try {
                // Critical section
            } finally {
                lock.unlock();
            }
        }
    }

    @Disabled("Remove @Disabled to see the bug detected by TryLockMisuseDetector")
    @AsyncTest(threads = 2, invocations = 5, detectAll = false, detectTryLockMisuse = true, failOn = FailOn.LOW)
    void test_concurrent_detectsTryLockMisuse() {
        var mon = AsyncTestContext.tryLockMisuseDetector();
        Thread thread = Thread.currentThread();

        // Simulate tryLock failure (another thread holding lock, or just mock result)
        boolean acquired = lock.tryLock();
        mon.recordTryLockResult(lock, "shared-lock", acquired, thread);

        // Bug: Unconditionally unlocking even if tryLock returned false
        try {
            // Do work (unsafe, as lock wasn't actually acquired!)
        } finally {
            lock.unlock();
            mon.recordUnlock(lock, "shared-lock", thread);
        }
    }
}
