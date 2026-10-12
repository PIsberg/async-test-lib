package se.deversity.asynctest.agent;

import com.example.agentfixture.SynchronizedIterationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The silent twins of {@code SynchronizedSetIterationWeavingTest} (#996, item 2): a synchronized
 * set iterated under its own monitor, and a synchronized map's key set, made in code the agent does
 * not weave, iterated under the map's monitor. The second is why sets of unknown origin used to be
 * skipped: judged against itself, the view would read as unguarded.
 */
@Tag("e2e")
class SynchronizedSetIterationSparesTheMutexTest {

    private static AsyncFindings findings;
    private static final SynchronizedIterationBean BEAN = new SynchronizedIterationBean();
    private static final Set<String> SET = Collections.synchronizedSet(new HashSet<>(Set.of("a", "b")));
    private static final Map<String, String> MAP =
            Collections.synchronizedMap(new HashMap<>(Map.of("a", "1", "b", "2")));
    /** Taken here, unwoven, so the run never sees the view come out of the map. */
    private static final Set<String> MAP_KEYS = MAP.keySet();

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

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.SYNCHRONIZED_COLLECTION_ITERATION)
    void iteratesTheSetHoldingIt() {
        BEAN.iterateHolding(SET, SET);
    }

    @AsyncTest(threads = 2, invocations = 2, includes = DetectorType.SYNCHRONIZED_COLLECTION_ITERATION)
    void iteratesTheMapViewHoldingTheMap() {
        BEAN.iterateHolding(MAP, MAP_KEYS);
    }

    @AfterAll
    static void nothingIsReported() {
        try {
            assertTrue(findings.violationsFrom("SynchronizedCollectionIteration").isEmpty(),
                    "both iterations hold the monitor the wrapper locks on: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
