package se.deversity.asynctest.agent;

import com.example.agentfixture.FeedAuditSubjects;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.diagnostics.WorkerSlot;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which agent-fed detector, if any, already reports the bug a recording-only detector names, on
 * unmodified code that records nothing ({@code docs/analysis/agent-feed-audit.md}).
 *
 * <p>Each row runs a buggy body and its fixed twin on two workers lined up on a barrier, 20 rounds,
 * with every detector on and the agent weaving fields and collections, and pins the detectors that
 * reported. The control row is the README's {@code counter++}, which {@code AtomicityValidator}
 * reports through the agent: without it, a row of silence could mean the harness fed nothing. A row
 * that changes because a detector was moved to the agent feed is the point of the audit; change the
 * pinned set and the write-up together.
 */
@Tag("e2e")
class AgentFeedOverlapAuditTest {

    private static final int THREADS = 2;
    private static final String RUN_FAILED = "<run failed>";
    private static final Map<String, Set<String>> FIRED = new TreeMap<>();

    @BeforeAll
    static void attach() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterAll
    static void report() {
        TelemetryRegistry.stop();
        FIRED.forEach((row, detectors) -> System.out.println("AUDIT " + row + " -> " + detectors));
    }

    interface Body {
        void run(int slot) throws Throwable;
    }

    /** Runs {@code body} on two workers lined up on a barrier; returns the detectors that reported. */
    private static Set<String> detectorsReporting(String row, Body body) {
        CyclicBarrier lineUp = new CyclicBarrier(THREADS);
        Set<String> detectors = new TreeSet<>();
        try (AsyncFindings findings = AsyncFindings.collect()) {
            try {
                AsyncTestRunner.run(AsyncTestConfig.builder().threads(THREADS).invocations(20)
                        .detectAll(true).timeoutMs(60_000).licenseMockMode(true).build(), () -> {
                    int slot = WorkerSlot.current();
                    lineUp.await(10, TimeUnit.SECONDS);
                    body.run(slot);
                });
            } catch (Throwable thrown) { // NOPMD - a buggy body may fail its run; the findings are the result
                FIRED.put(row + " (run failed: " + thrown.getClass().getSimpleName() + ")", Set.of());
            }
            for (Violation violation : findings.violations()) {
                detectors.add(violation.detector());
            }
        }
        FIRED.put(row, detectors);
        return detectors;
    }

    private static void assertRow(String row, Set<String> expected, Set<String> actual) {
        assertEquals(expected, actual, row + ": the detectors reporting this body changed. If a detector"
                + " was moved to the agent feed on purpose, update this row and docs/analysis/"
                + "agent-feed-audit.md together; otherwise an agent-fed detector gained or lost a bug class.");
    }

    @Test
    void control_theHarnessFeedsTheAgentPath() {
        FeedAuditSubjects.Counter counter = new FeedAuditSubjects.Counter();
        Set<String> detectors = detectorsReporting("0 CONTROL counter++", slot -> counter.increment());
        assertTrue(detectors.contains("AtomicityValidator"),
                "the README's counter++ was not reported, so the harness is not feeding the agent path"
                        + " and every silent row below means nothing: " + detectors);
    }

    @Test
    void concurrentModification_isAGap() {
        FeedAuditSubjects.SharedList buggy = new FeedAuditSubjects.SharedList();
        assertRow("1 CONCURRENT_MODIFICATIONS buggy", Set.of(),
                detectorsReporting("1 CONCURRENT_MODIFICATIONS buggy", slot -> {
                    if (slot == 0) {
                        buggy.sum();
                    } else {
                        buggy.add(slot);
                    }
                }));
        FeedAuditSubjects.SharedList fixed = new FeedAuditSubjects.SharedList();
        assertRow("1 CONCURRENT_MODIFICATIONS fixed", Set.of(),
                detectorsReporting("1 CONCURRENT_MODIFICATIONS fixed", slot -> {
                    if (slot == 0) {
                        fixed.sumGuarded();
                    } else {
                        fixed.addGuarded(slot);
                    }
                }));
    }

    @Test
    void visibility_isAGap() {
        FeedAuditSubjects.Publication buggy = new FeedAuditSubjects.Publication();
        assertRow("2 VISIBILITY buggy", Set.of(), detectorsReporting("2 VISIBILITY buggy", slot -> {
            if (slot == 0) {
                buggy.publish();
            } else {
                buggy.consume();
            }
        }));
        FeedAuditSubjects.Publication fixed = new FeedAuditSubjects.Publication();
        assertRow("2 VISIBILITY fixed", Set.of(), detectorsReporting("2 VISIBILITY fixed", slot -> {
            if (slot == 0) {
                fixed.publishVolatile();
            } else {
                fixed.consumeVolatile();
            }
        }));
    }

    @Test
    void completionLeak_isAGap() {
        assertRow("3 COMPLETABLE_FUTURE_COMPLETION_LEAKS buggy", Set.of(),
                detectorsReporting("3 COMPLETABLE_FUTURE_COMPLETION_LEAKS buggy",
                        slot -> FeedAuditSubjects.fetchLeaking(slot == 0)));
        assertRow("3 COMPLETABLE_FUTURE_COMPLETION_LEAKS fixed", Set.of(),
                detectorsReporting("3 COMPLETABLE_FUTURE_COMPLETION_LEAKS fixed",
                        slot -> FeedAuditSubjects.fetchCompleting(slot == 0)));
    }

    @Test
    void droppedRejection_isAGap() {
        FeedAuditSubjects.Pool pool = new FeedAuditSubjects.Pool();
        Runnable slow = () -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertRow("4 THREAD_POOL buggy", Set.of(), detectorsReporting("4 THREAD_POOL buggy", slot -> {
                for (int i = 0; i < 4; i++) {
                    pool.submitDropping(slow);
                }
            }));
            assertRow("4 THREAD_POOL fixed", Set.of(), detectorsReporting("4 THREAD_POOL fixed", slot -> {
                for (int i = 0; i < 4; i++) {
                    pool.submitCallerRuns(slow);
                }
            }));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void threadLeak_isReportedOnlyAsDaemonHygiene() {
        assertRow("5 THREAD_LEAKS buggy", Set.of("DaemonThreadHygieneDetector"),
                detectorsReporting("5 THREAD_LEAKS buggy", slot -> FeedAuditSubjects.startAndForget()));
        assertRow("5 THREAD_LEAKS fixed", Set.of(),
                detectorsReporting("5 THREAD_LEAKS fixed", slot -> FeedAuditSubjects.startAndJoin()));
    }
}
