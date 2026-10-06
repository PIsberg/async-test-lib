package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AbstractInstanceDetector}: the per-instance scaffolding that most detectors used to copy,
 * written once (#918).
 *
 * <p>The copies were where the registration race and identity-keying defects kept recurring:
 * #564 fixed identity-hash collisions one map at a time, and a get/null-check/put on a record path
 * lost registrations under contention. These tests pin the shape once, and
 * {@link #assertNotRetained} holds every migrated detector, from its own test, to the retention
 * property the base class adds.
 */
class AbstractInstanceDetectorTest {

    /** The smallest subclass: one state per instance, carrying its label. */
    static final class Probe extends AbstractInstanceDetector<Probe.State> {
        static final class State {
            final String label;

            State(String label) {
                this.label = label;
            }
        }

        State record(Object instance, String name) {
            return stateFor(instance, name);
        }

        @Override
        State newState(Object instance, String label) {
            return new State(label);
        }
    }

    @Test
    void oneStatePerInstance_byIdentityNotEquality() {
        Probe probe = new Probe();
        String a = new String("same");
        String b = new String("same");

        assertSame(probe.record(a, "a"), probe.record(a, "a again"), "the same instance keeps its state");
        assertNotSame(probe.record(a, "a"), probe.record(b, "b"), "equal but distinct instances are two subjects");
        assertEquals("a", probe.record(a, "ignored").label, "the label is fixed when the instance is first seen");
        assertEquals(2, probe.states().size());
    }

    @Test
    void anUnnamedInstanceGetsAStableLabelOfItsKind() {
        Probe probe = new Probe();
        Object subject = new StringBuilder();

        String label = probe.record(subject, null).label;
        assertTrue(label.startsWith("StringBuilder"), label);
        assertEquals(label, probe.record(subject, null).label);
    }

    @Test
    void concurrentFirstSightingsRegisterOneState() throws Exception {
        Probe probe = new Probe();
        Object subject = new Object();
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        Set<Probe.State> seen = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 1_000; n++) {
                        seen.add(probe.record(subject, "shared"));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(1, seen.size(), "a get/null-check/put would hand racing threads different states");
    }

    @Test
    void theDetectorDoesNotKeepItsSubjectAlive_butKeepsWhatItLearned() throws Exception {
        Probe probe = new Probe();
        WeakReference<Object> subject = recordFromAnotherThread(instance -> probe.record(instance, "dropped"));

        awaitCollected(subject);
        assertEquals(1, probe.states().size(), "the state, and any finding in it, outlives the subject");
    }

    /**
     * Fails unless an object recorded through {@code record}, then dropped, can be collected: the
     * retention property every detector built on {@link AbstractInstanceDetector} has. Each
     * migrated detector's own test calls it.
     *
     * @param record records its argument with the detector under test
     */
    static void assertNotRetained(Consumer<Object> record) throws InterruptedException {
        awaitCollected(recordFromAnotherThread(record));
    }

    /**
     * Records a fresh object from a thread that then ends, so neither this thread's stack nor the
     * recording thread's lookup key holds it, and returns a weak reference to it.
     */
    private static WeakReference<Object> recordFromAnotherThread(Consumer<Object> record) throws InterruptedException {
        WeakReference<Object>[] ref = new WeakReference[1];
        Thread recorder = new Thread(() -> {
            Object subject = new Object();
            ref[0] = new WeakReference<>(subject);
            record.accept(subject);
        });
        recorder.start();
        recorder.join();
        return ref[0];
    }

    private static void awaitCollected(WeakReference<Object> ref) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ref.get(), "the detector still holds the subject strongly after 50 collections");
    }
}
