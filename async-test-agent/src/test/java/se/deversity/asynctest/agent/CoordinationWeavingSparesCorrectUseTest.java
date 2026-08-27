package se.deversity.asynctest.agent;

import com.example.agentfixture.CorrectCoordinationBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The negative half of {@link CoordinationWeavingTest}, and the one that gates the table.
 *
 * <p>{@link CorrectCoordinationBean} releases every permit in a {@code finally} and offers to an
 * unbounded queue, so no permit leaks and no offer can fail. It runs through exactly the same woven
 * call sites, so what the detectors must distinguish is the protocol rather than the instruction.
 *
 * <p>This matters more here than for the shared-instance family. Correct coordination is the
 * overwhelming majority of coordination: nearly every use of a semaphore or a queue in a real
 * codebase is fine, so a detector that reports the mere presence of one would bury its user
 * immediately.
 */
@Tag("e2e")
class CoordinationWeavingSparesCorrectUseTest {

    private static AsyncFindings findings;

    private final CorrectCoordinationBean correct = new CorrectCoordinationBean();

    @BeforeAll
    static void attachWithConcurrencyWeaving() {
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

    @AsyncTest(threads = 4, invocations = 25)
    void everyPermitReturnedAndEveryOfferAccepted() throws InterruptedException {
        correct.useAPermit();
        correct.enqueue("element");
    }

    @AfterAll
    static void correctUseIsNotReported() {
        try {
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("Semaphore")
                                    || v.detector().contains("BlockingQueue")),
                    "CorrectCoordinationBean releases every permit in a finally and offers to an "
                            + "unbounded queue, so nothing leaks and no offer fails. A finding "
                            + "here is a false positive on correct coordination, which is nearly "
                            + "all coordination - the substitution would bury its user on the "
                            + "first real codebase. Findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
