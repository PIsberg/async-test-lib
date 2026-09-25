package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

class ThreadLocalRandomMisuseDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new ThreadLocalRandomMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void useOnObtainingThreadIsNotFlagged() {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        d.recordObtain(rng, "local-rng", Thread.currentThread());
        for (int i = 0; i < 5; i++) {
            d.recordUse(rng, Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void cachedReferenceUsedFromAnotherThreadIsFlagged() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        // Simulate the bug: capture current() on this thread, share the reference.
        ThreadLocalRandom shared = ThreadLocalRandom.current();
        d.recordObtain(shared, "cached-rng", Thread.currentThread());
        Thread t = new Thread(() -> d.recordUse(shared, Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("cached-rng"));
        assertTrue(msg.contains("per-thread"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("ThreadLocalRandomMisuse", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("misusingThreadCount"));
    }

    @Test
    void currentCalledOnEveryThreadIsNotFlagged() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom[] seenByWorker = new ThreadLocalRandom[1];
        Runnable correctUse = () -> {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            d.recordObtain(rng, "per-thread-rng", Thread.currentThread());
            rng.nextInt();
            d.recordUse(rng, Thread.currentThread());
            seenByWorker[0] = rng;
        };
        correctUse.run();
        ThreadLocalRandom onThisThread = seenByWorker[0];
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(correctUse);
            t.start();
            t.join();
        }

        // The premise the old identity model missed: current() hands every thread the same object.
        assertSame(onThisThread, seenByWorker[0],
                "ThreadLocalRandom.current() returns one JVM-wide instance on this JDK");
        var report = d.analyze();
        assertFalse(report.hasIssues(),
                "every thread called current() itself, which is the documented idiom: " + report);
    }

    @Test
    void onlyTheThreadThatNeverCalledCurrentIsNamed() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom captured = ThreadLocalRandom.current();
        d.recordObtain(captured, "cached-rng", Thread.currentThread());
        Thread correct = new Thread(() -> {
            ThreadLocalRandom own = ThreadLocalRandom.current();
            d.recordObtain(own, "cached-rng", Thread.currentThread());
            d.recordUse(own, Thread.currentThread());
        }, "calls-current");
        Thread misuser = new Thread(() -> d.recordUse(captured, Thread.currentThread()),
                "uses-the-captured-reference");
        correct.start();
        correct.join();
        misuser.start();
        misuser.join();

        var report = d.analyze();
        assertTrue(report.hasIssues(), "a reference obtained on one thread and used on another");
        String text = report.toString();
        assertTrue(text.contains("uses-the-captured-reference"), text);
        assertFalse(text.contains("calls-current"), text);
        assertEquals(java.util.Optional.of(IssueSeverity.MEDIUM), IssueSeverity.markedIn(text),
                "the failOn gate reads severity from the text, which must match the Violation");
    }

    @Test
    void useWithoutObtainIsIgnored() throws Exception {
        var d = new ThreadLocalRandomMisuseDetector();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // never recorded as obtained
        Thread t = new Thread(() -> d.recordUse(rng, Thread.currentThread()));
        t.start();
        t.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new ThreadLocalRandomMisuseDetector();
        d.recordObtain(null, "label", Thread.currentThread());
        d.recordObtain(ThreadLocalRandom.current(), "label", null);
        d.recordUse(null, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }
}
