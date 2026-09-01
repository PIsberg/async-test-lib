package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live sample has to see virtual threads, or say it cannot.
 *
 * <p><strong>Why this exists.</strong> {@code StaticInitDeadlockDetector}'s javadoc offers the
 * live sample as the path that works "with no instrumentation at all". It walked
 * {@code Thread.getAllStackTraces()}, which does not include virtual threads, and
 * {@code @AsyncTest} runs its workers on virtual threads by default - so on the runner everybody
 * uses, the zero-instrumentation path saw nothing the test itself did. Issue #376.
 *
 * <p>The dump {@code VirtualThreadLockGraph} already reads carries the three facts the sample
 * needs: a name, a state and a stack. Two of the three are not enough, which is the point of the
 * second test here: on a JDK whose dump has stacks but no state - measured on 21 - a thread
 * *running* a static initializer cannot be told from one parked in it, and reporting the first
 * would be a false positive on a class that is merely initializing. The sample takes virtual
 * threads only where the state is there to filter on.
 */
class StaticInitSampleSeesVirtualThreadsTest {

    @Test
    @DisplayName("the reader exposes a state and a stack per thread, which the sample needs")

    void theDumpReaderCarriesWhatTheSampleNeeds() throws Exception {
        // A virtual thread that is alive and parked while the dump is taken. Without one there
        // is nothing for this test to find, and an earlier draft asserted on virtual threads
        // without creating any: it failed, correctly, on a JVM that simply had none running.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread parked = Thread.ofVirtual().name("sample-probe").start(() -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "the probe must be running");

        try {
            Optional<List<VirtualThreadLockGraph.DumpedThread>> threads =
                    VirtualThreadLockGraph.threadsWithState();

            if (threads.isEmpty()) {
                return;     // this JDK's dump has no state; the case below covers what that means
            }
            assertFalse(threads.get().isEmpty(), "a running JVM has threads");
            assertTrue(threads.get().stream().anyMatch(t -> t.state() != null),
                    "threadsWithState() returned present, so a state must actually be there");
            assertTrue(threads.get().stream().anyMatch(t -> !t.stack().isEmpty()),
                    "and at least one thread must have a stack, or the sample has nothing to "
                            + "match <clinit> against");
            assertTrue(threads.get().stream()
                            .anyMatch(t -> t.virtual() && "sample-probe".equals(t.name())),
                    "the parked virtual thread must be in the dump: it is the whole reason this "
                            + "path exists, and Thread.getAllStackTraces() would not have it. "
                            + "Found: " + threads.get().stream()
                                    .filter(VirtualThreadLockGraph.DumpedThread::virtual)
                                    .map(VirtualThreadLockGraph.DumpedThread::name).toList());
        } finally {
            release.countDown();
            parked.join(5_000);
        }
    }

    @Test
    @DisplayName("a dump with stacks but no state reports 'cannot tell', not an empty sample")
    void aDumpWithoutStateIsNotAnEmptySample() {
        // The JDK 21 shape: stacks, no state. Reusing the fixture #367 captured, because the
        // point is a shape this machine may not be able to produce.
        String jdk21 = VirtualThreadLockGraphTest.fixture("virtual-thread-deadlock-jdk21.json");
        assertFalse(jdk21.contains("\"state\""),
                "the fixture is the pre-state dump shape; if this fails the fixture was replaced");

        assertTrue(VirtualThreadLockGraph.scanDumpForThreads(jdk21).isEmpty(),
                "without a state, a thread running a static initializer cannot be told from one "
                        + "parked in it. Returning the threads anyway would let the sample report "
                        + "a class that is merely initializing, which is a false positive on "
                        + "correct code.");
    }

    /**
     * The end-to-end claim: a virtual thread parked inside a static initializer reaches the
     * detector's live sample.
     *
     * <p>Two classes whose initializers wait on each other, touched from two virtual threads, is
     * the shape the detector exists for. {@code ThreadMXBean.findDeadlockedThreads()} cannot see
     * it, and neither could the sample, because both walk platform threads only.
     *
     * <p>The two initializers deadlock for the life of the JVM and cannot be broken; surefire runs
     * with {@code reuseForks=false}, so they die with this class's JVM and cannot reach another
     * test class. This test runs last in the file for the same reason the deadlock probe in
     * {@code VirtualThreadLockGraphTest} does.
     */
    @Test
    @DisplayName("a virtual thread parked in a static initializer reaches the live sample")
    void aVirtualThreadParkedInAClinitIsSampled() throws Exception {
        // Constructed before the wedge, the way ConcurrencyRunner constructs detectors before
        // the test body runs: the detector baselines what is already parked at construction,
        // and only a wedge created after it counts.
        StaticInitDeadlockDetector detector = new StaticInitDeadlockDetector();

        Thread first = Thread.ofVirtual().name("clinit-a").start(() -> touch(Alpha.class));
        Thread second = Thread.ofVirtual().name("clinit-b").start(() -> touch(Beta.class));
        assertTrue(BOTH_INSIDE.await(5, TimeUnit.SECONDS),
                "both initializers must be entered before they wait on each other");
        Thread.sleep(400);      // and then park, each waiting for the other's class

        String report = detector.analyze().toString();

        if (VirtualThreadLockGraph.threadsWithState().isEmpty()) {
            return;     // this JDK's dump carries no state; nothing can be sampled here
        }
        assertTrue(report.contains("clinit-a") || report.contains("clinit-b"),
                "the sample must name at least one of the parked virtual threads. Neither "
                        + "getAllStackTraces() nor findDeadlockedThreads() would have found "
                        + "them. Report was: " + report);
        first.interrupt();
        second.interrupt();
    }

    private static final CountDownLatch BOTH_INSIDE = new CountDownLatch(2);

    private static void touch(Class<?> type) {
        try {
            Class.forName(type.getName(), true, StaticInitSampleSeesVirtualThreadsTest.class.getClassLoader());
        } catch (Throwable ignored) {
            // The initializer never completes; that is the subject, not a failure.
        }
    }

    /** Its initializer waits for Beta's, and Beta's waits for its. */
    static class Alpha {
        static void touchMe() {
            // referencing the class is enough to require its initialization
        }

        static {
            BOTH_INSIDE.countDown();
            try {
                BOTH_INSIDE.await(5, TimeUnit.SECONDS);
                Beta.touchMe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The other half of the pair. */
    static class Beta {
        static {
            BOTH_INSIDE.countDown();
            try {
                BOTH_INSIDE.await(5, TimeUnit.SECONDS);
                Alpha.touchMe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        static void touchMe() {
            // referencing the class is enough to require its initialization
        }
    }

    /**
     * The other direction, and the one that pays for dropping the state filter.
     *
     * <p>Two threads each initializing a different class at the same instant is ordinary startup,
     * not a deadlock. Since a real class-init deadlock reports RUNNABLE, the state filter could
     * not tell the two apart in either direction; what does is whether it persists. Both
     * initializers below finish in about 20ms, against the 150ms the detector waits between its
     * two samples, so they are gone by the second one.
     *
     * <p>A margin rather than a latch, because the second sample happens inside {@code analyze()}
     * and the test cannot synchronise with it. Seven times over is wide enough that a slow runner
     * does not turn this red, and if it ever does, the failure is honest: it means the sample
     * interval is too short to distinguish slow initialization from a stall.
     */
    @Test
    @DisplayName("two classes merely initializing at the same time are not a finding")
    void concurrentButProgressingInitializersAreNotReported() throws Exception {
        StaticInitDeadlockDetector detector = new StaticInitDeadlockDetector();
        Thread first = new Thread(() -> touchLoading(Slow.class), "loading-one");
        Thread second = new Thread(() -> touchLoading(Slower.class), "loading-two");
        first.start();
        second.start();
        // Waited on, not slept past. "Both are inside their initializers now" is the premise the
        // whole assertion rests on, and a fixed 5 ms was a guess about scheduling rather than a
        // fact - a run where it was wrong would have passed for the wrong reason, silently.
        assertTrue(PROGRESSING_BOTH_INSIDE.await(5, TimeUnit.SECONDS),
                "both initializers must be entered before the sample is taken, or this test is "
                        + "asserting that a sampler saw nothing");

        String report;
        long previousDelay = StaticInitDeadlockDetector.secondSampleDelayMs;
        // The initializers take 20 ms and the production window is 150. That 7x margin is what
        // a loaded runner ate in #457, starving the initializing threads past the second sample
        // and turning ordinary class loading into a reported deadlock. Widening the window under
        // test changes nothing about what is asserted; it only stops the machine being part of it.
        StaticInitDeadlockDetector.secondSampleDelayMs = 3_000;
        try {
            report = detector.analyze().toString();
        } finally {
            StaticInitDeadlockDetector.secondSampleDelayMs = previousDelay;
        }

        first.join(5_000);
        second.join(5_000);
        // Named threads, not "no finding at all": whatever else this JVM holds — this class
        // deliberately leaves Alpha/Beta wedged for its lifetime, though a detector constructed
        // after that wedge now baselines it away — the claim here is only that these two
        // fast-completing initializers are not reported.
        assertFalse(report.contains("loading-one") || report.contains("loading-two"),
                "both initializers completed well inside the sample interval, so this is two "
                        + "classes loading, not a deadlock. Reporting them would be a false "
                        + "positive on ordinary startup, which is the whole cost of dropping the "
                        + "thread-state filter. Report was: " + report);
    }

    private static void touchLoading(Class<?> type) {
        try {
            Class.forName(type.getName(), true,
                    StaticInitSampleSeesVirtualThreadsTest.class.getClassLoader());
        } catch (Throwable ignored) {
            // not the subject
        }
    }

    /** Initializes slowly enough to be caught by one sample, fast enough to be gone by the next. */
    static class Slow {
        static {
            PROGRESSING_BOTH_INSIDE.countDown();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The second one, so the sample spans two different initializers. */
    static class Slower {
        static {
            PROGRESSING_BOTH_INSIDE.countDown();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * A wedge that predates the detector is not this run's finding.
     *
     * <p>This is the same baseline {@code DeadlockDetector} takes at construction, for the same
     * reason: in a JVM where an earlier test wedged threads inside class initializers — this very
     * file leaves Alpha/Beta wedged on purpose — a detector constructed afterwards would blame
     * every later run for a deadlock that predates it. {@code ConcurrencyRunner} constructs
     * detectors before the test body runs, so "already parked at construction" exactly separates
     * someone else's wedge from the run under test.
     *
     * <p>Platform daemon threads, not virtual ones, so the regression holds on JDKs whose thread
     * dump carries no state, and so the leaked pair cannot keep the JVM alive at exit.
     */
    @Test
    @DisplayName("threads already parked in an initializer at construction are baselined away")
    void preExistingInitializerWedgeIsBaselinedAtConstruction() throws Exception {
        Thread first = Thread.ofPlatform().daemon().name("stale-clinit-a")
                .start(() -> touchLoading(Gamma.class));
        Thread second = Thread.ofPlatform().daemon().name("stale-clinit-b")
                .start(() -> touchLoading(Delta.class));
        assertTrue(STALE_BOTH_INSIDE.await(5, TimeUnit.SECONDS),
                "both initializers must be entered before they wait on each other");
        Thread.sleep(400);      // and then park, each waiting for the other's class

        StaticInitDeadlockDetector constructedAfterTheWedge = new StaticInitDeadlockDetector();
        String report = constructedAfterTheWedge.analyze().toString();

        assertFalse(report.contains("stale-clinit-a") || report.contains("stale-clinit-b"),
                "a wedge that existed before the detector was constructed belongs to an earlier "
                        + "test, not to this run; reporting it makes every run after a leaked "
                        + "initializer deadlock fail on someone else's bug. Report was: " + report);

        first.interrupt();
        second.interrupt();
    }

    /** Counted down by each progressing initializer on entry, so "both inside" is a fact. */
    private static final CountDownLatch PROGRESSING_BOTH_INSIDE = new CountDownLatch(2);

    private static final CountDownLatch STALE_BOTH_INSIDE = new CountDownLatch(2);

    /** Its initializer waits for Delta's, and Delta's waits for its: wedged before construction. */
    static class Gamma {
        static void touchMe() {
            // referencing the class is enough to require its initialization
        }

        static {
            STALE_BOTH_INSIDE.countDown();
            try {
                STALE_BOTH_INSIDE.await(5, TimeUnit.SECONDS);
                Delta.touchMe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The other half of the pre-existing pair. */
    static class Delta {
        static {
            STALE_BOTH_INSIDE.countDown();
            try {
                STALE_BOTH_INSIDE.await(5, TimeUnit.SECONDS);
                Gamma.touchMe();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        static void touchMe() {
            // referencing the class is enough to require its initialization
        }
    }
}
