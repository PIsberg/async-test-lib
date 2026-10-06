package se.deversity.asynctest.agent;

import com.example.agentfixture.OrderedCacheBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LRU gets under one read lock are reported on a default JVM once the agent weaves the map's
 * three-argument constructor (#807).
 *
 * <p>Before, the map's order could only be read with {@code java.util} opened to the library, which
 * this JVM does not do, so every {@code LinkedHashMap} get counted as a read and the read view
 * guarded it. {@code AccessOrderedMapReadLockTest} covers the opened JVM;
 * {@link AccessOrderedMapWeavingSparesInsertionOrderTest} is the silent half of this one.
 */
@Tag("e2e")
class AccessOrderedMapWeavingTest {

    private static AsyncFindings findings;

    private final OrderedCacheBean plain = OrderedCacheBean.plain(true);
    private final OrderedCacheBean subclassed = OrderedCacheBean.lru();

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
        assertFalse(LinkedHashMap.class.getModule().isOpen("java.util",
                        AsyncFindings.class.getModule()),
                "this test is about the default JVM, which does not open java.util to the library");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void plainAccessOrderedMap() {
        plain.lookup("key");
        plain.store("key", "value");
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void accessOrderedSubclass() {
        subclassed.lookup("key");
        subclassed.store("key", "value");
    }

    static boolean namesCollection(AsyncFindings findings, String type) {
        return findings.violations().stream()
                .filter(v -> v.detector().contains("SharedCollection"))
                .anyMatch(v -> String.valueOf(v.attributes().get("report")).contains(type));
    }

    @AfterAll
    static void bothAccessOrderedShapesAreReported() {
        try {
            assertTrue(namesCollection(findings, "java.util.LinkedHashMap"),
                    "new LinkedHashMap<>(16, 0.75f, accessOrder) with accessOrder true relinks the "
                            + "entry on every get, so four threads' gets under the read view write "
                            + "the map at once. Silence means the woven constructor no longer tells "
                            + "SelfGuard the order. Findings were: " + findings.violations());
            assertTrue(namesCollection(findings, "OrderedCacheBean$LruMap"),
                    "the LRU subclass sets access order through its super(16, 0.75f, true) call, "
                            + "which the agent weaves in the subclass's constructor. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
