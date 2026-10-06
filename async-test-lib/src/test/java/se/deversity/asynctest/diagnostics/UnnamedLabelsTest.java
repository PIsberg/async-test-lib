package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UnnamedLabelsTest {

    @Test
    void numbersEachKindFromOne() {
        UnnamedLabels labels = new UnnamedLabels();

        assertEquals(List.of("queue@1", "queue@2", "timer@1", "queue@3"),
                List.of(labels.next("queue"), labels.next("queue"), labels.next("timer"), labels.next("queue")));
    }

    @Test
    void twoDetectorsCountApartSoARunsLabelsDoNotDependOnWhatRanBefore() {
        UnnamedLabels earlier = new UnnamedLabels();
        earlier.next("queue");
        earlier.next("queue");

        assertEquals("queue@1", new UnnamedLabels().next("queue"),
                "a fresh detector, which is a fresh run, starts at 1 (#860 item 2)");
    }

    @Test
    void oneObjectKeepsItsLabelAndTwoObjectsSharingAnIdentityHashGetTwo() {
        UnnamedLabels labels = new UnnamedLabels();
        List<Object> pair = IdentityCollisions.pair(Object::new);

        String first = labels.of(pair.get(0), "lock");
        assertEquals(first, labels.of(pair.get(0), "lock"), "the same object is named the same every time");
        assertNotEquals(first, labels.of(pair.get(1), "lock"),
                "two objects are two labels even when their identity hashes collide");
    }

    @Test
    void concurrentFirstSightsHandOutDistinctLabels() throws Exception {
        UnnamedLabels labels = new UnnamedLabels();
        int threads = 8;
        int perThread = 200;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(labels.of(new Object(), "item"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, seen.size(), "every object got its own number");
    }

    /**
     * A label does not keep its object alive (#929). The label map held a strong key per unnamed
     * object, so every detector using it kept every object a test recorded without a name for the
     * whole run, even where the detector's own state was weak.
     */
    @Test
    void aLabelledObjectCanStillBeCollected() throws Exception {
        UnnamedLabels labels = new UnnamedLabels();
        AbstractInstanceDetectorTest.assertNotRetained(subject -> labels.of(subject, "item"));
        AbstractInstanceDetectorTest.assertNotRetained(subject -> labels.of(new IdentityKey(subject), "item"));
    }

    @Test
    void aLiveObjectKeepsItsLabelThroughEitherOverload() {
        UnnamedLabels labels = new UnnamedLabels();
        Object subject = new Object();
        String first = labels.of(subject, "item");
        assertEquals(first, labels.of(new IdentityKey(subject), "item"));
        assertEquals(first, labels.of(subject, "item"));
    }
}
