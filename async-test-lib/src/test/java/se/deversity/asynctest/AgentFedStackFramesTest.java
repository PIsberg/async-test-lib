package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SleepInLockDetector.SleepInLockReport;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack a detector prints starts at the reader's code, not at the hook that fed it (#858).
 *
 * <p>The detectors that print a raw stack skipped a fixed three frames, which on a direct call is
 * {@code getStackTrace}, the detector's record method and its caller. On the agent path the hook and
 * the detector's own overloads sit in between, so the first line the reader saw was
 * {@code AgentSleepHooks}, a line they cannot change. Each case calls the hook from one helper in
 * this class, standing in for a woven call site, and asserts that the helper is the first frame.
 */
class AgentFedStackFramesTest {

    private static final Object MONITOR = new Object();

    @Test
    @DisplayName("a sleep-in-lock stack starts at the line that slept, not at the sleep hook")
    void sleepInLockStackStartsAtTheCaller() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSleepInLock(true).build();
        AsyncTestContext.install(new AsyncTestContext(cfg));
        SleepInLockReport report;
        try {
            AsyncTestContext.sleepInLockDetector().startMonitoring();
            sleepOnTheCallersLine();
            report = AsyncTestContext.sleepInLockDetector().analyze();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing here interrupts", e);
        } finally {
            AsyncTestContext.uninstall();
        }

        assertTrue(report.hasIssues(), "the premise: a sleep holding a monitor is reported: " + report);
        List<String> frames = report.toString().lines()
                .map(String::strip)
                .filter(line -> line.startsWith("at "))
                .toList();
        assertFalse(frames.isEmpty(), "the report must print a stack: " + report);
        assertTrue(frames.get(0).startsWith("at " + AgentFedStackFramesTest.class.getName() + ".sleepOnTheCallersLine("),
                "the first frame must be the line that slept, found " + frames);
    }

    /** The one line in this class that sleeps under a monitor, standing in for a woven call site. */
    private static void sleepOnTheCallersLine() throws InterruptedException {
        synchronized (MONITOR) {
            AgentSleepHooks.sleepHoldingMonitor(1L, MONITOR);
        }
    }
}
