package se.deversity.asynctest.agent;

import com.example.agentfixture.PerInstanceLockBean;
import com.example.agentfixture.SwappedLockBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code SynchronizedNonFinalDetector} fed by the agent with the instance each monitor field was
 * read from (#793). A {@code synchronized (this.lock)} block compiles to a load of the owner, the
 * field read, and the monitor entry; the agent hands the detector the owner it loaded, so one
 * instance whose lock changed is told apart from several instances with a lock each. Before #793
 * nothing reached the detector unless the body recorded by hand, so both runs were silent.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM, and the monitor instructions are woven
 * only with {@code fields=true} or a call-site substitution.
 */
@Tag("e2e")
class MonitorFieldOwnerWeavingTest {

    @BeforeAll
    static void attach() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    /** Runs {@code body} inside a fresh context and {@return the detector's report} */
    private static SynchronizedNonFinalDetector.SynchronizedNonFinalReport run(Runnable body) {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        AsyncTestContext.install(context);
        try {
            body.run();
            return AsyncTestContext.synchronizedNonFinalDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("one instance that synchronizes on its lock field before and after reassigning it is reported")
    void aReassignedInstanceLockIsReported() {
        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = run(() -> {
            SwappedLockBean bean = new SwappedLockBean();
            bean.increment();
            bean.swapLock();
            bean.increment();
        });
        assertTrue(report.hasIssues() && report.toString().contains("SwappedLockBean.lock"),
                "one SwappedLockBean entered synchronized (lock) on two different objects; a silent "
                        + "report means the woven monitor entry never named the instance. Report: "
                        + report);
    }

    @Test
    @DisplayName("several instances, each synchronizing on its own never-reassigned lock field, stay silent")
    void aLockPerInstanceIsSilent() {
        SynchronizedNonFinalDetector.SynchronizedNonFinalReport report = run(() -> {
            for (int i = 0; i < 4; i++) {
                new PerInstanceLockBean().increment();
            }
        });
        assertFalse(report.hasIssues(), "four instances each locked their own monitor once, which is "
                + "correct code. Report: " + report);
        assertTrue(report.notes().isEmpty(), "with the owner known there is nothing left undecided. "
                + "Notes: " + report.notes());
    }
}
