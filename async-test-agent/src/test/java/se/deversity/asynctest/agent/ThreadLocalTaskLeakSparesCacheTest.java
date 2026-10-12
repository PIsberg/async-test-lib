package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadLocalTaskBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A per-thread buffer set once and reused by later tasks, the cache idiom, is not contamination
 * (#995): only a thread-local two tasks on one thread both set is per-task state.
 */
@Tag("e2e")
class ThreadLocalTaskLeakSparesCacheTest {

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
    void reuseAPerThreadBuffer() throws Exception {
        bean.reuseAPerThreadBuffer();
    }

    @AfterAll
    static void theCacheIsNotReported() throws InterruptedException {
        try {
            assertTrue(findings.violationsFrom("ThreadLocalMonitor").stream()
                            .noneMatch(v -> v.toString().contains("earlier task")),
                    "a buffer set once per thread is a cache, not a leaked context: " + findings.violations());
        } finally {
            findings.close();
            bean.close();
        }
    }
}
