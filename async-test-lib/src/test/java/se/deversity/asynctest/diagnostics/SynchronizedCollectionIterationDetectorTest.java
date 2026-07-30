package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class SynchronizedCollectionIterationDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SynchronizedCollectionIterationDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenIteratingWithLock() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        d.recordWrapperCreated(list, "my-list");
        d.recordIterationStarted(list, Thread.currentThread(), true); // holding lock
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsIterationWithoutLock() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        d.recordWrapperCreated(list, "my-list");
        d.recordIterationStarted(list, Thread.currentThread(), false); // not holding lock
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("my-list"));
    }

    @Test
    void testNoIssueForUnregisteredWrapper() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        // wrapper not registered — iteration not tracked
        d.recordIterationStarted(list, Thread.currentThread(), false);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testCountsMultipleUnsafeIterations() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        d.recordWrapperCreated(list, "list");
        d.recordIterationStarted(list, Thread.currentThread(), false);
        d.recordIterationStarted(list, Thread.currentThread(), false);
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("2"));
    }

    @Test
    void testDetectsMultipleWrappers() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> l1 = Collections.synchronizedList(new ArrayList<>());
        List<String> l2 = Collections.synchronizedList(new ArrayList<>());
        d.recordWrapperCreated(l1, "list-1");
        d.recordWrapperCreated(l2, "list-2");
        d.recordIterationStarted(l1, Thread.currentThread(), false);
        d.recordIterationStarted(l2, Thread.currentThread(), false);
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNullSafety() {
        var d = new SynchronizedCollectionIterationDetector();
        assertDoesNotThrow(() -> {
            d.recordWrapperCreated(null, "x");
            d.recordIterationStarted(null, Thread.currentThread(), false);
            d.recordIterationStarted(Collections.synchronizedList(new ArrayList<>()), null, false);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());
        d.recordWrapperCreated(list, "list");
        d.recordIterationStarted(list, Thread.currentThread(), false);
        String s = d.analyze().toString();
        assertTrue(s.contains("SYNCHRONIZED COLLECTION"));
        assertTrue(s.contains("Fix"));
    }
}
