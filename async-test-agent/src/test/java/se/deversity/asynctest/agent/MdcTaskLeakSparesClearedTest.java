package se.deversity.asynctest.agent;

import com.example.agentfixture.MdcTaskBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.NOPMDCAdapter;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The twin of {@code MdcTaskLeakWeavingTest} (#995): the same pooled task, clearing the MDC in
 * {@code finally}, is not reported.
 */
@Tag("e2e")
class MdcTaskLeakSparesClearedTest {

    private static AsyncFindings findings;
    private static MdcTaskBean bean;

    @BeforeAll
    static void attachWithCollectionWeaving() throws ReflectiveOperationException {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        giveTheMdcAMemory();
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
        bean = new MdcTaskBean();
    }

    /**
     * The Maven test class path carries no SLF4J provider, so MDC falls back to a no-op adapter that
     * remembers nothing. slf4j-api's own BasicMDCAdapter, installed in its place for this JVM, makes
     * MDC behave as under a real backend; the Gradle build's logback needs nothing.
     */
    static void giveTheMdcAMemory() throws ReflectiveOperationException {
        if (MDC.getMDCAdapter() instanceof NOPMDCAdapter) {
            Field adapter = MDC.class.getDeclaredField("MDC_ADAPTER");
            adapter.setAccessible(true);
            adapter.set(null, new BasicMDCAdapter());
        }
    }

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.MDC_CONTEXT_LEAK)
    void clearInFinally() throws Exception {
        bean.clearInFinally();
    }

    @AfterAll
    static void nothingIsReported() throws InterruptedException {
        try {
            assertTrue(findings.violationsFrom("MdcContextLeak").isEmpty(),
                    "each task cleared its MDC: " + findings.violations());
        } finally {
            findings.close();
            bean.close();
        }
    }
}
