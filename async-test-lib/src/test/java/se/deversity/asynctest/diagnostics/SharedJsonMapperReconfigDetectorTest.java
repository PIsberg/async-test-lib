package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SharedJsonMapperReconfigDetectorTest {

    /** Stand-in for a mapper/serializer instance (e.g. an ObjectMapper or Gson). */
    private static final class FakeMapper {
    }

    @Test
    void cleanWhenNoActivity() {
        var d = new SharedJsonMapperReconfigDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void configureBeforeUseIsNotFlagged() {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)");
        d.recordConfigMutation(mapper, "setSerializationInclusion(NON_NULL)");
        d.recordUse(mapper);
        d.recordUse(mapper);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void mutationAfterUseBySameSingleThreadIsNotFlagged() {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)");
        d.recordUse(mapper);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void mutationFromNonUsingThreadAfterUseIsFlagged() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains(FakeMapper.class.getName()));
        assertTrue(msg.contains("registerModule(JavaTimeModule)"));
        assertTrue(msg.contains("own monitor count as guarded"));
        assertEquals(1, report.structuredViolations.size());
        var violation = report.structuredViolations.get(0);
        assertEquals("SharedJsonMapperReconfig", violation.detector());
        assertEquals(IssueSeverity.HIGH, violation.severity());
        assertEquals(FakeMapper.class.getName(), violation.attributes().get("className"));
    }

    @Test
    void mutationAfterConcurrentUseFromMultipleThreadsIsFlagged() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread user = new Thread(() -> d.recordUse(mapper));
        user.start();
        user.join();

        // Mutation now comes from one of the two using threads (the main thread),
        // but the instance has already been used from two distinct threads.
        d.recordConfigMutation(mapper, "setSerializationInclusion(NON_NULL)");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("2 thread(s)"));
        assertEquals(2, report.structuredViolations.get(0).attributes().get("usingThreadCount"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var a = new FakeMapper();
        var b = new FakeMapper();

        d.recordUse(a);
        d.recordUse(b);

        Thread t = new Thread(() -> d.recordConfigMutation(a, "configure(FAIL_ON_UNKNOWN, false)"));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("configure(FAIL_ON_UNKNOWN, false)"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new SharedJsonMapperReconfigDetector();
        d.recordUse(null);
        d.recordConfigMutation(null, "some-mutation");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullMutationDescriptionGetsFallbackLabel() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, null));
        mutator.start();
        mutator.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("configuration change"));
    }

    @Test
    void reportDescribesHazardAndFix() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        String describe = d.analyze().toString();
        assertTrue(describe.contains("SHARED JSON MAPPER RECONFIG DETECTED"));
        assertTrue(describe.contains("not safe once a serializer/mapper is visible to other threads"));
        assertTrue(describe.contains("ConcurrentModificationException"));
        assertTrue(describe.contains("Freeze mapper/builder configuration"));
        assertTrue(describe.contains("ObjectMapper.copy()"));
        assertTrue(describe.contains("Gson"));
    }

    @Test
    void analyzeIsIdempotent() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();

        d.recordUse(mapper);
        Thread mutator = new Thread(() -> d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"));
        mutator.start();
        mutator.join();

        var first = d.analyze();
        var second = d.analyze();

        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(
                first.structuredViolations.get(0).message(),
                second.structuredViolations.get(0).message());
    }

    // ---- Users are counted within one round (#748) ---------------------------------------------
    //
    // The runner finishes one round before it starts the next, and with virtual threads every body
    // execution is a fresh thread. A set of using threads kept across the run made any mapper used
    // in two rounds look "used by two or more threads", and made every later thread look like one
    // that "never used" it, so a reconfiguration with nothing in flight around it was reported.

    /**
     * Starts the next round of {@code scope} and runs each body on a fresh thread with the scope
     * bound, released together so their accesses overlap, as a run's workers are.
     */
    private static void round(SelfGuard.Scope scope, Runnable... bodies) throws InterruptedException {
        scope.markInvocationStart();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(bodies.length);
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                SelfGuard.Scope.bind(scope);
                try {
                    start.await();
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SelfGuard.Scope.unbind();
                }
            }, "round-worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    @Test
    void usersOfAnEarlierRoundDoNotMakeALaterSingleThreadReconfigurationARace() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();
        SelfGuard.Scope scope = new SelfGuard.Scope();

        // Round one: two threads use the mapper at once. Nobody reconfigures it.
        round(scope, () -> d.recordUse(mapper), () -> d.recordUse(mapper));
        // Round two: one thread uses it and then reconfigures it, alone.
        round(scope, () -> {
            d.recordUse(mapper);
            d.recordConfigMutation(mapper, "registerModule(JavaTimeModule)");
        });

        var report = d.analyze();
        assertFalse(report.hasIssues(),
                "round two's only user reconfigured the mapper; round one's users had finished: "
                        + report.violations);
    }

    @Test
    void aReconfigurationInARoundWithNoUseIsNotARace() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();
        SelfGuard.Scope scope = new SelfGuard.Scope();

        round(scope, () -> d.recordUse(mapper), () -> d.recordUse(mapper));
        // Round two: a fresh thread reconfigures it, and nothing uses it in that round.
        round(scope, () -> d.recordConfigMutation(mapper, "configure(FAIL_ON_UNKNOWN, false)"));

        var report = d.analyze();
        assertFalse(report.hasIssues(),
                "nothing used the mapper in the round it was reconfigured in: " + report.violations);
    }

    @Test
    void aReconfigurationDuringASharedRoundStillFiresWithThatRoundsUserCount() throws Exception {
        var d = new SharedJsonMapperReconfigDetector();
        var mapper = new FakeMapper();
        SelfGuard.Scope scope = new SelfGuard.Scope();
        var bothUsed = new java.util.concurrent.CyclicBarrier(2);

        // Round one: one thread uses it alone.
        round(scope, () -> d.recordUse(mapper));
        // Round two: two threads use it at once, and one of them then reconfigures it.
        round(scope, () -> {
            d.recordUse(mapper);
            await(bothUsed);
            d.recordConfigMutation(mapper, "setSerializationInclusion(NON_NULL)");
        }, () -> {
            d.recordUse(mapper);
            await(bothUsed);
        });

        var report = d.analyze();
        assertTrue(report.hasIssues(), "two threads used the mapper in the round it was reconfigured in");
        assertTrue(report.violations.get(0).contains("used by 2 thread(s)"),
                "the count is round two's users, not the three threads of the run: "
                        + report.violations.get(0));
        assertEquals(2, report.structuredViolations.get(0).attributes().get("usingThreadCount"));
    }

    private static void await(java.util.concurrent.CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
