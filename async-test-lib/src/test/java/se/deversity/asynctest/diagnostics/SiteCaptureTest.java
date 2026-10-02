package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentCollectionHooks;
import se.deversity.asynctest.AgentConcurrencyUtilHooks;
import se.deversity.asynctest.AgentGcHooks;
import se.deversity.asynctest.AgentLockHooks;
import se.deversity.asynctest.AgentMonitorHooks;
import se.deversity.asynctest.AgentSharedInstanceHooks;
import se.deversity.asynctest.AgentSleepHooks;
import se.deversity.asynctest.AgentThreadHooks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which frames {@link SiteCapture} passes over on its way to the user's line.
 *
 * <p>With the agent attached, a woven call site reaches a detector through one of the library's
 * root-package {@code Agent*Hooks} classes, so the frame directly above the detector is library
 * code the reader cannot change (#853). The other direction matters as much: the library's own
 * tests live in the same package, and a rule that skipped them would leave a site test with no
 * user frame to name.
 */
class SiteCaptureTest {

    @Test
    void everyAgentHookClassIsSkipped() {
        for (Class<?> hooks : List.of(AgentCollectionHooks.class, AgentConcurrencyUtilHooks.class,
                AgentGcHooks.class, AgentLockHooks.class, AgentMonitorHooks.class,
                AgentSharedInstanceHooks.class, AgentSleepHooks.class, AgentThreadHooks.class)) {
            assertTrue(SiteCapture.isFrameworkClass(hooks.getName()),
                    hooks.getName() + " is the library's hook, never the user's access site");
        }
    }

    @Test
    void aClassNestedInAnAgentHookIsSkipped() {
        // HandedTask wraps the task a body submits to an executor; its run() sits under the task.
        assertTrue(SiteCapture.isFrameworkClass("se.deversity.asynctest.AgentConcurrencyUtilHooks$HandedTask"),
                "a nested class of a hook is the library's code too");
    }

    @Test
    void theLibrarysOwnTestsInTheRootPackageStayUserFrames() {
        assertFalse(SiteCapture.isFrameworkClass("se.deversity.asynctest.AgentSharedInstanceHooksTest"),
                "a test of a hook is where its site test calls from");
        assertFalse(SiteCapture.isFrameworkClass("se.deversity.asynctest.AgentSharedInstanceHooksTest$1"),
                "nor is a class nested in that test");
        assertFalse(SiteCapture.isFrameworkClass("se.deversity.asynctest.AgentContextFixture"),
                "an Agent-prefixed fixture that is not a hook stays a user frame");
    }

    @Test
    void aUserClassNamedHooksIsNotSkipped() {
        assertFalse(SiteCapture.isFrameworkClass("com.acme.WebHooks"),
                "the rule is the library's root package, not any class ending in Hooks");
        assertFalse(SiteCapture.isFrameworkClass("com.acme.AgentHooks"),
                "nor an Agent*Hooks name outside the library");
        assertFalse(SiteCapture.isFrameworkClass("se.deversity.asynctest.sub.AgentFooHooks"),
                "nor one in a sub-package of the library's root");
    }

    @Test
    void aClassNestedInADetectorIsSkipped() {
        // #858: ThreadLeakDetector$ThreadState captures the creation stack, so its frame sits on top.
        assertTrue(SiteCapture.isFrameworkClass(ThreadLeakDetector.class.getName() + "$ThreadState"),
                "a detector's nested class is the detector, not the user");
        assertTrue(SiteCapture.isFrameworkClass("com.acme.QueueMonitor$Entry$1"),
                "however deep the nesting");
        assertFalse(SiteCapture.isFrameworkClass("com.acme.OrderService$Detector2"),
                "a nested name is judged by its suffix, and Detector2 does not end in Detector");
        assertFalse(SiteCapture.isFrameworkClass("com.acme.Detector.OrderService"),
                "a suffix in the package name is not the class's");
    }

    @Test
    void userFramesStartAtTheFirstUserFrameAndStopAtTheLimit() {
        StackTraceElement[] trace = {
                frame("java.lang.Thread", "getStackTrace"),
                frame("se.deversity.asynctest.diagnostics.SleepInLockDetector", "recordHolding"),
                frame("se.deversity.asynctest.AgentSleepHooks", "recordHeld"),
                frame("se.deversity.asynctest.AgentSleepHooks", "sleepHoldingMonitor"),
                frame("com.acme.Cache", "refresh"),
                frame("com.acme.Cache", "get"),
                frame("com.acme.Service", "handle"),
        };
        assertEquals(List.of(trace[4], trace[5]), SiteCapture.userFrames(trace, 2),
                "the hook and the detector are skipped, whatever their number, and max bounds the rest");
        assertEquals(trace[4], SiteCapture.firstUserFrame(trace));
        assertEquals(List.of(), SiteCapture.userFrames(null, 4), "no stack, no frames");
        assertEquals(List.of(), SiteCapture.userFrames(new StackTraceElement[] {trace[0], trace[1]}, 4),
                "a stack of framework frames only has no user frame");
    }

    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 1);
    }
}
