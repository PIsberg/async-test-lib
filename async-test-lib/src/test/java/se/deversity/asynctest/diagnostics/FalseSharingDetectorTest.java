package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FalseSharingDetectorTest {

    @Test
    void noRecordingsReturnNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
    }

    @Test
    void singleThreadSingleFieldNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "counter", int.class);
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
    }

    @Test
    void multipleThreadsAccessingSameFieldRecorded() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        Thread t1 = new Thread(() -> detector.recordFieldAccess(obj, "value", long.class));
        Thread t2 = new Thread(() -> detector.recordFieldAccess(obj, "value", long.class));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        // Should complete without throwing; report state is implementation-dependent
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertNotNull(report);
    }

    @Test
    void reportHasIssuesFalseWhenEmpty() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        assertTrue(report.falseSharedPairs.isEmpty());
        assertTrue(report.highContentionFields.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        FalseSharingDetector detector = new FalseSharingDetector();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        String text = report.toString();
        assertNotNull(text);
        assertTrue(text.contains("No false sharing detected.") || !report.hasIssues());
    }

    @Test
    void resetClearsState() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "field", int.class);
        detector.reset();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        assertTrue(report.falseSharedPairs.isEmpty());
    }

    @Test
    void disabledDetectorSkipsRecording() {
        FalseSharingDetector detector = new FalseSharingDetector();
        detector.disable();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "x", double.class);
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues());
        detector.enable();
    }

    @Test
    void nullObjectHandledGracefully() {
        FalseSharingDetector detector = new FalseSharingDetector();
        assertDoesNotThrow(() -> detector.recordFieldAccess(null, "field", int.class));
    }

    @Test
    void analyze_delegatesToAnalyzeFalseSharing() {
        FalseSharingDetector detector = new FalseSharingDetector();
        Object obj = new Object();
        detector.recordFieldAccess(obj, "field", int.class);

        FalseSharingDetector.FalseSharingReport viaAnalyze = detector.analyze();
        FalseSharingDetector.FalseSharingReport viaAnalyzeFalseSharing = detector.analyzeFalseSharing();

        assertEquals(viaAnalyzeFalseSharing.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeFalseSharing.toString(), viaAnalyze.toString());
    }

    /** Two int fields of the same class, each touched by two distinct threads so the
     * thread sets are unequal and both fields pass the multi-thread guard regardless of
     * map iteration order: under the detector's declaration-order offset model this is
     * an adjacent-fields, unequal-thread-sets pair, i.e. exactly what it reports as
     * false sharing. */
    private static FalseSharingDetector detectorWithReportablePattern() throws InterruptedException {
        FalseSharingDetector detector = new FalseSharingDetector();
        TwoCounters obj = new TwoCounters();
        Thread t1 = new Thread(() -> detector.recordFieldAccess(obj, "a", int.class));
        Thread t2 = new Thread(() -> detector.recordFieldAccess(obj, "a", int.class));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        Thread t3 = new Thread(() -> detector.recordFieldAccess(obj, "b", int.class));
        Thread t4 = new Thread(() -> detector.recordFieldAccess(obj, "b", int.class));
        t3.start();
        t4.start();
        t3.join();
        t4.join();
        return detector;
    }

    @Test
    void findingsAreGatedBehindTheExperimentalFlag() throws InterruptedException {
        FalseSharingDetector detector = detectorWithReportablePattern();
        FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
        assertFalse(report.hasIssues(),
                "Without -Dasync-test.experimental.false-sharing=true the detector must "
                        + "report nothing: its offsets are declaration-order arithmetic that "
                        + "ignores JVM field reordering and compressed oops, its keying is "
                        + "per-class rather than per-object, and its pair predicate requires "
                        + "unequal thread sets, which excludes the textbook case. Findings "
                        + "uncorrelated with the named phenomenon must be opt-in.");
    }

    @Test
    void experimentalFlagRestoresTheOldBehavior() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = detectorWithReportablePattern();
            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.hasIssues(),
                    "With the experimental property set, the pre-gate analysis must run "
                            + "unchanged so existing users can opt back in");
            assertFalse(report.falseSharedPairs.isEmpty());
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    // ---- Thread sets are taken within one round (#765) -----------------------------------------
    //
    // The runner finishes one round before it starts the next, and with virtual threads every body
    // execution is a fresh thread. Thread sets kept across the run make accesses from different
    // rounds, which never overlapped, read as concurrent.

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

    private static Runnable times(int n, Runnable access) {
        return () -> {
            for (int i = 0; i < n; i++) {
                access.run();
            }
        };
    }

    @Test
    void adjacentFieldsTouchedInDifferentRoundsAreNotFalseSharing() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();

            // Round one: two threads on a. Round two: two other threads on b. No round touched both.
            round(scope, () -> detector.recordFieldAccess(obj, "a", int.class),
                    () -> detector.recordFieldAccess(obj, "a", int.class));
            round(scope, () -> detector.recordFieldAccess(obj, "b", int.class),
                    () -> detector.recordFieldAccess(obj, "b", int.class));

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.falseSharedPairs.isEmpty(),
                    "a and b were never accessed in the same round, so no two threads contended "
                            + "for their cache line: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void adjacentFieldsTouchedByDifferentThreadsInOneRoundStillFire() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();

            round(scope, () -> detector.recordFieldAccess(obj, "a", int.class),
                    () -> detector.recordFieldAccess(obj, "a", int.class),
                    () -> detector.recordFieldAccess(obj, "b", int.class),
                    () -> detector.recordFieldAccess(obj, "b", int.class));
            round(scope, () -> detector.recordFieldAccess(obj, "a", int.class));

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.falseSharedPairs.size(),
                    "round one had two threads on a and two others on b: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void oneThreadPerRoundIsNotHighContention() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();

            round(scope, times(60, () -> detector.recordFieldAccess(obj, "a", int.class)));
            round(scope, times(60, () -> detector.recordFieldAccess(obj, "a", int.class)));

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.highContentionFields.isEmpty(),
                    "each round had one thread on a; two threads in different rounds never "
                            + "contended: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void twoThreadsInOneRoundAreStillHighContention() throws InterruptedException {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();

            round(scope, times(60, () -> detector.recordFieldAccess(obj, "a", int.class)),
                    times(60, () -> detector.recordFieldAccess(obj, "a", int.class)));

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.highContentionFields.size(),
                    "two threads hammered a in the same round: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    // ---- The access threshold is counted in contended rounds only (#794) -------------------------
    //
    // A platform thread outlives its round, so a count kept over the run adds up accesses from
    // rounds in which the thread was alone on the field and nothing contended.

    /**
     * Starts the next round of {@code scope} and runs each body on its long-lived platform thread,
     * one single-thread executor per worker, released together so their accesses overlap. The same
     * worker thread across rounds is what a platform-thread run gives each worker slot.
     */
    private static void round(SelfGuard.Scope scope, java.util.concurrent.ExecutorService[] workers,
                              Runnable... bodies) throws Exception {
        scope.markInvocationStart();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(bodies.length);
        java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            done.add(workers[i].submit(() -> {
                SelfGuard.Scope.bind(scope);
                try {
                    start.await();
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SelfGuard.Scope.unbind();
                }
            }));
        }
        for (java.util.concurrent.Future<?> future : done) {
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static java.util.concurrent.ExecutorService[] platformWorkers(int n) {
        java.util.concurrent.ExecutorService[] workers = new java.util.concurrent.ExecutorService[n];
        for (int i = 0; i < n; i++) {
            workers[i] = java.util.concurrent.Executors.newSingleThreadExecutor();
        }
        return workers;
    }

    private static void shutdown(java.util.concurrent.ExecutorService[] workers) {
        for (java.util.concurrent.ExecutorService worker : workers) {
            worker.shutdownNow();
        }
    }

    @Test
    void aThreadThatRacedOnceThenWorkedAloneIsNotHighContention() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.ExecutorService[] workers = platformWorkers(2);
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // Round one: both workers touch a once. Rounds two to twenty: the first worker, the
            // same platform thread, touches a ten times a round with nobody else on it.
            round(scope, workers, touchA, touchA);
            for (int r = 2; r <= 20; r++) {
                round(scope, workers, times(10, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.highContentionFields.isEmpty(),
                    "the only round with two threads on a had one access from each; the 190 "
                            + "later accesses were made alone and contended with nothing: " + report);
        } finally {
            shutdown(workers);
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void contentionAndThresholdInOneRoundFireDespiteLaterSoloRounds() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.ExecutorService[] workers = platformWorkers(2);
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // Round one: both workers hammer a, 60 accesses each. Later solo rounds must not
            // dilute or cancel what round one already showed.
            round(scope, workers, times(60, touchA), times(60, touchA));
            for (int r = 2; r <= 5; r++) {
                round(scope, workers, times(10, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.highContentionFields.size(),
                    "round one had two threads on a and each crossed the threshold in it: " + report);
        } finally {
            shutdown(workers);
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void steadyContentionEveryRoundOnPlatformThreadsKeepsItsVerdict() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.ExecutorService[] workers = platformWorkers(2);
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // Ten rounds, both workers on a every round, 30 accesses each: no single round crosses
            // the threshold, but every access counted was made while the other thread was on a.
            for (int r = 1; r <= 10; r++) {
                round(scope, workers, times(30, touchA), times(30, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.highContentionFields.size(),
                    "both threads were on a in every round, so all 300 accesses of each were "
                            + "contended and the run-wide count stands: " + report);
        } finally {
            shutdown(workers);
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    // ---- The threshold counts the field's contended traffic, not one thread's (#811) --------------
    //
    // False sharing is cache-line traffic between cores, and what drives it is how often the line is
    // touched while more than one core holds it. Which Java thread made an access says nothing about
    // that: a platform worker migrates between cores, and a virtual thread lives one body execution.
    // A per-thread share summed over the run is reachable by a pooled platform worker across many
    // rounds, but a virtual thread needs it inside one body, so the same workload used to report on
    // platform threads and stay silent on virtual threads.

    @Test
    void steadyContentionOnFreshThreadsEveryRoundHasThePlatformVerdict() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // steadyContentionEveryRoundOnPlatformThreadsKeepsItsVerdict's workload, with two fresh
            // threads every round, as useVirtualThreads = true runs it.
            for (int r = 1; r <= 10; r++) {
                round(scope, times(30, touchA), times(30, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.highContentionFields.size(),
                    "two threads were on a in every round, 600 contended accesses in all, the same "
                            + "traffic the platform-thread run reports; that each thread lived one "
                            + "round and made 30 of them does not change the field's traffic: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void aRaceOnceThenSoloRunOnFreshThreadsStaysSilent() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // aThreadThatRacedOnceThenWorkedAloneIsNotHighContention's workload on fresh threads.
            round(scope, touchA, touchA);
            for (int r = 2; r <= 20; r++) {
                round(scope, times(10, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.highContentionFields.isEmpty(),
                    "two contended accesses and 190 solo ones: " + report);
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void manyWorkersEachTouchingLightlyAreStillTheFieldsTraffic() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.ExecutorService[] workers = platformWorkers(4);
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // Four platform workers, three accesses each, every round for nine rounds: 108 contended
            // accesses on a, 27 from any one worker. No thread reaches a per-thread share of more
            // than 50, yet the line carried the traffic.
            for (int r = 1; r <= 9; r++) {
                round(scope, workers, times(3, touchA), times(3, touchA), times(3, touchA),
                        times(3, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertEquals(1, report.highContentionFields.size(),
                    "108 accesses to a were made in rounds four threads shared: " + report);
        } finally {
            shutdown(workers);
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    @Test
    void contendedTrafficBelowTheThresholdStaysSilent() throws Exception {
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.ExecutorService[] workers = platformWorkers(2);
        try {
            FalseSharingDetector detector = new FalseSharingDetector();
            TwoCounters obj = new TwoCounters();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable touchA = () -> detector.recordFieldAccess(obj, "a", int.class);

            // 98 contended accesses (seven rounds of two workers with seven each), then solo rounds
            // that take the run's history past the threshold without contending with anything.
            for (int r = 1; r <= 7; r++) {
                round(scope, workers, times(7, touchA), times(7, touchA));
            }
            for (int r = 8; r <= 12; r++) {
                round(scope, workers, times(10, touchA));
            }

            FalseSharingDetector.FalseSharingReport report = detector.analyzeFalseSharing();
            assertTrue(report.highContentionFields.isEmpty(),
                    "98 contended accesses is under the threshold of 100: " + report);
        } finally {
            shutdown(workers);
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
        }
    }

    // The same workload through the runner, once per thread model: the runner decides whether a
    // worker keeps its thread across rounds, so this is the pin that both models agree.

    /** A shared field each body execution touches {@code accessesPerBody} times. */
    static final class HotField {
        int a;
        final int accessesPerBody;

        HotField(int accessesPerBody) {
            this.accessesPerBody = accessesPerBody;
        }

        void hammer() {
            FalseSharingDetector detector = se.deversity.asynctest.AsyncTestContext.falseSharingDetector();
            for (int i = 0; i < accessesPerBody; i++) {
                detector.recordFieldAccess(this, "a", int.class);
            }
        }
    }

    /** {@return the FalseSharingDetector report the runner handed its listeners, or null if none} */
    private static String runnerReport(boolean virtualThreads, int threads, int invocations,
                                       int accessesPerBody) throws Throwable {
        String previousLicense = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "true");
        System.setProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY, "true");
        java.util.concurrent.atomic.AtomicReference<String> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (se.deversity.asynctest.AsyncTestListenerRegistry.Registration ignored =
                     se.deversity.asynctest.AsyncTestListenerRegistry.registerScoped(
                             new se.deversity.asynctest.AsyncTestListener() {
                                 @Override
                                 public void onDetectorReport(String detectorName, String report) {
                                     if (detectorName.contains("FalseSharing")) {
                                         seen.set(report);
                                     }
                                 }
                             })) {
            se.deversity.asynctest.AsyncTestConfig config = se.deversity.asynctest.AsyncTestConfig.builder()
                    .threads(threads).invocations(invocations).useVirtualThreads(virtualThreads)
                    .timeoutMs(10_000).detectAll(false).detectDeadlocks(false)
                    .detectFalseSharing(true)
                    .build();
            HotField target = new HotField(accessesPerBody);
            java.lang.reflect.Method body = HotField.class.getDeclaredMethod("hammer");
            se.deversity.asynctest.runner.ConcurrencyRunner.execute(
                    new org.junit.jupiter.api.extension.ReflectiveInvocationContext<java.lang.reflect.Method>() {
                        @Override public Class<?> getTargetClass() { return HotField.class; }
                        @Override public java.lang.reflect.Method getExecutable() { return body; }
                        @Override public java.util.List<Object> getArguments() { return java.util.List.of(); }
                        @Override public java.util.Optional<Object> getTarget() { return java.util.Optional.of(target); }
                    }, config);
            return seen.get();
        } finally {
            System.clearProperty(FalseSharingDetector.EXPERIMENTAL_PROPERTY);
            if (previousLicense == null) {
                System.clearProperty("license.mock.mode");
            } else {
                System.setProperty("license.mock.mode", previousLicense);
            }
        }
    }

    @Test
    void theRunnerReportsSteadyContentionOnBothThreadModels() throws Throwable {
        // Two workers, ten rounds, 30 accesses to a per body: 600 contended accesses on a. A pooled
        // platform worker makes 300 of them, a virtual thread 30.
        String platform = runnerReport(false, 2, 10, 30);
        String virtual = runnerReport(true, 2, 10, 30);

        assertAll(
                () -> assertTrue(platform != null && platform.contains("High-contention fields"),
                        "platform threads: two workers on a in every round. Report: " + platform),
                () -> assertTrue(virtual != null && virtual.contains("High-contention fields"),
                        "virtual threads: the same workload put the same contended traffic on a, "
                                + "so it must get the platform verdict. Report: " + virtual));
    }

    @Test
    void theRunnerStaysSilentForOneWorkerOnBothThreadModels() throws Throwable {
        // One worker, ten rounds, 30 accesses per body: 300 accesses on a, none of them contended.
        String platform = runnerReport(false, 1, 10, 30);
        String virtual = runnerReport(true, 1, 10, 30);

        assertAll(
                () -> assertFalse(platform != null && platform.contains("High-contention fields"),
                        "platform threads: one thread per round never contended. Report: " + platform),
                () -> assertFalse(virtual != null && virtual.contains("High-contention fields"),
                        "virtual threads: one thread per round never contended. Report: " + virtual));
    }

    static class TwoCounters {
        int a;
        int b;
    }
}
