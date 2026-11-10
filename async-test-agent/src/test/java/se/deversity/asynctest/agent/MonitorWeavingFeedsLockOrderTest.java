package se.deversity.asynctest.agent;

import com.example.agentfixture.MonitorOrderBean;
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
 * Pins that a lock-order inversion on {@code synchronized} reaches {@code LockOrderValidator} with
 * no instrumentation (#1243), and that the woven feed stays silent where no deadlock is possible.
 *
 * <p>Before #1243 the woven {@code MONITORENTER}/{@code MONITOREXIT} and synchronized-method events
 * fed the controlled schedule and the lockset and stopped there, so nested {@code synchronized}
 * taken both ways round drew nothing, while the same shape on two {@code ReentrantLock}s was
 * reported at {@code VERDICT}.
 *
 * <p>The two sides run one after the other, each on its own thread inside one test context, as
 * runner workers would: the validator reports the order it observed, so the threads need not
 * overlap, and they must not, because overlapping they could really deadlock.
 */
@Tag("e2e")
class MonitorWeavingFeedsLockOrderTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
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

    /** Runs each side on its own thread inside one context, in turn, and returns the report. */
    @SafeVarargs
    private static LockOrderValidator.LockOrderReport run(MonitorOrderBean bean,
                                                         Consumer<MonitorOrderBean>... sides)
            throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
        for (Consumer<MonitorOrderBean> side : sides) {
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
    @DisplayName("synchronized blocks nested both ways round by two threads are an inversion")
    void anInversionOnSynchronizedBlocksIsReported() throws InterruptedException {
        LockOrderValidator.LockOrderReport report =
                run(new MonitorOrderBean(), MonitorOrderBean::forward, MonitorOrderBean::reverse);
        assertEquals(1, report.inconsistentOrderings.size(),
                "Two threads nested alpha and beta in opposite orders through woven synchronized "
                        + "blocks. A silent report means TelemetryRegistry.monitorEntered no longer "
                        + "reaches AgentMonitorHooks.lockOrderMonitorEntered. Report: " + report);
    }

    @Test
    @DisplayName("synchronized blocks nested in one order on every thread are silent")
    void oneOrderOnSynchronizedBlocksIsSilent() throws InterruptedException {
        LockOrderValidator.LockOrderReport report =
                run(new MonitorOrderBean(), MonitorOrderBean::forward, MonitorOrderBean::forward);
        assertFalse(report.hasIssues(), "one order cannot deadlock: " + report);
    }

    @Test
    @DisplayName("both orders under one synchronized gate are silent")
    void bothOrdersUnderOneSynchronizedGateAreSilent() throws InterruptedException {
        LockOrderValidator.LockOrderReport report = run(new MonitorOrderBean(),
                MonitorOrderBean::gatedForward, MonitorOrderBean::gatedReverse);
        assertFalse(report.hasIssues(),
                "whoever holds the gate holds the pair, so the two nestings are never in "
                        + "progress at once (#1242): " + report);
    }

    @Test
    @DisplayName("an inversion behind a re-entered synchronized method is reported")
    void anInversionBehindAReenteredSynchronizedMethodIsReported() throws InterruptedException {
        LockOrderValidator.LockOrderReport report = run(new MonitorOrderBean(),
                MonitorOrderBean::outerThenAlpha, MonitorOrderBean::alphaThenOuter);
        assertEquals(1, report.inconsistentOrderings.size(),
                "outerThenAlpha still holds the bean's monitor once after inner() returned, so it "
                        + "nests alpha inside the bean, the reverse of alphaThenOuter (#1244). "
                        + "Report: " + report);
    }
}
