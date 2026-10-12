package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadLocalTaskBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A request context left on a pooled thread and read by the next task there, reported through the
 * woven {@code ThreadLocal} calls and {@code submit} with nothing recorded (#995). The twins are
 * {@code ThreadLocalTaskLeakSparesRemovalTest} and {@code ThreadLocalTaskLeakSparesCacheTest}.
 */
@Tag("e2e")
class ThreadLocalTaskLeakWeavingTest {

    private static AsyncFindings findings;
    private static ThreadLocalTaskBean bean;

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
        bean = new ThreadLocalTaskBean();
    }

    @AsyncTest(threads = 1, invocations = 2, includes = DetectorType.THREAD_LOCAL_LEAKS)
    void leaveTheUserBehind() throws Exception {
        bean.leaveTheUserBehind();
    }

    @AfterAll
    static void theStaleReadIsReported() throws InterruptedException {
        try {
            assertFalse(findings.violationsFrom("ThreadLocalMonitor").stream()
                            .noneMatch(v -> v.toString().contains("earlier task")),
                    "a pooled task read a user an earlier task on its thread set and never "
                            + "removed. Nothing named it, so the woven ThreadLocal calls or the task "
                            + "boundaries did not reach ThreadLocalMonitor. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
            bean.close();
        }
    }
}
