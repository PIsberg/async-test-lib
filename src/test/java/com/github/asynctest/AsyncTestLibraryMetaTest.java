package com.github.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

public class AsyncTestLibraryMetaTest {

    @Test
    void testRaceConditionIsCaught() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(RaceConditionDummy.class))
                .execute()
                .testEvents();
                
        long failed = testEvents.failed().count();
        assertEquals(1, failed, "The async test should have failed due to race condition.");
    }

    public static class RaceConditionDummy {
        private int unprotectedCounter = 0;

        @AsyncTest(threads = 20, invocations = 100)
        void testCounterRace() {
            int current = unprotectedCounter;
            Thread.yield();
            unprotectedCounter = current + 1;
        }

        @AfterEach
        void verify() {
            assertEquals(20 * 100, unprotectedCounter, "Counter should be 2000 if thread-safe");
        }
    }

    @Test
    void testDeadlockIsCaught() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(DeadlockDummy.class))
                .execute()
                .testEvents();
                
        long failed = testEvents.failed().count();
        assertEquals(1, failed, "The async test should have failed due to deadlock (timeout).");
    }

    public static class DeadlockDummy {
        private final Object lock1 = new Object();
        private final Object lock2 = new Object();
        private final AtomicInteger threadAssigner = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 1500)
        void testDeadlock() throws InterruptedException {
            int id = threadAssigner.getAndIncrement();
            if (id % 2 == 0) {
                synchronized (lock1) {
                    Thread.sleep(100);
                    synchronized (lock2) { }
                }
            } else {
                synchronized (lock2) {
                    Thread.sleep(100);
                    synchronized (lock1) { }
                }
            }
        }
    }

    @Test
    void testVisibilityIssueIsCaught() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(VisibilityDummy.class))
                .execute()
                .testEvents();
                
        // Should timeout because of infinite loop due to non-volatile flag tracking
        long failed = testEvents.failed().count();
        assertEquals(1, failed, "The async test should have failed due to visibility issue (timeout).");
    }

    public static class VisibilityDummy {
        private boolean stopHolder = false;
        private final AtomicInteger assigner = new AtomicInteger();

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 2000)
        void testVisibility() throws Exception {
            if (assigner.getAndIncrement() % 2 == 0) {
                Thread.sleep(100);
                stopHolder = true;
            } else {
                while (!stopHolder) {
                    // Spin endlessly. Without volatile, the JIT optimizes this to true loop
                }
            }
        }
    }

    @Test
    void testVirtualThreadStress() {
        Events testEvents = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(VirtualThreadStressDummy.class))
                .execute()
                .testEvents();
                
        assertEquals(0, testEvents.failed().count(), "Virtual threads stress test should easily pass without OutOfMemory or thread exhaustion.");
    }

    public static class VirtualThreadStressDummy {
        @AsyncTest(threads = 5000, invocations = 2, useVirtualThreads = true, timeoutMs = 15000)
        void stress() throws InterruptedException {
            Thread.sleep(10);
        }
    }
}
