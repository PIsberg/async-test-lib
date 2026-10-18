package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the rule {@link JfrPinningStream} reports by: only a pin that recurs in two rounds
 * is the code's, a pin seen in one round is warm-up.
 */
class JfrPinningStreamTest {

    private static final String SITE = "com.example.Point.distanceFromOrigin(Unknown Source)";

    @Test
    void twoClassesFirstInitializedAtOneSiteInTwoRoundsAreWarmUp() {
        // The Corpus Eval JDK 25 failure on correct StampedLock code: one readLock call site, and
        // the first contended readLock in one round waited for StampedLock$WriterNode's initializer
        // while the first queued reader in a later round waited for ReaderNode's. Each class
        // initializes once per JVM, so neither pin can recur; together they spanned two rounds.
        VirtualThreadPinningDetector detector = monitoring();
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, "Waited for initialization of java.util.concurrent.locks.StampedLock$WriterNode "
                        + "by another thread (Object.wait)", VirtualThreadPinningDetector.PinningCause.CLASS_INIT,
                        frame("java.util.concurrent.locks.StampedLock", "acquireRead", 1307)),
                pin(2L, "Freeze or preempt failed (2) (Contended monitor enter)",
                        VirtualThreadPinningDetector.PinningCause.OTHER,
                        frame("java.util.concurrent.locks.StampedLock", "acquireRead", 1316)),
                pin(3L, "Waited for initialization of java.util.concurrent.locks.StampedLock$ReaderNode "
                        + "by another thread (Object.wait)", VirtualThreadPinningDetector.PinningCause.CLASS_INIT,
                        frame("java.util.concurrent.locks.StampedLock", "acquireRead", 1316)));

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1, 3L, 1), detector);

        var report = detector.analyzePinning();
        assertFalse(report.hasIssues(), "one-time class initializations are warm-up: " + report);
        assertEquals(2, singleRound, "each initialization is its own pin, seen in one round");
    }

    @Test
    void aPinRecurringAtOneFrameInTwoRoundsIsReported() {
        // The corpus MUST_FIRE shape: a fresh copy of a class per round whose initializer sleeps,
        // so the same initializer pins at the same frame in every round.
        VirtualThreadPinningDetector detector = monitoring();
        StackTraceElement sleep = frame("java.lang.Thread", "sleepNanos", 512);
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, "VM call to Init.<clinit> on stack (Thread.sleep)",
                        VirtualThreadPinningDetector.PinningCause.CLASS_INIT_EXECUTING, sleep),
                pin(2L, "VM call to Init.<clinit> on stack (Thread.sleep)",
                        VirtualThreadPinningDetector.PinningCause.CLASS_INIT_EXECUTING, sleep));

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1), detector);

        assertEquals(2, detector.analyzePinning().getEvents().size(), "one pin per thread and site");
        assertEquals(0, singleRound);
    }

    @Test
    void aPinSeenInOneRoundOnlyIsNotReported() {
        VirtualThreadPinningDetector detector = monitoring();
        StackTraceElement sleep = frame("java.lang.Thread", "sleepNanos", 512);
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, "Native or VM frame on stack (Thread.sleep)", VirtualThreadPinningDetector.PinningCause.NATIVE, sleep),
                pin(2L, "Native or VM frame on stack (Thread.sleep)", VirtualThreadPinningDetector.PinningCause.NATIVE, sleep));

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 0), detector);

        assertEquals(0, detector.analyzePinning().getEvents().size());
        assertEquals(1, singleRound);
    }

    private static VirtualThreadPinningDetector monitoring() {
        VirtualThreadPinningDetector detector = new VirtualThreadPinningDetector();
        detector.startMonitoring();
        return detector;
    }

    /** A pin at {@link #SITE} whose stack starts with {@code top}, the JDK frame it pinned in. */
    private static JfrPinningStream.Pin pin(long threadId, String reason,
                                            VirtualThreadPinningDetector.PinningCause cause,
                                            StackTraceElement top) {
        StackTraceElement[] frames = {
            top,
            frame("se.deversity.asynctest.AgentLockHooks", "readLock", -1),
            frame("com.example.Point", "distanceFromOrigin", 42)
        };
        return new JfrPinningStream.Pin(threadId, "worker-" + threadId, SITE,
                "JFR jdk.VirtualThreadPinned: " + reason, cause, 0L, frames);
    }

    private static StackTraceElement frame(String type, String method, int line) {
        return new StackTraceElement(type, method, null, line);
    }
}
