package se.deversity.asynctest.agent;

import com.example.agentfixture.ThreadJoinBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a woven {@code Thread.join} orders, judged end to end: weaver, thread hooks,
 * happens-before model and {@code AtomicityValidator} (#743).
 *
 * <p>The table-resolution test proves each {@code join} overload is matched; only an attached
 * agent proves the match orders anything. Each case has a child write a field that its parent
 * reads. After a join that returned, the write is ordered before the read and the pair stays
 * silent; the same read made before the join, with the two sides paired through latches in this
 * unwoven class, keeps its finding.
 *
 * <p>The bridge forwards every thread's events, so what is measured is the join edge and not which
 * threads the bridge attributes. Only the fixture is woven, so nothing else publishes.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class ThreadJoinWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** A fixture call that may throw. */
    private interface Call {
        int run() throws Exception;
    }

    /** Runs {@code call} with every thread's events forwarded, and returns the validator's findings. */
    private static List<String> findings(Call call) throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, id -> true)) {
            assertEquals(42, call.run(), "the parent must have read the child's write");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static boolean mentionsTheBox(List<String> findings) {
        return findings.stream().anyMatch(f -> f.contains("Box.value"));
    }

    /** Asserts the child's write, read after the join {@code overload} names, is ordered. */
    private static void assertOrderedByJoin(int overload, String join) throws Exception {
        ThreadJoinBean bean = new ThreadJoinBean();
        List<String> findings = findings(() -> bean.readAfterJoin(42, overload));

        assertFalse(mentionsTheBox(findings),
                "The child wrote the box and finished; the parent read it after " + join
                        + " returned, which the Java memory model orders after everything the "
                        + "child did. A finding here means the woven join fed no edge. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("a child's write read after join() is ordered")
    void readAfterJoinIsSilent() throws Exception {
        assertOrderedByJoin(0, "join()");
    }

    @Test
    @DisplayName("a child's write read after join(long) is ordered")
    void readAfterTimedJoinIsSilent() throws Exception {
        assertOrderedByJoin(1, "join(long)");
    }

    @Test
    @DisplayName("a child's write read after join(long, int) is ordered")
    void readAfterNanoTimedJoinIsSilent() throws Exception {
        assertOrderedByJoin(2, "join(long, int)");
    }

    @Test
    @DisplayName("a child's write read after join(Duration) is ordered")
    void readAfterDurationJoinIsSilent() throws Exception {
        assertOrderedByJoin(3, "join(Duration)");
    }

    @Test
    @DisplayName("the same write read before the join keeps its finding")
    void readBeforeTheJoinIsReported() throws Exception {
        ThreadJoinBean bean = new ThreadJoinBean();
        CountDownLatch written = new CountDownLatch(1);
        List<String> findings = findings(() -> bean.readBeforeJoin(42, written::countDown, () -> {
            try {
                assertTrue(written.await(10, TimeUnit.SECONDS), "the child never wrote");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));

        assertTrue(mentionsTheBox(findings),
                "The parent read the box after a latch the agent does not see and before the join, "
                        + "so nothing it knows orders the child's write before that read. Findings "
                        + "were: " + findings);
    }
}
