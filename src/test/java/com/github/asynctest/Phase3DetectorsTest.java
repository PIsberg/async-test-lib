package com.github.asynctest;

import com.github.asynctest.diagnostics.AtomicityValidator;
import com.github.asynctest.diagnostics.BusyWaitDetector;
import com.github.asynctest.diagnostics.InterruptMonitor;
import com.github.asynctest.diagnostics.RaceConditionDetector;
import com.github.asynctest.diagnostics.ThreadLocalMonitor;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class Phase3DetectorsTest {

    @Test
    void raceConditionDetectorReportsCrossThreadAccess() throws InterruptedException {
        RaceConditionDetector detector = new RaceConditionDetector();
        Object target = new Object();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> awaitAndRun(ready, start, () -> detector.recordFieldWrite(target, "counter")));
        Thread reader = new Thread(() -> awaitAndRun(ready, start, () -> detector.recordFieldRead(target, "counter")));

        writer.start();
        reader.start();
        ready.await();
        start.countDown();
        writer.join();
        reader.join();

        RaceConditionDetector.RaceConditionReport report = detector.analyzeRaceConditions();
        assertTrue(report.hasIssues());
        assertFalse(report.unsafeAccesses.isEmpty());
    }

    @Test
    void threadLocalMonitorReportsMissingCleanup() {
        ThreadLocalMonitor monitor = new ThreadLocalMonitor();
        ThreadLocal<String> threadLocal = new ThreadLocal<>();

        monitor.recordThreadLocalInit(threadLocal, "requestContext");
        threadLocal.set("value");
        monitor.recordThreadLocalAccess(threadLocal);

        ThreadLocalMonitor.ThreadLocalReport report = monitor.analyzeThreadLocalLeaks();
        assertTrue(report.hasIssues());
        assertFalse(report.uncleanedThreadLocals.isEmpty());
    }

    @Test
    void busyWaitDetectorReportsSpinLoop() {
        BusyWaitDetector detector = new BusyWaitDetector();

        for (int i = 0; i < 15_000; i++) {
            detector.recordLoopIteration();
        }
        detector.recordYield();

        BusyWaitDetector.BusyWaitReport report = detector.analyzeBusyWaiting();
        assertTrue(report.hasIssues());
        assertFalse(report.busyWaitLoops.isEmpty());
    }

    @Test
    void atomicityValidatorReportsCheckThenActViolation() {
        AtomicityValidator validator = new AtomicityValidator();

        validator.recordCompoundOperationStart("checkThenIncrement");
        validator.recordFieldAccess("counter", 5, false);
        validator.recordFieldAccess("counter", 4, true);
        validator.recordCompoundOperationEnd("checkThenIncrement");
        validator.detectCheckThenActViolation("counter", 5, 4, true);

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();
        assertTrue(report.hasIssues());
        assertFalse(report.checkThenActViolations.isEmpty());
    }

    @Test
    void interruptMonitorReportsIgnoredInterrupts() throws InterruptedException {
        InterruptMonitor monitor = new InterruptMonitor();

        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                monitor.recordInterruptException(ex);
                monitor.recordIgnoredException("swallowed during shutdown");
            }
        });

        worker.start();
        worker.interrupt();
        worker.join();

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();
        assertTrue(report.hasIssues());
        assertFalse(report.ignoredInterrupts.isEmpty());
    }

    @Test
    void interruptMonitorMarksRestoredInterruptsAsHandled() {
        InterruptMonitor monitor = new InterruptMonitor();

        try {
            throw new InterruptedException("restore");
        } catch (InterruptedException ex) {
            monitor.recordInterruptException(ex);
            Thread.currentThread().interrupt();
            monitor.recordInterruptRestored();
            Thread.interrupted();
        }

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();
        assertFalse(report.hasIssues());
    }

    @Test
    void detectorReportsProduceReadableText() {
        assertNotNull(new RaceConditionDetector().analyzeRaceConditions().toString());
        assertNotNull(new ThreadLocalMonitor().analyzeThreadLocalLeaks().toString());
        assertNotNull(new BusyWaitDetector().analyzeBusyWaiting().toString());
        assertNotNull(new AtomicityValidator().analyzeAtomicity().toString());
        assertNotNull(new InterruptMonitor().analyzeInterruptHandling().toString());
    }

    @Test
    void asyncTestAcceptsPhase3Flags() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(Phase3FlagsDummy.class))
            .execute()
            .testEvents();

        assertTrue(events.failed().count() + events.succeeded().count() > 0);
        assertTrue(events.aborted().count() == 0);
    }

    static class Phase3FlagsDummy {
        private final AtomicInteger counter = new AtomicInteger();

        @AsyncTest(
            threads = 3,
            invocations = 3,
            detectRaceConditions = true,
            detectThreadLocalLeaks = true,
            detectBusyWaiting = true,
            detectAtomicityViolations = true,
            detectInterruptMishandling = true,
            timeoutMs = 2_000
        )
        void runsWithPhase3FlagsEnabled() {
            counter.incrementAndGet();
        }
    }

    private static void awaitAndRun(CountDownLatch ready, CountDownLatch start, Runnable action) {
        ready.countDown();
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
        action.run();
    }
}
