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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * {@code synchronized} block pinned on 21 only (JEP 491). Each run initializes a fresh copy of the
 * class in its own loader, so a rerun in the same JVM (PIT, a repeated test) pins again instead of
 * finding the class already initialized.
 */
@E2E
class VirtualThreadPinningJfrTest {

    private static final String DETECTOR = "VirtualThreadPinningDetector";
    private static final Map<String, String> REPORTS = new ConcurrentHashMap<>();

    /** Its initializer blocks, which pins the virtual thread running it to its carrier. */
    public static final class BlockingInitializer {
        static {
            sleep(50);
        }

        private BlockingInitializer() {
        }
    }

    /** One worker initializes a fresh copy of {@link BlockingInitializer}. */
    public static class PinsInClassInitializer {
        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() {
            initializeFreshCopy(BlockingInitializer.class);
        }
    }

    /** The same block outside any initializer: a virtual thread unmounts and nothing pins. */
    public static class BlocksWithoutPinning {
        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() {
            sleep(50);
        }
    }

    /** Blocks while holding a monitor: pins up to JDK 23, not from 24 (JEP 491). */
    public static class BlocksInsideSynchronized {
        private static final Object LOCK = new Object();

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() {
            synchronized (LOCK) {
                sleep(50);
            }
        }
    }

    /** Waits while a thread that is not one of the run's workers pins. */
    public static class ForeignThreadPins {
        static volatile CountDownLatch go = new CountDownLatch(1);
        static volatile CountDownLatch pinned = new CountDownLatch(1);

        @AsyncTest(threads = 1, invocations = 1, useVirtualThreads = true,
                   includes = DetectorType.VIRTUAL_THREAD_PINNING)
        void body() throws InterruptedException {
            go.countDown();
            assertTrue(pinned.await(10, TimeUnit.SECONDS), "the foreign thread never pinned");
        }
    }

    @Test
    @DisplayName("a worker blocking in a class initializer is reported with no recording call")
    void pinInAClassInitializerIsReported() {
        run(PinsInClassInitializer.class);
        assertTrue(REPORTS.containsKey(DETECTOR),
                "the worker blocked for 50 ms inside <clinit>, which pins a virtual thread on every "
                        + "supported JDK and makes the JVM emit jdk.VirtualThreadPinned. The body "
                        + "recorded nothing, so the report has to come from that event. Reports: "
                        + REPORTS.keySet());
    }

    @Test
    @DisplayName("one blocking call reported by several JFR events is one pinning event")
    void oneBlockingCallIsOneEvent() {
        run(PinsInClassInitializer.class);
        String report = Objects.requireNonNull(REPORTS.get(DETECTOR), "no pinning report at all");
        // The JVM emits one event per park on the carrier; a timed sleep re-parks for its
        // remainder, so a single sleep produced two events on 21, 24 and 26 in the probe.
        assertTrue(report.contains(": 1 virtual thread pinning event(s)"),
                "one sleep on one thread at one site must be one event, not one per park. Report: "
                        + report);
    }

    @Test
    @DisplayName("the same block outside an initializer is silent, so the report above is the pin and not the sleep")
    void blockingWithoutPinningIsSilent() {
        run(BlocksWithoutPinning.class);
        assertFalse(REPORTS.containsKey(DETECTOR),
                "a virtual thread sleeping outside any initializer or monitor unmounts; nothing "
                        + "pinned. Report: " + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("blocking inside synchronized is reported only on a JDK where it still pins")
    void synchronizedPinsOnlyBeforeJdk24() {
        run(BlocksInsideSynchronized.class);
        boolean pinsHere = Runtime.version().feature() < 24;
        assertEquals(pinsHere, REPORTS.containsKey(DETECTOR),
                "JEP 491 stopped synchronized from pinning in JDK 24, and the JVM's own event is "
                        + "the arbiter: on JDK " + Runtime.version().feature() + " a report is "
                        + (pinsHere ? "required" : "a false positive") + ". Report: "
                        + REPORTS.get(DETECTOR));
    }

    @Test
    @DisplayName("a pin on a thread that is not one of the run's workers is not this run's finding")
    void aForeignThreadsPinIsNotAttributed() throws InterruptedException {
        ForeignThreadPins.go = new CountDownLatch(1);
        ForeignThreadPins.pinned = new CountDownLatch(1);
        Thread foreign = Thread.ofVirtual().name("not-a-worker").start(() -> {
            try {
                if (ForeignThreadPins.go.await(10, TimeUnit.SECONDS)) {
                    initializeFreshCopy(BlockingInitializer.class);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ForeignThreadPins.pinned.countDown();
            }
        });
        run(ForeignThreadPins.class);
        foreign.join(TimeUnit.SECONDS.toMillis(10));
        // JFR delivers every event to every open stream, so a run in parallel with another
        // test sees that test's pins; attributing them would report the wrong test.
        assertFalse(REPORTS.containsKey(DETECTOR),
                "the pin happened on a thread the run did not start. Report: " + REPORTS.get(DETECTOR));
    }

    private static void run(Class<?> fixture) {
        REPORTS.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTS.put(detectorName, report);
            }
        };
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            EngineTestKit.engine("junit-jupiter").selectors(selectClass(fixture)).execute();
        }
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
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
