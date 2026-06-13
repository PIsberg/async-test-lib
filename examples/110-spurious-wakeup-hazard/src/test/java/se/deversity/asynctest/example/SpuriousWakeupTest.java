package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example demonstrating SpuriousWakeupDetector flagging monitor wait outside a loop.
 */
class SpuriousWakeupTest {

    private final Object lock = new Object();
    private boolean ready = false;

    @Test
    void testWaitInLoop_doesNotFlag() throws InterruptedException {
        synchronized (lock) {
            // Safe: wait in a while loop
            while (!ready) {
                // In actual code, wait would go here. We just simulate it.
                break;
            }
        }
    }

    @Disabled("Remove @Disabled to see the bug detected by SpuriousWakeupDetector")
    @AsyncTest(threads = 2, invocations = 5, detectAll = false, detectSpuriousWakeupHazard = true)
    void test_concurrent_detectsSpuriousWakeupHazard() throws InterruptedException {
        var mon = AsyncTestContext.spuriousWakeupHazardDetector();
        Thread thread = Thread.currentThread();

        synchronized (lock) {
            // Bug: waiting outside a loop (e.g. using if instead of while, or no check at all)
            if (!ready) {
                // Record the wait hazard (insideLoop = false)
                mon.recordWait(lock, "shared-lock", false, thread);
            }
        }
    }
}
