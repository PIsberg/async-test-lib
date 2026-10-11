package se.deversity.asynctest.agent;

import com.example.agentfixture.LazyFillMapBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.report.Violation;

import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Check-then-act on a {@code ConcurrentHashMap} in unmodified code, found through the real weaver.
 *
 * <p>The fixture writes {@code containsKey} then {@code put} and records nothing. With
 * {@code collections=true} both calls are substituted, and the map hooks pair them per thread, map
 * and key. Its twin makes the same two calls under the map's own monitor, and must stay silent.
 * Both run through {@code @AsyncTest}, because the hooks resolve the detector from the live run.
 */
@Tag("e2e")
class CheckThenActWeavingEndToEndTest {

    private static final int THREADS = 2;

    private static AsyncFindings findings;

    private static final LazyFillMapBean RACED = new LazyFillMapBean();
    private static final LazyFillMapBean GUARDED = new LazyFillMapBean();
    private static final AtomicInteger RACED_TICKETS = new AtomicInteger();
    private static final AtomicInteger GUARDED_TICKETS = new AtomicInteger();

    /** Lines both workers up between the check and the put, the interleaving that loses a value. */
    private static final CyclicBarrier BETWEEN = new CyclicBarrier(THREADS);

    @BeforeAll
    static void attachWithCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
    }

    private static void meet() {
        try {
            BETWEEN.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException("a worker did not reach the put", e);
        }
    }

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void racedFill() {
        RACED.fill("raced-" + RACED_TICKETS.getAndIncrement() / THREADS,
                CheckThenActWeavingEndToEndTest::meet);
    }

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void guardedFill() {
        GUARDED.fillUnderTheMapsMonitor("guarded-" + GUARDED_TICKETS.getAndIncrement() / THREADS);
    }

    private static boolean reportedForKeyPrefix(String prefix) {
        List<Violation> found = findings.violationsFrom("NonAtomicConcurrentMapUpdate");
        return found.stream().anyMatch(v -> String.valueOf(v.attributes().get("report"))
                .contains("for key '" + prefix));
    }

    @AfterAll
    static void bothDirectionsHold() {
        try {
            assertTrue(reportedForKeyPrefix("raced-"),
                    "LazyFillMapBean.fill makes containsKey then put on a ConcurrentHashMap, and "
                            + "both workers of a round found the key absent before either put. "
                            + "Nothing was reported, so the woven calls never reached "
                            + "NonAtomicConcurrentMapUpdateDetector: check that the map hooks still "
                            + "hand a self-synchronized map to agentCheck and agentAct. Findings "
                            + "were: " + findings.violations());
            assertFalse(reportedForKeyPrefix("guarded-"),
                    "fillUnderTheMapsMonitor makes the same calls under synchronized (cache), so "
                            + "no second worker can land between them; a finding here is a false "
                            + "positive on the documented fix. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
