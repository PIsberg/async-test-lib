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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A synchronized set the run did not see being made, iterated without its lock, reported through
 * the woven {@code iterator()} with nothing recorded (#996, item 2).
 *
 * <p>The set is built here, in code the agent does not weave, so the run cannot know whether it is
 * a plain {@code synchronizedSet} or a map's view guarded by the map. The detector used to skip such
 * a set; it now reads the wrapper's own mutex, which the agent opens {@code java.util} to the
 * library to allow. {@code SynchronizedSetIterationSparesTheMutexTest} holds the silent twins.
 */
@Tag("e2e")
class SynchronizedSetIterationWeavingTest {

    private static AsyncFindings findings;
    private static final SynchronizedIterationBean BEAN = new SynchronizedIterationBean();
    private static final Set<String> SET = Collections.synchronizedSet(new HashSet<>(Set.of("a", "b")));

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
    void iteratesWithoutTheLock() {
        BEAN.iterate(SET);
    }

    @AfterAll
    static void theIterationIsReported() {
        try {
            assertFalse(findings.violationsFrom("SynchronizedCollectionIteration").isEmpty(),
                    "SynchronizedIterationBean.iterate walks a synchronizedSet without holding it. "
                            + "Nothing was reported, so the detector still skips a synchronized set "
                            + "of unknown origin, or could not read its mutex. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }
}
