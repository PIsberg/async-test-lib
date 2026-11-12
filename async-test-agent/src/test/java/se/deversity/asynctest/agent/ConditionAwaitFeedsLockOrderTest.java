package se.deversity.asynctest.agent;

import com.example.agentfixture.ConditionOrderBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.LockOrderValidator;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a woven {@code Condition.await} lets its lock go for {@code LockOrderValidator} and
 * takes it back nested inside whatever else the thread holds (#1256), as a woven
 * {@code Object.wait} has since #1243.
 *
 * <p>Before #1256 the validator kept the condition's lock held through the await, so a thread
 * that took it back while holding another lock recorded no edge for it, and the deadlock in
 * {@link ConditionOrderBean#awaitInsideOther()} drew nothing.
 *
 * <p>The two sides run one after the other, each on its own thread inside one test context, as
 * in {@code MonitorWeavingFeedsLockOrderTest}: overlapping, they could really deadlock.
 */
@Tag("e2e")
class ConditionAwaitFeedsLockOrderTest {

    @BeforeAll
    static void attachWithLockWeaving() {
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
    }

    /** Runs each side on its own thread inside one context, in turn, and returns the report. */
    @SafeVarargs
    private static LockOrderValidator.LockOrderReport run(ConditionOrderBean bean,
                                                         Consumer<ConditionOrderBean>... sides)
            throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        for (Consumer<ConditionOrderBean> side : sides) {
            Thread thread = new Thread(() -> {
                AsyncTestContext.install(context);
                try {
                    side.accept(bean);
                } finally {
                    AsyncTestContext.uninstall();
                }
            });
            thread.start();
            thread.join();
        }
        AsyncTestContext.install(context);
        try {
            return AsyncTestContext.lockOrderValidator().validateLockOrder();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("an await that takes its lock back inside another lock is an inversion")
    void anAwaitTakingItsLockBackInsideAnotherIsReported() throws InterruptedException {
        ConditionOrderBean bean = new ConditionOrderBean();
        LockOrderValidator.LockOrderReport report =
                run(bean, ConditionOrderBean::awaitInsideOther, ConditionOrderBean::awaitInsideOther);
        assertEquals(1, report.inconsistentOrderings.size(),
                "Each thread nested inner inside outer, then took outer back inside inner when "
                        + "its await ended. A silent report means AgentMonitorHooks.conditionAwait* "
                        + "no longer hands the condition's lock to the validator. Report: " + report);
    }

    @Test
    @DisplayName("an await on the innermost lock keeps one order and is silent")
    void anAwaitOnTheInnermostLockIsSilent() throws InterruptedException {
        ConditionOrderBean bean = new ConditionOrderBean();
        LockOrderValidator.LockOrderReport report =
                run(bean, ConditionOrderBean::awaitInnermost, ConditionOrderBean::awaitInnermost);
        assertFalse(report.hasIssues(),
                "inner is taken back inside outer, the order it was first taken in: " + report);
    }
}
