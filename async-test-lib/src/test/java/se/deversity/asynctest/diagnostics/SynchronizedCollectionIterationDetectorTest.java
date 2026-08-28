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

    /**
     * Re-declaring a wrapper must not erase the unsafe iterations already seen on it.
     *
     * <p>This is the shape RegistrationIsIdempotentTest exists for, in a method that escaped it
     * on a naming technicality: the gate scans {@code registerX}, and this one is called
     * {@code recordWrapperCreated}. Its own javadoc calls it "Register a synchronized wrapper",
     * and the class usage example calls it from inside an {@code @AsyncTest} body - which runs
     * once per worker per invocation. Installing a fresh WrapperInfo each time meant the count
     * the report is built from was whatever accumulated since the last worker happened to
     * re-declare, so the detector under-reported or missed entirely, and how badly depended on
     * interleaving.
     */
    @Test
    void reDeclaringAWrapperKeepsEarlierUnsafeIterations() {
        var d = new SynchronizedCollectionIterationDetector();
        List<String> list = Collections.synchronizedList(new ArrayList<>());

        d.recordWrapperCreated(list, "list");
        d.recordIterationStarted(list, Thread.currentThread(), false);

        // A second worker entering the same body re-declares the same wrapper.
        d.recordWrapperCreated(list, "list");
        d.recordIterationStarted(list, Thread.currentThread(), false);

        assertTrue(d.analyze().toString().contains("2 unsafe iteration"),
                "Both unsafe iterations must survive the second declaration. Re-declaring is what "
                        + "an @AsyncTest body does once per worker, and a registration that "
                        + "installs fresh state discards everything learned before it. Report was: "
                        + d.analyze());
    }
}
