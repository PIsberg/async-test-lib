package se.deversity.asynctest.agent;

import com.example.agentfixture.OrderedCacheBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The silent half of {@link AccessOrderedMapWeavingTest}: gets under the read view of a
 * {@code LinkedHashMap} the agent saw built in insertion order are the correct read-write idiom and
 * stay silent (#807).
 *
 * <p>The access-ordered subclass runs here too, as the control: it shows collection weaving is live
 * in this JVM, so the silence of the other two is the order's doing.
 */
@Tag("e2e")
class AccessOrderedMapWeavingSparesInsertionOrderTest {

    private static AsyncFindings findings;

    private final OrderedCacheBean plain = OrderedCacheBean.plain(false);
    private final OrderedCacheBean subclassed = OrderedCacheBean.insertionOrdered();
    private final OrderedCacheBean control = OrderedCacheBean.lru();

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

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void plainInsertionOrderedMap() {
        plain.lookup("key");
        plain.store("key", "value");
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void insertionOrderedSubclass() {
        subclassed.lookup("key");
        subclassed.store("key", "value");
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void accessOrderedControl() {
        control.lookup("key");
        control.store("key", "value");
    }

    @AfterAll
    static void onlyTheAccessOrderedControlIsReported() {
        try {
            assertTrue(AccessOrderedMapWeavingTest.namesCollection(findings,
                            "OrderedCacheBean$LruMap"),
                    "the control is the access-ordered map; without its finding the silence below "
                            + "proves nothing. Findings were: " + findings.violations());
            assertFalse(AccessOrderedMapWeavingTest.namesCollection(findings,
                            "java.util.LinkedHashMap"),
                    "new LinkedHashMap<>(16, 0.75f, false) is insertion-ordered: its get only "
                            + "reads, so the read view guards it beside puts under the write view. "
                            + "Findings were: " + findings.violations());
            assertFalse(AccessOrderedMapWeavingTest.namesCollection(findings,
                            "OrderedCacheBean$InsertionMap"),
                    "super(16, 0.75f, false) builds an insertion-ordered subclass, the same "
                            + "correct idiom. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
