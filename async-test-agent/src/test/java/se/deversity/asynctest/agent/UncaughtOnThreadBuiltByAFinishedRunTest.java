package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadStartingJoiner;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A thread a worker of an earlier run constructed, left unstarted, and a worker of a later run
 * started through a woven {@code Thread.start} belongs to the later run when it dies (#1253). The
 * earlier run, which the thread inherited at construction, is over; it must not shadow the run
 * that started the thread.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UncaughtOnThreadBuiltByAFinishedRunTest {

    private static final AtomicReference<Thread> BUILT = new AtomicReference<>();
    /** Keeps the finished run reachable, as a suite that is still running usually does. */
    private static final AtomicReference<Object> FINISHED_RUN = new AtomicReference<>();
    private static AsyncFindings findings;

    private final ThreadStartingJoiner joiner = new ThreadStartingJoiner();

    @BeforeAll
    static void attachWithThreadWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
    }

    @Order(1)
    @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
            preset = Preset.NONE)
    void anEarlierRunBuildsTheThread() {
        FINISHED_RUN.set(se.deversity.asynctest.AsyncTestContext.get());
        BUILT.set(new Thread(() -> {
            throw new IllegalStateException("died for the run that started it");
        }, "built-by-a-finished-run"));
    }

    @Order(2)
    @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
            preset = Preset.NONE)
    void aLaterRunStartsIt() throws InterruptedException {
        joiner.startAndJoin(BUILT.get());
    }

    @AfterAll
    static void theDeathIsTheStartingRuns() {
        try {
            String report = findings.violationsFrom("UncaughtExceptionHandlerDetector").toString();
            assertTrue(report.contains("Thread 'built-by-a-finished-run' threw 'IllegalStateException'"),
                    "the later run started the thread through a woven start, and its death was "
                            + "dropped as the finished run's; findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
