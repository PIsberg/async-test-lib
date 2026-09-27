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
}
