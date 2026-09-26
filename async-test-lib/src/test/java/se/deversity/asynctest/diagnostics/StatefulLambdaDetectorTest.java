package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StatefulLambdaDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new StatefulLambdaDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenExecutedOnSingleThread() {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable r = () -> counter[0]++;
        d.recordExecution(r, "r", Thread.currentThread());
        d.recordCapturedMutation(r, "counter", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenMultiThreadButNoMutation() throws Exception {
        var d = new StatefulLambdaDetector();
        Runnable r = () -> {};
        d.recordExecution(r, "r", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordExecution(r, "r", Thread.currentThread()));
        t2.start();
        t2.join();
        // multi-thread execution with no captured mutation — not an issue
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMutationFromMultipleThreads() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable r = () -> counter[0]++;
        d.recordExecution(r, "task", Thread.currentThread());
        d.recordCapturedMutation(r, "counter", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r, "task", Thread.currentThread());
            d.recordCapturedMutation(r, "counter", Thread.currentThread());
        });
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("task"));
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    @Test
    void lambdaBumpingACapturedLongAdderIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            hits.increment();
            d.recordCapturedMutation(task[0], "hits", hits, Thread.currentThread());
            total.addAndGet(2);
            d.recordCapturedMutation(task[0], "total", total, Thread.currentThread());
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(),
                "LongAdder and AtomicLong are the thread-safe state the report's own Fix section "
                        + "recommends; a lambda sharing them across threads is correct code: "
                        + d.analyze().violations);
    }

    @Test
    void lambdaMutatingItsCaptureUnderADeclaredLockIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Object lock = new Object();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                synchronized (lock) {
                    counter[0]++;
                    d.recordCapturedMutation(task[0], "counter", Thread.currentThread());
                }
            }
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(),
                "every mutation of the capture held one lock: " + d.analyze().violations);
    }

    @Test
    void lambdaMutatingACapturedArrayUnderItsOwnMonitorIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            synchronized (counter) {
                counter[0]++;
                d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
            }
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(), "synchronized (counter) guarded every mutation");
    }

    @Test
    void lambdaMutatingACapturedArrayWithNoLockIsReportedThroughTheStateOverload() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            counter[0]++;
            d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
        };
        onTwoThreads(task[0]);

        assertTrue(d.analyze().hasIssues(), "an int[] is not thread-safe state");
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    // #769: the lockset was kept per lambda, so two captures each guarded by its own lock
    // intersected to nothing and read as a race.
    @Test
    void twoCapturesEachGuardedByItsOwnLockAreNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] hits = {0};
        int[] misses = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            synchronized (hits) {
                hits[0]++;
                d.recordCapturedMutation(task[0], "hits", hits, Thread.currentThread());
            }
            synchronized (misses) {
                misses[0]++;
                d.recordCapturedMutation(task[0], "misses", misses, Thread.currentThread());
            }
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(),
                "each capture held its own lock at every mutation: " + d.analyze().violations);
    }

    @Test
    void oneGuardedAndOneUnguardedCaptureIsReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] hits = {0};
        int[] misses = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            synchronized (hits) {
                hits[0]++;
                d.recordCapturedMutation(task[0], "hits", hits, Thread.currentThread());
            }
            misses[0]++;
            d.recordCapturedMutation(task[0], "misses", misses, Thread.currentThread());
        };
        onTwoThreads(task[0]);

        assertTrue(d.analyze().hasIssues(), "misses was mutated with no lock held");
        assertTrue(d.analyze().violations.get(0).contains("misses"));
    }

    @Test
    void oneCaptureGuardedByADifferentLockOnEachThreadIsReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Object lockA = new Object();
        Object lockB = new Object();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            Object lock = "A".equals(Thread.currentThread().getName()) ? lockA : lockB;
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                synchronized (lock) {
                    counter[0]++;
                    d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
                }
            }
        };
        Thread a = new Thread(task[0], "A");
        Thread b = new Thread(task[0], "B");
        a.start();
        b.start();
        a.join();
        b.join();

        assertTrue(d.analyze().hasIssues(),
                "lock A on one thread and lock B on the other serialise nothing");
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    // #770: only a mutation was recorded, so one mutating thread beside threads that only read
    // the capture put a single thread in the round and read as unshared.
    @Test
    void oneWriterBesideReadersWithNoLockIsReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            if ("writer".equals(Thread.currentThread().getName())) {
                counter[0]++;
                d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
            } else {
                d.recordCapturedRead(task[0], counter, Thread.currentThread());
                assertTrue(counter[0] >= 0);
            }
        };
        onThreadsNamed(task[0], "writer", "reader-1", "reader-2");

        assertTrue(d.analyze().hasIssues(),
                "one thread wrote the capture while two others read it with no lock held");
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    @Test
    void oneWriterBesideReadersAllUnderTheCapturesMonitorIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            synchronized (counter) {
                if ("writer".equals(Thread.currentThread().getName())) {
                    counter[0]++;
                    d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
                } else {
                    d.recordCapturedRead(task[0], counter, Thread.currentThread());
                    assertTrue(counter[0] >= 0);
                }
            }
        };
        onThreadsNamed(task[0], "writer", "reader-1", "reader-2");

        assertFalse(d.analyze().hasIssues(),
                "every read and every write held synchronized (counter): " + d.analyze().violations);
    }

    @Test
    void aGuardedWriterBesideAnUnguardedReaderIsReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            if ("writer".equals(Thread.currentThread().getName())) {
                synchronized (counter) {
                    counter[0]++;
                    d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
                }
            } else {
                d.recordCapturedRead(task[0], counter, Thread.currentThread());
                assertTrue(counter[0] >= 0);
            }
        };
        onThreadsNamed(task[0], "writer", "reader");

        assertTrue(d.analyze().hasIssues(), "the read held no lock, so the writer's lock orders nothing");
    }

    @Test
    void oneThreadMutatingAndReadingItsCaptureIsNotReported() {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable task = () -> counter[0]++;
        d.recordExecution(task, "task", Thread.currentThread());
        counter[0]++;
        d.recordCapturedMutation(task, "counter", counter, Thread.currentThread());
        d.recordCapturedRead(task, counter, Thread.currentThread());

        assertFalse(d.analyze().hasIssues(), "one thread cannot race itself");
    }

    @Test
    void readersWithNoWriterAreNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            d.recordCapturedRead(task[0], counter, Thread.currentThread());
        };
        onThreadsNamed(task[0], "reader-1", "reader-2");

        assertFalse(d.analyze().hasIssues(), "concurrent reads of state nobody writes are no race");
    }

    private static void onThreadsNamed(Runnable body, String... names) throws InterruptedException {
        Thread[] threads = new Thread[names.length];
        for (int i = 0; i < names.length; i++) {
            threads[i] = new Thread(body, names[i]);
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
    }

    private static void onTwoThreads(Runnable body) throws InterruptedException {
        Thread a = new Thread(body);
        Thread b = new Thread(body);
        a.start();
        b.start();
        a.join();
        b.join();
    }

    @Test
    void testSeparateLambdaInstancesNoIssue() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] c1 = {0};
        int[] c2 = {0};
        Runnable r1 = () -> c1[0]++;
        Runnable r2 = () -> c2[0]++;
        d.recordExecution(r1, "r1", Thread.currentThread());
        d.recordCapturedMutation(r1, "c1", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r2, "r2", Thread.currentThread());
            d.recordCapturedMutation(r2, "c2", Thread.currentThread());
        });
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new StatefulLambdaDetector();
        Runnable r = () -> {};
        assertDoesNotThrow(() -> {
            d.recordExecution(null, "x", Thread.currentThread());
            d.recordExecution(r, "x", null);
            d.recordCapturedMutation(null, "x", Thread.currentThread());
            d.recordCapturedMutation(r, "x", null);
            d.recordCapturedRead(null, new int[1], Thread.currentThread());
            d.recordCapturedRead(r, new int[1], null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] c = {0};
        Runnable r = () -> c[0]++;
        d.recordExecution(r, "r", Thread.currentThread());
        d.recordCapturedMutation(r, "c", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r, "r", Thread.currentThread());
            d.recordCapturedMutation(r, "c", Thread.currentThread());
        });
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("STATEFUL LAMBDA"));
        assertTrue(s.contains("Fix"));
    }
}
