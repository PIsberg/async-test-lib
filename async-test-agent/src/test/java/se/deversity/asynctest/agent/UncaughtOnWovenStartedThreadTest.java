package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadStartingJoiner;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A thread constructed outside the run and started by a worker through a woven
 * {@code Thread.start} belongs to that run when it dies of an uncaught exception (#1253).
 *
 * <p>Without the agent the runner knows a body's thread only by the worker that constructed it,
 * and these are constructed on the JUnit thread before the run, so only the woven start ties them
 * to it.
 */
@Tag("e2e")
class UncaughtOnWovenStartedThreadTest {

    private static final int EXECUTIONS = 2 * 2;
    private static final Queue<Thread> BUILT_BEFORE_THE_RUN = new ConcurrentLinkedQueue<>();
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
        for (int i = 0; i < EXECUTIONS; i++) {
            BUILT_BEFORE_THE_RUN.add(new Thread(() -> {
                throw new IllegalStateException("died on a thread a worker started");
            }, "built-before-the-run-" + i));
        }
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
            preset = Preset.NONE)
    void startsAThreadBuiltElsewhere() throws InterruptedException {
        joiner.startAndJoin(BUILT_BEFORE_THE_RUN.remove());
    }

    @AfterAll
    static void everyDeathIsReported() {
        try {
            String report = findings.violationsFrom("UncaughtExceptionHandlerDetector").toString();
            for (int i = 0; i < EXECUTIONS; i++) {
                assertTrue(report.contains("Thread 'built-before-the-run-" + i + "' threw 'IllegalStateException'"),
                        "each thread a worker started through the woven start died uncaught, and each "
                                + "must be reported; thread " + i + " was not in: " + findings.violations());
            }
        } finally {
            findings.close();
        }
    }
}
