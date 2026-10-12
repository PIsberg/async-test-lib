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

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One JPA {@code EntityManager} used by every worker, reported through the woven calls with nothing
 * recorded (#992). The handle is a {@link Proxy}, so no provider or database is needed; the weaver
 * matches the call site's owner by name. {@code PersistenceHandleSparesOnePerThreadTest} holds the
 * twin.
 */
@Tag("e2e")
class PersistenceHandleWeavingTest {

    private static AsyncFindings findings;
    private static final PersistenceHandleBean BEAN = new PersistenceHandleBean();
    private static final EntityManager SHARED = handle();

    /** A do-nothing EntityManager: every method returns null, or the identity answers Object needs. */
    static EntityManager handle() {
        return (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
                new Class<?>[] {EntityManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "EntityManager@proxy";
                    default -> null;
                });
    }

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
    void everyWorkerUsesOneHandle() {
        BEAN.findPersistAndFlush(SHARED);
    }

    @AfterAll
    static void theSharedHandleIsReported() {
        try {
            assertFalse(findings.violationsFrom("JdbcConnectionShared").isEmpty(),
                    "every worker called find, persist and flush on one EntityManager. Nothing was "
                            + "reported, so the woven calls did not reach persistenceHandleUsed: "
                            + "check PersistenceHandleWeaver.OWNERS. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
