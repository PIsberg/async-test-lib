package se.deversity.asynctest.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link AsyncTestAgent#selfAttach(String)} says when the JVM refuses to load an agent at
 * runtime, which is what JEP 451 says a future JDK will do by default.
 *
 * <p>The message used to say self-attachment was disabled and to set
 * {@code -Djdk.attach.allowAttachSelf=true}. In a JVM started with
 * {@code -XX:-EnableDynamicAgentLoading} that flag changes nothing: Byte Buddy attaches from a
 * helper process when self-attach is off, and it is the dynamic load itself that is refused. A
 * reader following the old advice stayed uninstrumented. The child JVM here reproduces the refusal
 * on today's JDKs and prints the message.
 */
@Tag("e2e")
class SelfAttachRefusedTest {

    /** Runs in the child: tries the attach and prints what it was told. */
    public static final class Child {
        public static void main(String[] args) {
            try {
                AsyncTestAgent.selfAttach("includes=nothing.here");
                System.out.println("ATTACHED");
            } catch (IllegalStateException e) {
                System.out.println("REFUSED: " + e.getMessage());
            }
        }
    }

    @Test
    void aRefusedRuntimeLoadNamesTheRealSwitchAndTheStartupRoute() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(List.of(java, "-XX:-EnableDynamicAgentLoading",
                "-cp", System.getProperty("java.class.path"), Child.class.getName()))
                .redirectErrorStream(true).start();
        String output;
        try {
            assertTrue(child.waitFor(120, TimeUnit.SECONDS), "the child JVM did not finish");
            output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            child.destroyForcibly();
            throw e;
        }

        assertTrue(output.contains("REFUSED: "), "the child should have been refused: " + output);
        assertTrue(output.contains("EnableDynamicAgentLoading"),
                "the refusal must name the switch that refused it, not allowAttachSelf: " + output);
        assertTrue(output.contains("-javaagent:"),
                "the refusal must name the startup route that keeps working: " + output);
    }
}
