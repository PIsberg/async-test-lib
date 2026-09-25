package se.deversity.asynctest.agent;

import com.example.agentfixture.DaemonDecidingThreadFactory;
import com.example.agentfixture.InheritingThreadFactory;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentThreadHooks;
import se.deversity.asynctest.diagnostics.ThreadFactoryDetector;

import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins ThreadFactoryDetector's daemon branch once {@code Thread.setDaemon} is woven (#731): a
 * factory called from a daemon worker that never decides is reported, one that calls
 * setDaemon(true) is not, and a JDK factory, which decides where nothing is woven, is not.
 *
 * <p>Without the agent all three hand back a daemon thread and none can be told apart; the
 * library side of that direction is ThreadFactoryDetectorTest.
 */
@Tag("e2e")
class DaemonThreadFactoryWeavingTest {

    @BeforeAll
    static void attachWithThreadWeaving() {
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
        assertTrue(AgentThreadHooks.isThreadWeavingInstalled(),
                "the agent installed the thread table but never told the library, so the "
                        + "detector keeps reading the inherited flag and every test here is moot");
    }

    @Test
    void aFactoryThatNeverDecidesIsReportedFromADaemonWorker() throws InterruptedException {
        ThreadFactoryDetector.ThreadFactoryReport report =
                createOnDaemonWorker(new InheritingThreadFactory(), "inheriting");

        assertTrue(report.hasIssues() && report.toString().contains("daemon only by inheritance"),
                "InheritingThreadFactory never calls setDaemon, and its thread is daemon only "
                        + "because the calling worker was. Report: " + report);
    }

    @Test
    void aFactoryThatCallsSetDaemonTrueIsNotReported() throws InterruptedException {
        ThreadFactoryDetector.ThreadFactoryReport report =
                createOnDaemonWorker(new DaemonDecidingThreadFactory(), "deciding");

        assertFalse(report.hasIssues(),
                "DaemonDecidingThreadFactory calls setDaemon(true) through a woven call site. "
                        + "Report: " + report);
    }

    @Test
    void aJdkDaemonFactoryIsNotReported() throws InterruptedException {
        ThreadFactory jdk = Thread.ofPlatform().daemon().name("jdk-daemon-worker")
                .uncaughtExceptionHandler((t, e) -> { }).factory();

        ThreadFactoryDetector.ThreadFactoryReport report = createOnDaemonWorker(jdk, "jdk");

        assertFalse(report.hasIssues(),
                "Thread.Builder decides the flag inside the JDK, which is never woven, so no "
                        + "setDaemon is recorded; a JDK factory's flag must be taken at its word. "
                        + "Report: " + report);
    }

    /** Calls the factory on a daemon thread, as a runner worker would (#479). */
    private static ThreadFactoryDetector.ThreadFactoryReport createOnDaemonWorker(
            ThreadFactory factory, String name) throws InterruptedException {
        ThreadFactoryDetector detector = new ThreadFactoryDetector();
        detector.registerFactory(factory, name);
        Thread worker = new Thread(() -> detector.recordThreadCreated(factory, name,
                factory.newThread(() -> { })), "daemon-runner-worker");
        worker.setDaemon(true);
        worker.start();
        worker.join();
        return detector.analyze();
    }
}
