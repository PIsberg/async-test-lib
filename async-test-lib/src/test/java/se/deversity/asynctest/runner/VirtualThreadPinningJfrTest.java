package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.E2E;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that the runner feeds {@code VirtualThreadPinningDetector} from the JVM's own
 * {@code jdk.VirtualThreadPinned} JFR event, so a test body that pins is reported without calling
 * {@code recordPinningEvent}.
 *
 * <p>Until this feed the detector reported only the pins a body described to it, which are the
 * pins its author already knew about. Every fixture below therefore records nothing.
 *
 * <p>The pinning fixture blocks inside a class initializer, the one cause that pins on every JDK
 * this project builds on: a 2026-10-10 probe saw the event for it on 21, 24 and 26, where a
 * {@code synchronized} block pinned on 21 only (JEP 491). Each body execution initializes a fresh
 * copy of the class in its own loader, so the same initializer pins again in every round.
 *
 * <p>A site is reported only when it pinned in at least two rounds. The first CI run of this feed
 * reported hundreds of pins on the corpus's correct bodies, nearly all a few milliseconds of
 * workers contending on {@code ClassLoader.loadClass} or a library's first use (Netty, Jackson,
 * our own detectors' lazy state): real pins, but warm-up, which a class can only do once per JVM.
 * A pin that recurs round after round is the code; that is the line the fixtures here draw.
 *
 * <p>Every test checks that its fixture body ran to the end and that each initializer it needed
 * finished. A body that throws produces no pin and no report, and the silent tests would then
 * pass on nothing; the first draft of this class did exactly that.
 */
@E2E
class VirtualThreadPinningJfrTest {

    private static final String DETECTOR = "VirtualThreadPinningDetector";
    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    /** Fixture body executions that ran to their last statement. */
    static final AtomicInteger COMPLETED = new AtomicInteger();
    /** Fresh copies of {@link BlockingInitializer} that finished initializing. */
    static final AtomicInteger INITIALIZED = new AtomicInteger();

    /**
     * Its initializer blocks, which pins the virtual thread running it to its carrier.
     *
     * <p>Touches only public JDK API: a fresh copy lives in another loader, hence another runtime
     * package, and a call to a package-private helper of this test throws
     * {@code IllegalAccessError} before anything blocks.
     */
    public static final class BlockingInitializer {
        static {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private BlockingInitializer() {
        }
    }

    /** Each round's worker initializes a fresh copy of {@link BlockingInitializer}. */
    public static class PinsInEveryRound {
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() {
            initializeFreshCopy(BlockingInitializer.class);
            COMPLETED.incrementAndGet();
        }
    }

    /** The same pin in round one only: the shape of class-loading warm-up. */
    public static class PinsInFirstRoundOnly {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() throws InterruptedException {
            if (EXECUTIONS.getAndIncrement() == 0) {
                initializeFreshCopy(BlockingInitializer.class);
            } else {
                Thread.sleep(50);
            }
            COMPLETED.incrementAndGet();
        }
    }

    /** The same block outside any initializer: a virtual thread unmounts and nothing pins. */
    public static class BlocksWithoutPinning {
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() throws InterruptedException {
            Thread.sleep(50);
            COMPLETED.incrementAndGet();
        }
    }

    /** Blocks while holding a monitor: pins up to JDK 23, not from 24 (JEP 491). */
    public static class BlocksInsideSynchronized {
        private static final Object LOCK = new Object();

        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() throws InterruptedException {
            synchronized (LOCK) {
                Thread.sleep(50);
            }
            COMPLETED.incrementAndGet();
        }
    }

    /** In every round, a thread the body starts (not a worker) pins and the body joins it. */
    public static class BodyStartedThreadPins {
        @AsyncTest(threads = 1, invocations = 2, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() throws InterruptedException {
            Thread.ofVirtual().start(() -> initializeFreshCopy(BlockingInitializer.class)).join();
            COMPLETED.incrementAndGet();
        }
    }

    @Test
    @DisplayName("a worker blocking in a class initializer in every round is reported with no recording call")
    void pinInEveryRoundIsReported() {
        int initialized = INITIALIZED.get();
        run(PinsInEveryRound.class, 2);
        assertEquals(initialized + 2, INITIALIZED.get(), "the initializers never ran, so nothing pinned");
        assertTrue(REPORTS.containsKey(DETECTOR),
                "each round's worker blocked for 50 ms inside <clinit>, which pins a virtual thread on "
                        + "every supported JDK and makes the JVM emit jdk.VirtualThreadPinned. The body "
                        + "recorded nothing, so the report has to come from that event. Reports: "
                        + REPORTS.keySet());
    }

    @Test
    @DisplayName("one blocking call reported by several JFR events is one pinning event per thread")
    void oneBlockingCallIsOneEventPerThread() {
        run(PinsInEveryRound.class, 2);
        String report = Objects.requireNonNull(REPORTS.get(DETECTOR), "no pinning report at all");
        // The JVM emits one event per park on the carrier; a timed sleep re-parks for its
        // remainder, so a single sleep produced two events on 21, 24 and 26 in the probe. Two
        // rounds, one fresh virtual worker each: two events, not four.
        assertTrue(report.contains(": 2 virtual thread pinning event(s)"),
                "one sleep on one thread at one site must be one event, not one per park. Report: "
                        + report);
    }

    @Test
    @DisplayName("a site that pinned in one round only is warm-up, not a finding")
    void pinInOneRoundOnlyIsSilent() {
        int initialized = INITIALIZED.get();
        PinsInFirstRoundOnly.EXECUTIONS.set(0);
        run(PinsInFirstRoundOnly.class, 2);
        assertEquals(initialized + 1, INITIALIZED.get(), "round one's initializer never ran");
        assertFalse(REPORTS.containsKey(DETECTOR),
                "the initializer pinned in round one and nothing pinned in round two: a class "
                        + "loads and initializes once per JVM, so a pin that cannot recur is warm-up. "
                        + "Report: " + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("the same block outside an initializer is silent, so the report above is the pin and not the sleep")
    void blockingWithoutPinningIsSilent() {
        run(BlocksWithoutPinning.class, 2);
        assertFalse(REPORTS.containsKey(DETECTOR),
                "a virtual thread sleeping outside any initializer or monitor unmounts; nothing "
                        + "pinned. Report: " + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("blocking inside synchronized is reported only on a JDK where it still pins")
    void synchronizedPinsOnlyBeforeJdk24() {
        run(BlocksInsideSynchronized.class, 2);
        boolean pinsHere = Runtime.version().feature() < 24;
        assertEquals(pinsHere, REPORTS.containsKey(DETECTOR),
                "JEP 491 stopped synchronized from pinning in JDK 24, and the JVM's own event is "
                        + "the arbiter: on JDK " + Runtime.version().feature() + " a report is "
                        + (pinsHere ? "required" : "a false positive") + ". Report: "
                        + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("a pin on a thread that is not one of the run's workers is not this run's finding")
    void aNonWorkersPinIsNotAttributed() {
        int initialized = INITIALIZED.get();
        run(BodyStartedThreadPins.class, 2);
        assertEquals(initialized + 2, INITIALIZED.get(), "the body's threads never ran the initializer");
        // JFR delivers every event to every open stream, so a run in parallel with another
        // test sees that test's pins; attributing them would report the wrong test. A thread
        // the body starts is indistinguishable from one, which is what #983 is about.
        assertFalse(REPORTS.containsKey(DETECTOR),
                "the pins happened on threads the runner did not start. Report: " + REPORTS.get(DETECTOR));
    }

    /** Runs {@code fixture} and fails unless all {@code executions} of its body ran to the end. */
    private static void run(Class<?> fixture, int executions) {
        REPORTS.clear();
        int completed = COMPLETED.get();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTS.put(detectorName, report);
            }
        };
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        }
        assertEquals(completed + executions, COMPLETED.get(),
                fixture.getSimpleName() + "'s body did not run to the end, so its silence or its "
                        + "report says nothing about pinning");
    }

    /** Defines {@code type} again in a loader of its own and initializes it, running its {@code <clinit>}. */
    static void initializeFreshCopy(Class<?> type) {
        String name = type.getName();
        byte[] bytes;
        try (InputStream in = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            bytes = Objects.requireNonNull(in, name).readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ClassLoader loader = new ClassLoader(type.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                if (!className.equals(name)) {
                    return super.loadClass(className, resolve);
                }
                synchronized (getClassLoadingLock(className)) {
                    Class<?> loaded = findLoadedClass(className);
                    return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
                }
            }
        };
        try {
            Class.forName(name, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        INITIALIZED.incrementAndGet();
    }
}
