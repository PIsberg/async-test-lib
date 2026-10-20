package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * When the runner attaches the agent, and with what arguments.
 *
 * <p>The README's first example, a bare {@code counter++}, reported nothing unless the user also
 * passed {@code -Dasynctest.agent=fields=true}: adding the agent artifact was not enough. These
 * pin the rule that replaced it. The agent artifact on the test classpath is the opt-in, the
 * property overrides it verbatim, {@code off} opts out, and an agent that is already running
 * (attached with {@code -javaagent:} or a {@code selfAttach} in setup) is never attached again.
 */
@DisplayName("AgentAutoAttach plan")
class AgentAutoAttachTest {

    @Test
    void agentOnTheClasspath_attachesWithFieldsAndCollections_scopedToTheTestsProject() {
        AgentAutoAttach.Plan plan = AgentAutoAttach.plan(null, false, true, "com.acme.shop.order");

        assertEquals("fields=true,collections=true,includes=com.acme.shop", plan.args());
        assertEquals("classpath", plan.source());
    }

    @Test
    void aShortPackage_isIncludedWhole() {
        assertEquals("fields=true,collections=true,includes=com.myapp",
                AgentAutoAttach.plan(null, false, true, "com.myapp").args());
        assertEquals("fields=true,collections=true,includes=myapp",
                AgentAutoAttach.plan(null, false, true, "myapp").args());
    }

    @Test
    void noAgentOnTheClasspath_andNoProperty_attachesNothing() {
        assertNull(AgentAutoAttach.plan(null, false, false, "com.acme.shop"));
    }

    @Test
    void aTestInTheDefaultPackage_orWithNoKnownClass_isNotAttachedByDefault() {
        // Without a package there is no include to infer, and with no include the agent weaves
        // every class it loads, JUnit and the build tool's included. The property still works.
        assertNull(AgentAutoAttach.plan(null, false, true, ""));
        assertNull(AgentAutoAttach.plan(null, false, true, null));
    }

    @Test
    void aBodyRunThroughAsyncTestRunner_isNotAttachedByDefault() {
        // AsyncTestRunner's invocation names its own BodyHolder, in the library's package, which
        // the agent never weaves: an attach there would cost a retransformation and observe nothing.
        assertNull(AgentAutoAttach.plan(null, false, true, "se.deversity.asynctest"));
        assertNull(AgentAutoAttach.plan(null, false, true, "se.deversity.asynctest.runner"));
    }

    @Test
    void theOptOut_attachesNothing_evenWithTheAgentOnTheClasspath() {
        for (String property : new String[] {"off", "OFF", "false", " Off "}) {
            assertNull(AgentAutoAttach.plan(property, false, true, "com.acme.shop"), property);
        }
    }

    @Test
    void anExplicitProperty_isPassedThroughVerbatim() {
        AgentAutoAttach.Plan plan = AgentAutoAttach.plan("includes=com.other,fields=true", false, true,
                "com.acme.shop");

        assertEquals("includes=com.other,fields=true", plan.args());
        assertEquals("property", plan.source());
    }

    @Test
    void anExplicitProperty_isAttemptedEvenWhenTheAgentLooksAbsent_soTheMissingArtifactIsReported() {
        AgentAutoAttach.Plan plan = AgentAutoAttach.plan("fields=true", false, false, "com.acme.shop");

        assertEquals("fields=true", plan.args());
    }

    @Test
    void anEmptyProperty_attachesWithTheAgentsOwnDefaults() {
        assertEquals("", AgentAutoAttach.plan("", false, true, "com.acme.shop").args());
    }

    @Test
    void anAgentAlreadyRunning_isNeverAttachedAgain() {
        assertNull(AgentAutoAttach.plan(null, true, true, "com.acme.shop"));
        assertNull(AgentAutoAttach.plan("fields=true", true, true, "com.acme.shop"));
    }
}
