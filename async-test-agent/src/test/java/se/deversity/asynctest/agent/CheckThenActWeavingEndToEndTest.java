package se.deversity.asynctest.agent;

import com.example.agentfixture.BoundedAdmissionMapBean;
import com.example.agentfixture.DrainOneQueueBean;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
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
 *
 * <p>The keyless pair is pinned the same way (#1249): {@code size} then {@code put} of each
 * worker's own key, with both workers lined up between the two, and its twin under the monitor.
 * So is the queue pair: {@code isEmpty} then {@code remove}, where the second worker to remove
 * takes a job its check never saw, and its twin under the queue's monitor.
 */
@Tag("e2e")
class CheckThenActWeavingEndToEndTest {

    private static final int THREADS = 2;

    private static AsyncFindings findings;

    private static final LazyFillMapBean RACED = new LazyFillMapBean();
    private static final LazyFillMapBean GUARDED = new LazyFillMapBean();
    private static final AtomicInteger RACED_TICKETS = new AtomicInteger();
    private static final AtomicInteger GUARDED_TICKETS = new AtomicInteger();
    // Two map types, because a report names the map by its type and a number that restarts in
    // every run: the type is what tells the raced run's finding from the guarded one's.
    private static final BoundedAdmissionMapBean RACED_ADMISSION =
            new BoundedAdmissionMapBean(new ConcurrentHashMap<>());
    private static final BoundedAdmissionMapBean GUARDED_ADMISSION =
            new BoundedAdmissionMapBean(new ConcurrentSkipListMap<>());
    private static final AtomicInteger ADMISSION_TICKETS = new AtomicInteger();
    // The same two-type split for the queue pair; jobs to spare, so no remove ever throws.
    private static final DrainOneQueueBean RACED_DRAIN =
            new DrainOneQueueBean(new ConcurrentLinkedQueue<>(), 1_000);
    private static final DrainOneQueueBean GUARDED_DRAIN =
            new DrainOneQueueBean(new LinkedBlockingQueue<>(), 1_000);

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

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void racedAdmission() {
        RACED_ADMISSION.admit("member-" + ADMISSION_TICKETS.getAndIncrement(),
                CheckThenActWeavingEndToEndTest::meet);
    }

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void guardedAdmission() {
        GUARDED_ADMISSION.admitUnderTheMapsMonitor("member-" + ADMISSION_TICKETS.getAndIncrement());
    }

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void racedDrain() {
        RACED_DRAIN.drainOne(CheckThenActWeavingEndToEndTest::meet);
    }

    @AsyncTest(threads = THREADS, invocations = 10, detectAll = true)
    void guardedDrain() {
        GUARDED_DRAIN.drainOneUnderTheQueuesMonitor();
    }

    /** {@return whether an isEmpty-then-remove finding was reported on a queue of type {@code queueType}} */
    private static boolean isEmptyThenRemoveReportedOn(String queueType) {
        return findings.violationsFrom("BlockingQueue").stream()
                .map(v -> String.valueOf(v.attributes().get("report")))
                .anyMatch(report -> report.contains(queueType + "@")
                        && report.contains("isEmpty-then-remove"));
    }

    /** {@return whether a size-then-put finding was reported on a map of type {@code mapType}} */
    private static boolean sizeThenPutReportedOn(String mapType) {
        return findings.violationsFrom("NonAtomicConcurrentMapUpdate").stream()
                .map(v -> String.valueOf(v.attributes().get("report")))
                .anyMatch(report -> report.contains("'size-then-put' on " + mapType + "@"));
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
            assertTrue(sizeThenPutReportedOn("ConcurrentHashMap"),
                    "BoundedAdmissionMapBean.admit makes size then put on a ConcurrentHashMap, and "
                            + "both workers of a round checked before either put, so the second put "
                            + "landed after another worker's. Nothing was reported, so the woven "
                            + "size never reached agentSizeCheck (#1249). Findings were: "
                            + findings.violations());
            assertFalse(sizeThenPutReportedOn("ConcurrentSkipListMap"),
                    "admitUnderTheMapsMonitor makes the same calls on a ConcurrentSkipListMap under "
                            + "synchronized (members); a "
                            + "finding here is a false positive on the fix. Findings were: "
                            + findings.violations());
            assertTrue(isEmptyThenRemoveReportedOn("ConcurrentLinkedQueue"),
                    "DrainOneQueueBean.drainOne makes isEmpty then remove on a "
                            + "ConcurrentLinkedQueue, and both workers of a round checked the same "
                            + "head before either removed, so the second remove took another job. "
                            + "Nothing was reported, so the woven isEmpty or remove never reached "
                            + "BlockingQueueDetector (#1249). Findings were: " + findings.violations());
            assertFalse(isEmptyThenRemoveReportedOn("LinkedBlockingQueue"),
                    "drainOneUnderTheQueuesMonitor makes the same calls on a LinkedBlockingQueue "
                            + "under synchronized (queue); a finding here is a false positive on "
                            + "the fix. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
