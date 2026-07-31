package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ForkJoinPoolDetector.
 */
public class ForkJoinPoolDetectorTest {

    @Test
    void testNormalForkJoinUsage() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "normalPool", 2);
        detector.recordFork(pool, "normalPool", "task1");
        detector.recordJoin(pool, "normalPool", "task1");
        detector.recordTaskTime(pool, "normalPool", 10L);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal usage should not report issues");
    }

    @Test
    void testForkWithoutJoinDetection() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "forkNoJoinPool", 2);
        detector.recordFork(pool, "forkNoJoinPool", "task1");
        // Missing join!
        detector.recordForkWithoutJoin("forkNoJoinPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect fork without join");
    }

    @Test
    void testExceptionInTaskDetection() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "exceptionPool", 2);
        detector.recordException("exceptionPool", "task1", new RuntimeException("test"));

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect exception in task");
    }

    @Test
    void testWorkStealingTracking() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(4);

        detector.registerPool(pool, "stealingPool", 4);

        // Simulate work stealing events
        detector.recordWorkSteal(pool);
        detector.recordWorkSteal(pool);
        detector.recordWorkSteal(pool);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        // Work stealing is normal, not an issue
        assertFalse(report.hasIssues(), "Work stealing is normal behavior");
    }

    @Test
    void testMultiTaskForkJoin() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(4);

        detector.registerPool(pool, "multiTaskPool", 4);

        for (int i = 0; i < 10; i++) {
            detector.recordFork(pool, "multiTaskPool", "task" + i);
            detector.recordJoin(pool, "multiTaskPool", "task" + i);
            detector.recordTaskTime(pool, "multiTaskPool", 5L);
        }

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-task fork/join should work correctly");
    }

    @Test
    void testReportToString() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(2);

        detector.registerPool(pool, "testPool", 2);
        detector.recordForkWithoutJoin("testPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("FORKJOINPOOL ISSUES DETECTED"), "Report should have header");
    }

    @Test
    void testRecordForkOnUnregisteredPoolIsNoop() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        assertDoesNotThrow(() -> detector.recordFork(pool, "unregistered", "task1"),
                "Recording a fork for an unregistered pool must be a no-op, not throw");
    }

    @Test
    void testRecordJoinOnUnregisteredPoolIsNoop() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        assertDoesNotThrow(() -> detector.recordJoin(pool, "unregistered", "task1"),
                "Recording a join for an unregistered pool must be a no-op, not throw");
    }

    @Test
    void testRecordTaskTimeOnUnregisteredPoolIsNoop() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        assertDoesNotThrow(() -> detector.recordTaskTime(pool, "unregistered", 10L),
                "Recording a task time for an unregistered pool must be a no-op, not throw");
    }

    @Test
    void testRecordForkIncrementsForkCount() throws Exception {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "forkCountPool", 1);
        detector.recordFork(pool, "forkCountPool", "task1");
        detector.recordFork(pool, "forkCountPool", "task2");

        assertEquals(2, readPoolInfoIntField(detector, pool, "forkCount"),
                "Each recordFork call must increment forkCount by exactly one");
    }

    @Test
    void testRecordJoinIncrementsJoinCount() throws Exception {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "joinCountPool", 1);
        detector.recordJoin(pool, "joinCountPool", "task1");
        detector.recordJoin(pool, "joinCountPool", "task2");
        detector.recordJoin(pool, "joinCountPool", "task3");

        assertEquals(3, readPoolInfoIntField(detector, pool, "joinCount"),
                "Each recordJoin call must increment joinCount by exactly one");
    }

    @Test
    void testRecordTaskTimeAccumulatesTotalAndCount() throws Exception {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "taskTimePool", 1);
        detector.recordTaskTime(pool, "taskTimePool", 10L);
        detector.recordTaskTime(pool, "taskTimePool", 20L);

        assertEquals(30L, readPoolInfoLongField(detector, pool, "totalTaskTime"),
                "recordTaskTime must accumulate the total task time by addition, not subtraction");
        assertEquals(2, readPoolInfoIntField(detector, pool, "taskCount"),
                "Each recordTaskTime call must increment taskCount by exactly one");
    }

    @Test
    void testWorkStealSectionAbsentWithNoSteals() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "noStealPool", 1);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertFalse(report.toString().contains("Work Stealing Events"),
                "With zero work-steal events, the work-stealing section must not be printed");
    }

    @Test
    void testWorkStealSectionPresentWithExactCountAfterOneSteal() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "oneStealPool", 1);
        detector.recordWorkSteal(pool);

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertTrue(report.toString().contains("Work Stealing Events: 1"),
                "A single recordWorkSteal call must produce an exact count of 1, not -1");
    }

    @Test
    void testForkedWithoutJoinSectionContentWhenPresentAndAbsentWhenClean() {
        ForkJoinPoolDetector dirty = new ForkJoinPoolDetector();
        ForkJoinPool dirtyPool = new ForkJoinPool(1);
        dirty.registerPool(dirtyPool, "dirtyForkPool", 1);
        dirty.recordForkWithoutJoin("dirtyForkPool", "task1");

        assertTrue(dirty.analyze().toString().contains("Tasks Forked But Not Joined:"),
                "A forked-without-joined task must be listed");

        ForkJoinPoolDetector clean = new ForkJoinPoolDetector();
        ForkJoinPool cleanPool = new ForkJoinPool(1);
        clean.registerPool(cleanPool, "cleanForkPool", 1);
        clean.recordFork(cleanPool, "cleanForkPool", "task1");
        clean.recordJoin(cleanPool, "cleanForkPool", "task1");

        assertFalse(clean.analyze().toString().contains("Tasks Forked But Not Joined:"),
                "With no forked-without-joined tasks, the section must not be printed");
    }

    @Test
    void testExceptionsSectionContentWhenPresentAndAbsentWhenClean() {
        ForkJoinPoolDetector dirty = new ForkJoinPoolDetector();
        ForkJoinPool dirtyPool = new ForkJoinPool(1);
        dirty.registerPool(dirtyPool, "dirtyExPool", 1);
        dirty.recordException("dirtyExPool", "task1", new RuntimeException("boom"));

        assertTrue(dirty.analyze().toString().contains("Exceptions in Forked Tasks:"),
                "A recorded exception must be listed");

        ForkJoinPoolDetector clean = new ForkJoinPoolDetector();
        ForkJoinPool cleanPool = new ForkJoinPool(1);
        clean.registerPool(cleanPool, "cleanExPool", 1);

        assertFalse(clean.analyze().toString().contains("Exceptions in Forked Tasks:"),
                "With no exceptions recorded, the section must not be printed");
    }

    @Test
    void testNoIssuesMessagePresentWhenClean() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "cleanPool", 1);
        detector.recordFork(pool, "cleanPool", "task1");
        detector.recordJoin(pool, "cleanPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertTrue(report.toString().contains("No ForkJoinPool issues detected."),
                "A fully clean pool must print the no-issues message");
    }

    @Test
    void testNoIssuesMessageAbsentWhenIssuesExist() {
        ForkJoinPoolDetector detector = new ForkJoinPoolDetector();
        ForkJoinPool pool = new ForkJoinPool(1);

        detector.registerPool(pool, "dirtyPool", 1);
        detector.recordForkWithoutJoin("dirtyPool", "task1");

        ForkJoinPoolDetector.ForkJoinPoolReport report = detector.analyze();

        assertFalse(report.toString().contains("No ForkJoinPool issues detected."),
                "When issues are present, the no-issues message must not be printed");
    }

    private static int readPoolInfoIntField(ForkJoinPoolDetector detector, ForkJoinPool pool, String fieldName)
            throws Exception {
        Field registryField = ForkJoinPoolDetector.class.getDeclaredField("poolRegistry");
        registryField.setAccessible(true);
        Map<?, ?> registry = (Map<?, ?>) registryField.get(detector);
        Object info = registry.get(pool);
        Field field = info.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(info);
    }

    private static long readPoolInfoLongField(ForkJoinPoolDetector detector, ForkJoinPool pool, String fieldName)
            throws Exception {
        Field registryField = ForkJoinPoolDetector.class.getDeclaredField("poolRegistry");
        registryField.setAccessible(true);
        Map<?, ?> registry = (Map<?, ?>) registryField.get(detector);
        Object info = registry.get(pool);
        Field field = info.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(info);
    }
}
