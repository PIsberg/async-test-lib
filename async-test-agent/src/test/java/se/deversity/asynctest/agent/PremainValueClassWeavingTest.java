package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The agent installed as {@code -javaagent:} does, through {@code premain}, weaves a class in the shape kotlinc gives a generic value class ({@link ValueClassShape}).
 *
 * <p>Its own class, since the agent installs at most once per JVM; {@code reuseForks=false} gives it
 * its own fork.
 */
@Tag("e2e")
class PremainValueClassWeavingTest {

    @Test
    @DisplayName("a class whose static method names the class's type variable is woven (#1467)")
    void aValueClassIsWoven() throws Exception {
        Instrumentation inst;
        try {
            inst = ByteBuddyAgent.install();
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            inst = null;
        }
        assumeTrue(inst != null, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        AsyncTestAgent.premain("includes=" + ValueClassShape.PACKAGE + ",fields=true,collections=true,debug=true", inst);

        String agentOutput = ValueClassShape.loadAndCaptureAgentOutput();

        assertFalse(agentOutput.contains("Failed to instrument " + ValueClassShape.NAME),
                "the agent could not describe the class and left it unwoven:\n" + agentOutput);
        assertTrue(agentOutput.contains("Instrumented " + ValueClassShape.NAME),
                "the class was not woven:\n" + agentOutput);
    }
}
