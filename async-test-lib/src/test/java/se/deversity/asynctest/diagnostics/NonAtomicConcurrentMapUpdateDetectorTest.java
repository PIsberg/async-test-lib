package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;

class NonAtomicConcurrentMapUpdateDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadCheckThenActIsNotFlagged() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        for (int i = 0; i < 5; i++) {
            d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sameMapAndKeyAcrossThreadsIsFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "user-1", "cache-fill", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("cache-fill"));
        assertTrue(msg.contains("user-1"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("putIfAbsent"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("NonAtomicConcurrentMapUpdate", report.structuredViolations.get(0).detector());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("threadCount"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void differentKeysOnSameMapAreTrackedSeparately() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, "a", "op", Thread.currentThread());
        d.recordCheckThenAct(map, "b", "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "a", "op", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        // only key "a" was touched by two threads
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("'a'"));
    }

    @Test
    void differentMapsAreTrackedSeparately() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> m1 = new ConcurrentHashMap<>();
        ConcurrentMap<String, String> m2 = new ConcurrentHashMap<>();
        d.recordCheckThenAct(m1, "k", "op", Thread.currentThread());
        d.recordCheckThenAct(m2, "k", "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(m1, "k", "op", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void checkThenActUnderTheMapsOwnMonitorIsNotFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        Runnable fillOnce = () -> {
            synchronized (map) {
                if (!map.containsKey("k")) {
                    map.put("k", "v");
                }
                d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
            }
        };
        onTwoThreads(fillOnce);

        assertFalse(d.analyze().hasIssues(),
                "every check-then-act on the key held one lock, so no second caller could land "
                        + "between the check and the put; reporting it as a lost update at HIGH "
                        + "is a false positive: " + d.analyze());
    }

    @Test
    void checkThenActUnderADeclaredPrivateLockIsNotFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        Object lock = new Object();
        Runnable fillOnce = () -> {
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                synchronized (lock) {
                    if (!map.containsKey("k")) {
                        map.put("k", "v");
                    }
                    d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
                }
            }
        };
        onTwoThreads(fillOnce);

        assertFalse(d.analyze().hasIssues(), "one declared lock covered both callers");
    }

    @Test
    void checkThenActUnderTwoDifferentLocksIsStillFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        Object first = new Object();
        Object second = new Object();
        Thread a = new Thread(() -> fillUnder(d, map, first));
        Thread b = new Thread(() -> fillUnder(d, map, second));
        a.start();
        a.join();
        b.start();
        b.join();

        assertTrue(d.analyze().hasIssues(),
                "each caller held a lock, but not the same one, so they never excluded each other");
    }

    @Test
    void distinctKeysWithTheSameStringFormAreTrackedSeparately() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<Object, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, 1, "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "1", "op", Thread.currentThread()));
        t.start();
        t.join();

        assertFalse(d.analyze().hasIssues(),
                "Integer 1 and String \"1\" are two keys of the map, so each was reached by one "
                        + "thread; merging them by their string form invented a shared site");
    }

    /**
     * The verdict-evidence-corpus argues this detector's VERDICT from its lockset and its
     * happens-before model (#818): a check-then-act ordered after another thread's cannot land
     * between that thread's check and its put. Both halves hand over through the same latch; only
     * the declared edge differs.
     */
    @Test
    void checkThenActOrderedAfterAnotherThreadsIsNotFlagged() throws Exception {
        assertFalse(handedOver(true).analyze().hasIssues(),
                "the second caller's check-then-act happens after the first one's, so no update "
                        + "can be lost between them");
    }

    @Test
    void theSameHandOverWithoutTheEdgeIsStillFlagged() throws Exception {
        assertTrue(handedOver(false).analyze().hasIssues(),
                "with no edge the model cannot order the two callers, so the lockset decides, "
                        + "and no lock covered either");
    }

    /**
     * #827: {@code if (!map.containsKey(k)) map.put(k, CONSTANT)} on two threads puts one
     * instance twice, so the map ends as it would under {@code putIfAbsent} and no caller holds a
     * value the map dropped.
     */
    @Test
    void callersThatAllPutTheSameInstanceAreNotFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Boolean> map = new ConcurrentHashMap<>();
        onTwoThreads(() -> {
            if (!map.containsKey("k")) {
                map.put("k", Boolean.TRUE);
            }
            d.recordCheckThenAct(map, "k", Boolean.TRUE, "mark-seen", Thread.currentThread());
        });

        assertFalse(d.analyze().hasIssues(),
                "both callers put the same instance, so whichever put landed last, nothing was "
                        + "lost: " + d.analyze());
    }

    @Test
    void callersPuttingDifferentValuesAreStillFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        onTwoThreads(() -> {
            String session = "session-" + Thread.currentThread().threadId();
            if (!map.containsKey("k")) {
                map.put("k", session);
            }
            d.recordCheckThenAct(map, "k", session, "get-or-create", Thread.currentThread());
        });

        var report = d.analyze();
        assertTrue(report.hasIssues(), "each caller put its own session, so one overwrote the other");
        assertEquals(1, report.structuredViolations.size());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    /**
     * The catalog's buggy example: two empty lists are equal, but each caller adds to the one it
     * put, and the list the map dropped takes that caller's element with it. Equality would have
     * excused the lost element, so values are compared by identity.
     */
    @Test
    void equalButDistinctValuesAreStillFlagged() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, List<String>> map = new ConcurrentHashMap<>();
        onTwoThreads(() -> {
            List<String> fresh = new ArrayList<>();
            if (!map.containsKey("k")) {
                map.put("k", fresh);
            }
            d.recordCheckThenAct(map, "k", fresh, "add-to-bucket", Thread.currentThread());
        });

        assertTrue(d.analyze().hasIssues(),
                "two new empty lists are equal and still two instances; the one the map dropped "
                        + "loses what its caller adds to it");
    }

    @Test
    void aCallerThatRecordedNoValueKeepsTheFinding() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Boolean> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, "k", Boolean.TRUE, "mark-seen", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, "k", "mark-seen", Thread.currentThread()));
        t.start();
        t.join();

        assertTrue(d.analyze().hasIssues(),
                "the second caller did not say what it put, so the pair cannot be shown to agree");
    }

    @Test
    void theOverloadWithoutAValueStillFlagsASameValuePair() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Boolean> map = new ConcurrentHashMap<>();
        onTwoThreads(() -> {
            if (!map.containsKey("k")) {
                map.put("k", Boolean.TRUE);
            }
            d.recordCheckThenAct(map, "k", "mark-seen", Thread.currentThread());
        });

        assertTrue(d.analyze().hasIssues(),
                "without the value the detector cannot tell a same-value pair from a lost update");
    }

    /**
     * #833: the same-instance excuse is judged per round, as the sharing verdict is. Rounds run
     * one after another, so callers of different rounds never overlapped: a round whose callers
     * all put that round's instance lost nothing, whatever another round put.
     */
    @Test
    void callersThatAgreeWithinEachRoundAreNotFlaggedForPuttingAnotherInstanceNextRound()
            throws Exception {
        var scope = new SelfGuard.Scope();
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();
        for (int round = 0; round < 3; round++) {
            Object token = new Object();
            onTwoThreadsIn(scope, () -> d.recordCheckThenAct(
                    map, "k", token, "install-token", Thread.currentThread()));
            scope.markInvocationStart();
        }

        assertFalse(d.analyze().hasIssues(),
                "every round's two callers put that round's one token, so no round lost a put; "
                        + "the tokens of different rounds never met: " + d.analyze());
    }

    @Test
    void aRoundWhoseCallersPutDifferentInstancesStillFires() throws Exception {
        var scope = new SelfGuard.Scope();
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();
        Object token = new Object();
        onTwoThreadsIn(scope, () -> d.recordCheckThenAct(
                map, "k", token, "install-token", Thread.currentThread()));
        scope.markInvocationStart();
        onTwoThreadsIn(scope, () -> d.recordCheckThenAct(
                map, "k", new Object(), "install-token", Thread.currentThread()));
        scope.markInvocationStart();
        onTwoThreadsIn(scope, () -> d.recordCheckThenAct(
                map, "k", token, "install-token", Thread.currentThread()));

        var report = d.analyze();
        assertTrue(report.hasIssues(),
                "the second round's callers each put their own instance, so one put was lost there");
        assertEquals(1, report.structuredViolations.size());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("threadCount"),
                "the report counts the threads of the round that lost a put");
    }

    /**
     * A round that disagreed but had one caller raced nobody, and a round that raced agreed, so
     * no round lost a put. Judged over the run, the two made a finding together.
     */
    @Test
    void aDisagreementWithNoRaceDoesNotConvictARoundThatRacedInAgreement() throws Exception {
        var scope = new SelfGuard.Scope();
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, Object> map = new ConcurrentHashMap<>();
        Object token = new Object();
        onTwoThreadsIn(scope, () -> d.recordCheckThenAct(
                map, "k", token, "install-token", Thread.currentThread()));
        scope.markInvocationStart();
        inOneThreadIn(scope, () -> {
            d.recordCheckThenAct(map, "k", new Object(), "install-token", Thread.currentThread());
            d.recordCheckThenAct(map, "k", "install-token", Thread.currentThread());
        });

        assertFalse(d.analyze().hasIssues(),
                "the round with two callers agreed, and the round that did not had one caller: "
                        + d.analyze());
    }

    private static NonAtomicConcurrentMapUpdateDetector handedOver(boolean declared) throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        var latch = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> {
            d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
            map.putIfAbsent("k", "v");
            if (declared) {
                HappensBefore.release(latch);
            }
            latch.countDown();
        });
        Thread second = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (declared) {
                HappensBefore.acquire(latch);
            }
            d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
        });
        second.start();
        first.start();
        first.join();
        second.join();
        return d;
    }

    private static void fillUnder(NonAtomicConcurrentMapUpdateDetector d,
                                  ConcurrentMap<String, String> map, Object lock) {
        try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
            synchronized (lock) {
                map.putIfAbsent("k", "v");
                d.recordCheckThenAct(map, "k", "lazy-fill", Thread.currentThread());
            }
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

    private static void onTwoThreadsIn(SelfGuard.Scope scope, Runnable body) throws InterruptedException {
        onTwoThreads(boundTo(scope, body));
    }

    private static void inOneThreadIn(SelfGuard.Scope scope, Runnable body) throws InterruptedException {
        Thread t = new Thread(boundTo(scope, body));
        t.start();
        t.join();
    }

    /** Runs {@code body} with {@code scope} bound, as a runner worker has its run's round clock. */
    private static Runnable boundTo(SelfGuard.Scope scope, Runnable body) {
        return () -> {
            SelfGuard.Scope.bind(scope);
            try {
                body.run();
            } finally {
                SelfGuard.Scope.unbind();
            }
        };
    }

    @Test
    void nullsAreIgnored() {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        d.recordCheckThenAct(null, "k", "op", Thread.currentThread());
        d.recordCheckThenAct(new ConcurrentHashMap<>(), "k", "op", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullKeyIsHandled() throws Exception {
        var d = new NonAtomicConcurrentMapUpdateDetector();
        ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
        d.recordCheckThenAct(map, null, "op", Thread.currentThread());
        Thread t = new Thread(() -> d.recordCheckThenAct(map, null, "op", Thread.currentThread()));
        t.start();
        t.join();
        assertTrue(d.analyze().hasIssues());
    }
}
