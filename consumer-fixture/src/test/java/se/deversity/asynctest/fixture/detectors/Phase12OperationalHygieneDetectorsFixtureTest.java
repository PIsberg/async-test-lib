package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.INTERRUPT_SWALLOWING})
    void interruptSwallowing() {
        reachable("interruptSwallowingDetector()", AsyncTestContext::interruptSwallowingDetector);

        // Swallowing means catching InterruptedException without restoring the flag. The
        // fixture restores it — leaving a worker interrupted would corrupt the next round.
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MDC_CONTEXT_LEAK})
    void mdcContextLeak() {
        reachable("mdcContextLeakDetector()", AsyncTestContext::mdcContextLeakDetector);

        // Modelled with a ThreadLocal rather than org.slf4j.MDC: the consumer fixture pins
        // the library's published dependency set, and reaching into SLF4J's MDC here would
        // make this fixture depend on a binding it does not declare.
        MDC_SUBSTITUTE.set("correlation-id");
        try {
            spin(32);
        } finally {
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
        String key = "async-test-lib.fixture.probe";
        System.setProperty(key, "set");
        try {
            System.getProperty(key);
        } finally {
            System.clearProperty(key);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FUTURE_IGNORED})
    void futureIgnored() {
        reachable("futureIgnoredDetector()", AsyncTestContext::futureIgnoredDetector);

        // A Future whose result and exception are never inspected swallows failures.
        Future<Integer> future = CompletableFuture.supplyAsync(() -> spin(32));
        future.isDone();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXPLICIT_GC})
    void explicitGc() {
        reachable("explicitGcDetector()", AsyncTestContext::explicitGcDetector);

        // The call the detector exists to find. It is a hint, not a command, and it stalls
        // every other thread in the JVM — which is why it is a finding.
        System.gc();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DEPRECATED_THREAD_API})
    @SuppressWarnings("deprecation")
    void deprecatedThreadApi() {
        reachable("deprecatedThreadApiDetector()", AsyncTestContext::deprecatedThreadApiDetector);

        // Thread.getId() is deprecated in favour of threadId() — a deprecated Thread API
        // call is exactly this detector's subject.
        spin((int) (Thread.currentThread().getId() % 16));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_XML_PARSER})
    void sharedXmlParser() {
        reachable("sharedXmlParserDetector()", AsyncTestContext::sharedXmlParserDetector);

        // DocumentBuilderFactory is not thread-safe; a per-use builder is the fix, and the
        // shared factory below is what the detector reasons about.
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
        SHARED_ZONE.getRawOffset();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.UNCAUGHT_EXCEPTION_HANDLER})
    void uncaughtExceptionHandler() {
        reachable("uncaughtExceptionHandlerDetector()",
            AsyncTestContext::uncaughtExceptionHandlerDetector);

        // A thread with no handler dies silently. The handler is set per-thread here rather
        // than through setDefaultUncaughtExceptionHandler, which is JVM-global.
        Thread worker = new Thread(() -> spin(32));
        worker.setUncaughtExceptionHandler((t, e) -> { });
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
