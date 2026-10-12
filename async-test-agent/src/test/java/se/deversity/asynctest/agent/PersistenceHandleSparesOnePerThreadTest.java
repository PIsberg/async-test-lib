package se.deversity.asynctest.agent;

import com.example.agentfixture.PersistenceHandleBean;
import jakarta.persistence.EntityManager;
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
 * The twin of {@code PersistenceHandleWeavingTest} (#992): the same calls, on an
 * {@code EntityManager} each worker thread has to itself, the documented way to use one.
 */
@Tag("e2e")
class PersistenceHandleSparesOnePerThreadTest {

    private static AsyncFindings findings;
    private static final PersistenceHandleBean BEAN = new PersistenceHandleBean();
    private static final ThreadLocal<EntityManager> PER_THREAD = ThreadLocal.withInitial(
            PersistenceHandleWeavingTest::handle);

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

    @AsyncTest(threads = 4, invocations = 2, includes = DetectorType.JDBC_CONNECTION_SHARED)
    void eachWorkerUsesItsOwnHandle() {
        BEAN.findPersistAndFlush(PER_THREAD.get());
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertTrue(findings.violationsFrom("JdbcConnectionShared").isEmpty(),
                    "each worker used its own EntityManager: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
