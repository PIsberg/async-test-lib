package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * A thread the test body starts and that dies of an uncaught exception (#1253), without the
 * agent: the runner attributes the thread to the run because a worker constructed it.
 *
 * <p>An {@link AssertionError} there is the test's own assertion on the wrong thread, so it fails
 * the test; any other throwable is an {@code UncaughtExceptionHandlerDetector} finding. A thread
 * that ends normally, or whose own handler takes the exception, changes nothing, and the JVM-wide
 * default handler is the one the run found, before and after.
 *
 * <p>Nested classes match {@code *$*} and are excluded from direct surefire discovery; they run
 * only through the kit.
 */
@E2E
class BodyStartedThreadFailureTest {

    private static final String DETECTOR = "UncaughtExceptionHandlerDetector";

    @Test
    void anAssertionOnAThreadTheBodyStartedFailsTheTest() {
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();

        Events events = run(AssertionOnStartedThread.class);

        assertEquals(1, events.failed().count(),
                "an AssertionError thrown on a thread the body started and joined must fail the test");
        assertTrue(failureText(events).contains(AssertionOnStartedThread.MESSAGE),
                "the failure must carry the assertion that failed; was: " + failureText(events));
        assertSame(before, Thread.getDefaultUncaughtExceptionHandler(),
                "the run must leave the JVM-wide default handler as it found it");
    }

    @Test
    void anAssertionFailsTheTestWhateverTheDetectorSelection() {
        Events events = run(AssertionWithNoDetectors.class);

        assertEquals(1, events.failed().count(),
                "the assertion is the test's own failure, not a finding: preset NONE must not hide it");
    }

    @Test
    void anotherThrowableIsAFindingNotAFailure() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = run(RuntimeExceptionOnStartedThread.class);

            assertEquals(0, events.failed().count(), "failOn = NONE: a finding is reported, not thrown");
            findings.assertReported(DETECTOR, se.deversity.asynctest.diagnostics.IssueSeverity.HIGH);
            assertTrue(findings.violationsFrom(DETECTOR).toString().contains("IllegalStateException"),
                    "the finding must name what the thread threw; was: " + findings.violationsFrom(DETECTOR));
        }
    }

    @Test
    void aThreadThatEndsNormallyChangesNothing() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = run(StartedThreadEndsNormally.class);

            assertEquals(0, events.failed().count(), "a child that returns must not fail the test");
            findings.assertNotReported(DETECTOR);
        }
    }

    @Test
    void aThreadWithItsOwnHandlerIsLeftToIt() {
        OwnHandlerSwallows.HANDLED.clear();
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = run(OwnHandlerSwallows.class);

            assertEquals(0, events.failed().count(),
                    "the thread's own handler took the exception: that is the test's decision");
            findings.assertNotReported(DETECTOR);
            assertEquals(2 * 2, OwnHandlerSwallows.HANDLED.size(),
                    "the thread's own handler must still receive every exception");
        }
    }

    @Test
    void anExceptionTheBodyRethrowsAfterJoinFailsOnce() {
        Events events = run(RethrownAfterJoin.class);

        assertEquals(1, events.failed().count(), "the rethrown error must fail the test");
        assertTrue(failureText(events).contains(RethrownAfterJoin.MESSAGE),
                "the failure must be the rethrown error; was: " + failureText(events));
    }

    @Test
    void aDefaultHandlerSetBeforeTheRunStillReceivesEveryDeathAndCoversTheThread() {
        List<Throwable> received = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler mine = (t, e) -> received.add(e);
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(mine);
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events covered = run(RuntimeExceptionOnStartedThread.class);
            assertEquals(0, covered.failed().count(), "failOn = NONE");
            findings.assertNotReported(DETECTOR); // #758: a JVM-wide default handled it
            assertEquals(2 * 2, received.size(), "the default handler the run found must still be called");

            received.clear();
            Events failed = run(AssertionOnStartedThread.class);
            assertEquals(1, failed.failed().count(),
                    "a default handler that logs does not make a failed assertion pass");
            assertFalse(received.isEmpty(), "and it still receives the assertion");

            assertSame(mine, Thread.getDefaultUncaughtExceptionHandler(),
                    "the run must restore the default handler it found");
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    @Test
    void aPoolTheBodyBuiltReportsATaskThatKilledItsThreadOnce() {
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = run(PoolTaskKillsItsThread.class);

            assertEquals(0, events.failed().count(), "failOn = NONE");
            String report = findings.violationsFrom(DETECTOR).toString();
            assertEquals(1, report.split("Thread 'body-pool-thread' threw", -1).length - 1,
                    "one dead pool thread, reported once; was: " + report);
        }
    }

    @Test
    void aThreadTheBodyDidNotJoinFailsTheRoundItDiesIn() {
        Events events = run(UnjoinedDiesInALaterRound.class);

        assertEquals(1, events.failed().count(),
                "a thread started in round 0 and dying in round 1 must still fail the run");
        assertTrue(failureText(events).contains(UnjoinedDiesInALaterRound.MESSAGE),
                "the failure must carry its assertion; was: " + failureText(events));
    }

    @Test
    void aThreadThatDiesAfterTheLastRoundStillFailsTheRun() {
        Events events = run(UnjoinedDiesInTheLastAfterHook.class);

        assertEquals(1, events.failed().count(),
                "a death after the last round's own check, before analysis, must fail the run");
        assertTrue(failureText(events).contains(UnjoinedDiesInTheLastAfterHook.MESSAGE),
                "the failure must carry its assertion; was: " + failureText(events));
    }

    @Test
    void aThreadThatOutlivesItsRunNeverFailsTheNextOneAndIsStillHandedOn() {
        List<Throwable> received = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> received.add(e));
        try (AsyncFindings findings = AsyncFindings.collect()) {
            Events events = run(OutlivesItsRun.class);

            assertEquals(0, events.failed().count(),
                    "the thread belongs to the first run, which was over: blaming the second run "
                            + "for it would fail a test that did nothing wrong");
            assertEquals(2, events.succeeded().count(), "both runs must pass");
            findings.assertNotReported(DETECTOR);
            assertTrue(received.stream().anyMatch(e -> OutlivesItsRun.MESSAGE.equals(e.getMessage())),
                    "the death must still reach the default handler, never be swallowed; got " + received);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
    }

    // -----------------------------------------------------------------------

    private static Events run(Class<?> testClass) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(testClass))
                .execute()
                .testEvents();
    }

    private static String failureText(Events events) {
        StringBuilder text = new StringBuilder();
        events.failed().stream()
                .map(e -> e.getPayload(TestExecutionResult.class).flatMap(TestExecutionResult::getThrowable))
                .forEach(t -> t.ifPresent(thrown -> {
                    for (Throwable c = thrown; c != null; c = c.getCause()) {
                        text.append(c).append('\n');
                    }
                }));
        return text.toString();
    }

    private static void startAndJoin(Runnable task) throws InterruptedException {
        Thread child = new Thread(task, "body-started-child");
        child.start();
        child.join();
    }

    // -----------------------------------------------------------------------
    // the subjects
    // -----------------------------------------------------------------------

    public static class AssertionOnStartedThread {
        static final String MESSAGE = "failed on a thread the body started";

        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void body() throws InterruptedException {
            startAndJoin(() -> {
                throw new AssertionError(MESSAGE);
            });
        }
    }

    public static class AssertionWithNoDetectors {
        @AsyncTest(threads = 2, invocations = 2, preset = Preset.NONE)
        void body() throws InterruptedException {
            startAndJoin(() -> {
                throw new AssertionError("no detector selected");
            });
        }
    }

    public static class RuntimeExceptionOnStartedThread {
        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void body() throws InterruptedException {
            startAndJoin(() -> {
                throw new IllegalStateException("not an assertion");
            });
        }
    }

    public static class StartedThreadEndsNormally {
        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE, failOn = FailOn.LOW)
        void body() throws InterruptedException {
            startAndJoin(() -> { });
        }
    }

    public static class OwnHandlerSwallows {
        static final List<Throwable> HANDLED = new CopyOnWriteArrayList<>();

        @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE, failOn = FailOn.LOW)
        void body() throws InterruptedException {
            Thread child = new Thread(() -> {
                throw new AssertionError("taken by the thread's own handler");
            });
            child.setUncaughtExceptionHandler((t, e) -> HANDLED.add(e));
            child.start();
            child.join();
        }
    }

    public static class RethrownAfterJoin {
        static final String MESSAGE = "rethrown by the body after join";

        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void body() throws Throwable {
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread child = new Thread(() -> {
                throw new AssertionError(MESSAGE);
            });
            child.setUncaughtExceptionHandler((t, e) -> caught.set(e));
            child.start();
            child.join();
            Throwable thrown = caught.get();
            if (thrown != null) {
                throw thrown;
            }
        }
    }

    public static class PoolTaskKillsItsThread {
        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void body() throws InterruptedException {
            List<Thread> made = new CopyOnWriteArrayList<>();
            ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "body-pool-thread");
                made.add(thread);
                return thread;
            });
            try {
                pool.execute(() -> {
                    throw new IllegalStateException("killed its pool thread");
                });
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
            for (Thread thread : made) {
                thread.join(); // awaitTermination returns before the dying thread has run its handler
            }
        }
    }

    public static class UnjoinedDiesInALaterRound {
        static final String MESSAGE = "died a round after it was started";
        static final java.util.concurrent.atomic.AtomicReference<Thread> STARTED = new AtomicReference<>();
        static final java.util.concurrent.CountDownLatch RELEASE = new java.util.concurrent.CountDownLatch(1);

        @AsyncTest(threads = 1, invocations = 2, preset = Preset.NONE)
        void body() throws InterruptedException {
            Thread started = STARTED.get();
            if (started == null) {
                Thread child = new Thread(() -> {
                    awaitQuietly(RELEASE);
                    throw new AssertionError(MESSAGE);
                }, "unjoined-child");
                STARTED.set(child);
                child.start();
            } else {
                RELEASE.countDown();
                started.join();
            }
        }
    }

    public static class UnjoinedDiesInTheLastAfterHook {
        static final String MESSAGE = "died after the last round's check";
        static final AtomicReference<Thread> STARTED = new AtomicReference<>();
        static final java.util.concurrent.CountDownLatch RELEASE = new java.util.concurrent.CountDownLatch(1);

        @AsyncTest(threads = 1, invocations = 1, preset = Preset.NONE)
        void body() {
            Thread child = new Thread(() -> {
                awaitQuietly(RELEASE);
                throw new AssertionError(MESSAGE);
            }, "unjoined-child");
            STARTED.set(child);
            child.start();
        }

        @AfterEachInvocation
        void releaseAndJoin() throws InterruptedException {
            RELEASE.countDown();
            STARTED.get().join();
        }
    }

    @org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
    public static class OutlivesItsRun {
        static final String MESSAGE = "died after the run that started it";
        static final AtomicReference<Thread> STARTED = new AtomicReference<>();
        static final java.util.concurrent.CountDownLatch RELEASE = new java.util.concurrent.CountDownLatch(1);

        @org.junit.jupiter.api.Order(1)
        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void startsAThreadAndLeaves() {
            Thread child = new Thread(() -> {
                awaitQuietly(RELEASE);
                throw new AssertionError(MESSAGE);
            }, "outliving-child");
            STARTED.set(child);
            child.start();
        }

        @org.junit.jupiter.api.Order(2)
        @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
                preset = Preset.NONE)
        void watchesItDie() throws InterruptedException {
            RELEASE.countDown();
            STARTED.get().join();
        }
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
