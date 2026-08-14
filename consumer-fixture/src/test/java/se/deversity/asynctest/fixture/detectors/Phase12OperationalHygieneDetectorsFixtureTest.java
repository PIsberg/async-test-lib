package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 12, operational &amp; hygiene group — {@code INTERRUPT_SWALLOWING} through
 * {@code UNCAUGHT_EXCEPTION_HANDLER}.
 *
 * <p>Corresponding examples: {@code examples/11-interrupt-swallowing},
 * {@code examples/12-mdc-context-leak}, {@code examples/13-system-property-mutation},
 * {@code examples/14-future-ignored}, {@code examples/15-explicit-gc},
 * {@code examples/16-deprecated-thread-api}, {@code examples/17-shared-xml-parser},
 * {@code examples/18-boxed-primitive-lock}, {@code examples/19-shared-timezone},
 * {@code examples/20-uncaught-exception-handler}.
 */
class Phase12OperationalHygieneDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "InterruptSwallowingDetector",
                    "MdcContextLeakDetector",
                    "SystemPropertyMutationDetector",
                    "FutureIgnoredDetector",
                    "ExplicitGcDetector",
                    "DeprecatedThreadApiDetector",
                    "SharedXmlParserDetector",
                    "BoxedPrimitiveLockDetector",
                    "SharedTimeZoneDetector",
                    "UncaughtExceptionHandlerDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.INTERRUPT_SWALLOWING})
    void interruptSwallowing() {
        reachable("interruptSwallowingDetector()", AsyncTestContext::interruptSwallowingDetector);

        // Swallowing means catching InterruptedException without restoring the flag. The
        // fixture restores it — leaving a worker interrupted would corrupt the next round.
        // Modelled, not performed: Thread.sleep(1) is not going to be interrupted here, and a
        // fixture that genuinely swallowed an interrupt would leave the worker's flag clear for
        // the next round. The recording is the swallow the detector reasons about; the flag is
        // still restored below so the round stays clean.
        var detector = AsyncTestContext.interruptSwallowingDetector();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            detector.recordCatch(Thread.currentThread(), "Phase12.interruptSwallowing", false);
            Thread.currentThread().interrupt();
        }
        detector.recordCatch(Thread.currentThread(), "Phase12.interruptSwallowing", false);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MDC_CONTEXT_LEAK})
    void mdcContextLeak() {
        reachable("mdcContextLeakDetector()", AsyncTestContext::mdcContextLeakDetector);

        // Modelled with a ThreadLocal rather than org.slf4j.MDC: the consumer fixture pins
        // the library's published dependency set, and reaching into SLF4J's MDC here would
        // make this fixture depend on a binding it does not declare.
        var detector = AsyncTestContext.mdcContextLeakDetector();
        detector.recordTaskStart(Thread.currentThread(), java.util.Map.of());
        MDC_SUBSTITUTE.set("correlation-id");
        try {
            spin(32);
        } finally {
            // The leak: the task ends with context the task did not start with, which on a
            // pooled thread is then inherited by whatever runs next. Recorded before remove()
            // so the fixture leaves no state behind while still showing the hazard.
            detector.recordTaskEnd(Thread.currentThread(),
                    java.util.Map.of("correlationId", "correlation-id"));
            MDC_SUBSTITUTE.remove();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SYSTEM_PROPERTY_MUTATION})
    void systemPropertyMutation() {
        reachable("systemPropertyMutationDetector()",
            AsyncTestContext::systemPropertyMutationDetector);

        // System properties are JVM-global; mutating one from a test leaks into every other
        // test in the fork. A fixture-private key keeps the blast radius to this method.
        var detector = AsyncTestContext.systemPropertyMutationDetector();
        String key = "async-test-lib.fixture.probe";
        System.setProperty(key, "set");
        detector.recordSet(key, "set", Thread.currentThread());
        try {
            System.getProperty(key);
        } finally {
            System.clearProperty(key);
            detector.recordClear(key, Thread.currentThread());
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FUTURE_IGNORED})
    void futureIgnored() {
        reachable("futureIgnoredDetector()", AsyncTestContext::futureIgnoredDetector);

        // A Future whose result and exception are never inspected swallows failures.
        // Submitted and never inspected: no get(), no join(), no exceptionally(). The missing
        // recordInspect call is the finding, so there is deliberately none below.
        var detector = AsyncTestContext.futureIgnoredDetector();
        Future<Integer> future = CompletableFuture.supplyAsync(() -> spin(32));
        detector.recordSubmit(future, "ignored-task", Thread.currentThread());
        future.isDone();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXPLICIT_GC})
    void explicitGc() {
        reachable("explicitGcDetector()", AsyncTestContext::explicitGcDetector);

        // The call the detector exists to find. It is a hint, not a command, and it stalls
        // every other thread in the JVM — which is why it is a finding.
        AsyncTestContext.explicitGcDetector()
                .recordGcInvocation(Thread.currentThread(), "Phase12.explicitGc");
        System.gc();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DEPRECATED_THREAD_API})
    @SuppressWarnings("deprecation")
    void deprecatedThreadApi() {
        reachable("deprecatedThreadApiDetector()", AsyncTestContext::deprecatedThreadApiDetector);

        // Thread.getId() is deprecated in favour of threadId() — a deprecated Thread API
        // call is exactly this detector's subject.
        AsyncTestContext.deprecatedThreadApiDetector()
                .recordApiUse("Thread.getId()", Thread.currentThread());
        spin((int) (Thread.currentThread().getId() % 16));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_XML_PARSER})
    void sharedXmlParser() {
        reachable("sharedXmlParserDetector()", AsyncTestContext::sharedXmlParserDetector);

        // DocumentBuilderFactory is not thread-safe; a per-use builder is the fix, and the
        // shared factory below is what the detector reasons about.
        AsyncTestContext.sharedXmlParserDetector()
                .recordAccess(SHARED_XML_FACTORY, "DocumentBuilderFactory", Thread.currentThread());
        try {
            SHARED_XML_FACTORY.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new AssertionError("default DocumentBuilder configuration must work", e);
        } catch (RuntimeException expected) {
            // A shared factory losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.BOXED_PRIMITIVE_LOCK})
    void boxedPrimitiveLock() {
        reachable("boxedPrimitiveLockDetector()", AsyncTestContext::boxedPrimitiveLockDetector);

        // Small Integers are cached and interned JVM-wide, so this monitor is shared with
        // every other piece of code that locks on the same boxed value.
        AsyncTestContext.boxedPrimitiveLockDetector()
                .recordLockAcquire(BOXED_MONITOR, Thread.currentThread(), "Phase12.boxedPrimitiveLock");
        synchronized (BOXED_MONITOR) {
            spin(32);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_TIMEZONE})
    void sharedTimeZone() {
        reachable("sharedTimeZoneDetector()", AsyncTestContext::sharedTimeZoneDetector);

        // Reads only: TimeZone.setDefault() would change process-wide state and leak into
        // every later test in this fork.
        // Recorded as a mutation because that is the hazard the detector names; the fixture
        // only reads, since TimeZone.setDefault() is process-wide and would leak into every
        // later test in this fork.
        AsyncTestContext.sharedTimeZoneDetector()
                .recordMutation(SHARED_ZONE, "setRawOffset", Thread.currentThread());
        SHARED_ZONE.getRawOffset();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.UNCAUGHT_EXCEPTION_HANDLER})
    void uncaughtExceptionHandler() {
        reachable("uncaughtExceptionHandlerDetector()",
            AsyncTestContext::uncaughtExceptionHandlerDetector);

        // A thread with no handler dies silently. The handler is set per-thread here rather
        // than through setDefaultUncaughtExceptionHandler, which is JVM-global.
        // The finding needs both halves: a thread that had no handler when it started, and an
        // exception that then went uncaught. recordThreadStart is therefore called before any
        // handler exists, so the detector sees hasCustomHandler=false.
        //
        // The handler installed afterwards is fixture hygiene, not part of the hazard: it
        // reports the throw to the detector and swallows it, so a dying thread does not spray a
        // stack trace across the consumer's build log.
        var uehDetector = AsyncTestContext.uncaughtExceptionHandlerDetector();
        Thread worker = new Thread(() -> {
            spin(32);
            throw new IllegalStateException("worker died with no handler of its own");
        });
        uehDetector.recordThreadStart(worker);
        worker.setUncaughtExceptionHandler((t, e) -> uehDetector.recordUncaughtException(t, e));
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final ThreadLocal<String> MDC_SUBSTITUTE = new ThreadLocal<>();

    private static final DocumentBuilderFactory SHARED_XML_FACTORY =
        DocumentBuilderFactory.newInstance();

    /** Cached boxed value: an interned, JVM-wide monitor. */
    private static final Integer BOXED_MONITOR = 42;

    private static final TimeZone SHARED_ZONE = TimeZone.getTimeZone("UTC");
}
