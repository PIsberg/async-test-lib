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

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1, 3L, 1), detector).singleRoundSites();

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

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1), detector).singleRoundSites();

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

        int singleRound = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 0), detector).singleRoundSites();

        assertEquals(0, detector.analyzePinning().getEvents().size());
        assertEquals(1, singleRound);
    }

    /** JDK 24-26's reason for a monitor enter the JVM could not preempt inside a critical section. */
    private static final String CRITICAL_SECTION_ENTER = "Freeze or preempt failed (2) (Contended monitor enter)";

    @Test
    void aJdkLockContendedInsideTheJvmsCriticalSectionIsNotTheCodes() {
        // The Corpus Eval JDK 25 failure on correct CompletableFuture code (#1028): the first join
        // that has to wait creates a Signaller, and the VM, initializing that class, contends on
        // its init lock in a section it does not let the virtual thread leave. A JDK 24 probe
        // recorded the same event at the same instruction. Shown here at that frame in two rounds,
        // which nothing about the reason or the frame ties to the body.
        VirtualThreadPinningDetector detector = monitoring();
        StackTraceElement[] frames = {
            frame("java.util.concurrent.CompletableFuture", "waitingGet", 1931),
            frame("java.util.concurrent.CompletableFuture", "join", 2163),
            frame("se.deversity.asynctest.AgentConcurrencyUtilHooks", "join", -1),
            frame("com.example.Reader", "read", 17)
        };
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, CRITICAL_SECTION_ENTER, frames), pin(2L, CRITICAL_SECTION_ENTER, frames));

        var delivery = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1), detector);

        var report = detector.analyzePinning();
        assertFalse(report.hasIssues(), "the JVM's own section on the JDK's lock is not the body's pin: " + report);
        assertEquals(2, delivery.criticalSectionPins(), "counted for the DEBUG line, not dropped silently");
    }

    @Test
    void theClassLoadingLockTheJvmContendsOnInEveryRoundIsNotTheCodes() {
        // A JDK 24 and a JDK 26 probe: virtual threads loading a class through Class.forName, a
        // different class in every round, contend on the application loader's per-name lock under
        // the VM's call into loadClass, at the same JDK frame each round.
        VirtualThreadPinningDetector detector = monitoring();
        StackTraceElement[] frames = {
            frame("java.lang.ClassLoader", "loadClass", 490),
            frame("java.lang.Class", "forName0", -1),
            frame("java.lang.Class", "forName", 463),
            frame("java.lang.Class", "forName", 454),
            frame("com.example.Plugins", "load", 30)
        };
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, CRITICAL_SECTION_ENTER, frames), pin(2L, CRITICAL_SECTION_ENTER, frames),
                pin(3L, CRITICAL_SECTION_ENTER, frames));

        var delivery = JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1, 3L, 2), detector);

        var report = detector.analyzePinning();
        assertFalse(report.hasIssues(), "class loading on the JDK's loader lock is warm-up: " + report);
        assertEquals(3, delivery.criticalSectionPins());
    }

    @Test
    void aMonitorTheCodeTakesInsideTheJvmsCriticalSectionIsStillReported() {
        // The same reason with the body's own monitor on top: a class loader the code wrote, whose
        // loadClass takes a shared monitor while the VM is loading through it. The probes saw it
        // pin in every round on JDK 24 and 26; it is the code's lock, so it stays a finding.
        VirtualThreadPinningDetector detector = monitoring();
        StackTraceElement[] frames = {
            frame("com.example.PluginLoader", "loadClass", 61),
            frame("java.lang.ClassLoader", "loadClass", 490),
            frame("java.lang.Class", "forName0", -1),
            frame("com.example.Plugins", "load", 30)
        };
        List<JfrPinningStream.Pin> pins = List.of(
                pin(1L, CRITICAL_SECTION_ENTER, frames), pin(2L, CRITICAL_SECTION_ENTER, frames));

        JfrPinningStream.deliver(pins, Map.of(1L, 0, 2L, 1), detector);

        assertEquals(2, detector.analyzePinning().getEvents().size(), "the code's own monitor, in two rounds");
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

    /** A pin with {@code frames} as its whole stack, its site the first frame outside the JDK and the hooks. */
    private static JfrPinningStream.Pin pin(long threadId, String reason, StackTraceElement... frames) {
        String site = "";
        for (StackTraceElement frame : frames) {
            String type = frame.getClassName();
            if (!type.startsWith("java.") && !type.startsWith("se.deversity.asynctest.Agent")) {
                site = frame.toString();
                break;
            }
        }
        return new JfrPinningStream.Pin(threadId, "worker-" + threadId, site,
                "JFR jdk.VirtualThreadPinned: " + reason, VirtualThreadPinningDetector.PinningCause.OTHER, 0L, frames);
    }

    private static StackTraceElement frame(String type, String method, int line) {
        return new StackTraceElement(type, method, null, line);
    }
}
