package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConditionVariableDetector.
 */
public class ConditionVariableDetectorTest {

    @Test
    void testNormalConditionUsage() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "normal-condition");
        
        detector.recordAwait(condition, "normal-condition");
        detector.recordSignal(condition, "normal-condition", false);
        detector.recordAwaitExit(condition, "normal-condition", false);
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "the await was woken by the signal recorded while it waited");
        assertTrue(report.threadActivity.stream().anyMatch(a -> a.startsWith("normal-condition: ")), "Should track activity");
    }

    @Test
    void testSignalWithNoWaiterIsANoteNotAFinding() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "no-waiter-condition");
        
        // Signal without any waiters: correct whenever the consumer tests its predicate first (#583)
        detector.recordSignal(condition, "no-waiter-condition", false);
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "A signal with nobody waiting is not a finding");
        assertFalse(report.signalsWithNoWaiter.isEmpty(), "It is still shown as a note");
        assertTrue(report.toString().contains("not a finding"), report.toString());
    }

    @Test
    void testRecordedAwaitWithoutItsLockIsANoteNotAFinding() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "stuck-waiter-condition");
        
        // Thread waiting without signal
        detector.recordAwait(condition, "stuck-waiter-condition");
        // Don't call recordAwaitExit or signal
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        // #666: registered without its lock, only the body's own records say it waits.
        assertFalse(report.hasIssues(), "A recorded await with no lock to read it from is a note: " + report);
        assertTrue(report.stuckWaiters.isEmpty(), report.toString());
        assertTrue(report.unconfirmedWaits.stream().anyMatch(n -> n.contains("still waiting at analysis")),
                report.toString());
    }

    @Test
    void testMissingSignalIsANoteNotAFinding() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "missing-signal-condition");
        
        // Multiple awaits without any signals
        detector.recordAwait(condition, "missing-signal-condition");
        detector.recordAwaitExit(condition, "missing-signal-condition", false);
        detector.recordAwait(condition, "missing-signal-condition");
        detector.recordAwaitExit(condition, "missing-signal-condition", false);
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        // #666: decided only from the body's own recordAwaitExit(..., false), so a note.
        assertFalse(report.hasIssues(), "A missing signal is the body's declaration: " + report);
        assertFalse(report.unsignalledWakeups.isEmpty(), "It is still shown as a note");
    }

    @Test
    void testSignalAllTracking() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "signalall-condition");
        
        detector.recordSignal(condition, "signalall-condition", true); // signalAll
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        String activity = report.threadActivity.stream().filter(a -> a.startsWith("signalall-condition: ")).findFirst().orElse("");
        assertTrue(activity != null && activity.contains("signalAll"),
                   "Should track signalAll calls");
    }

    @Test
    void testThreadActivityTracking() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "activity-condition");
        
        Thread t1 = new Thread(() -> {
            detector.recordAwait(condition, "activity-condition");
            detector.recordAwaitExit(condition, "activity-condition", false);
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordSignal(condition, "activity-condition", false);
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.threadActivity.isEmpty(), "Should track thread activity");
    }

    @Test
    void testNullSafety() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        
        // Should not throw on null inputs
        detector.registerCondition(null, "null-condition");
        detector.recordAwait(null, "null");
        detector.recordAwaitExit(null, "null", false);
        detector.recordSignal(null, "null", false);
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        
        detector.registerCondition(condition, "test-condition");
        
        // A thread left inside an await, with no lock registered: a note (#666)
        detector.recordAwait(condition, "test-condition");
        
        ConditionVariableDetector.ConditionVariableReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("CONDITION VARIABLE ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("not a finding") && reportStr.contains("still waiting at analysis"),
                "Report should show the unconfirmed waiter as a note: " + reportStr);
        assertFalse(reportStr.contains("Stuck Waiters"), reportStr);
    }

    /**
     * Two conditions may share a name. Each keeps its own thread-activity line; filed under the
     * name, the second condition's line overwrote the first's (#789).
     */
    @Test
    void twoConditionsWithTheSameNameEachKeepTheirThreadActivity() {
        ConditionVariableDetector detector = new ConditionVariableDetector();
        ReentrantLock lock = new ReentrantLock();
        Condition signalled = lock.newCondition();
        Condition broadcast = lock.newCondition();
        detector.registerCondition(signalled, "ready");
        detector.registerCondition(broadcast, "ready");
        detector.recordSignal(signalled, "ready", false);
        detector.recordSignal(broadcast, "ready", true);

        String report = detector.analyze().toString();
        assertTrue(report.contains("ready: 0 awaits (0 timed out, 0 abandoned in an earlier round), "
                        + "1 signalling threads, 1 signals, 0 signalAll"),
                "the signalled condition's line survives beside the broadcast one's: " + report);
        assertTrue(report.contains("ready: 0 awaits (0 timed out, 0 abandoned in an earlier round), "
                        + "1 signalling threads, 0 signals, 1 signalAll"),
                "the broadcast condition's line survives beside the signalled one's: " + report);
    }
}
