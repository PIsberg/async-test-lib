package se.deversity.asynctest.agent;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attaches the <em>packaged</em> agent jar to a fresh JVM the way the docs tell a
 * consumer to ({@code -javaagent:async-test-agent-<version>.jar}) and requires the
 * JVM to reach {@code main}.
 *
 * <p>The child classpath is this JVM's classpath with every Byte Buddy jar removed,
 * because that is what a consumer's test JVM looks like: {@code async-test-lib}
 * present, Byte Buddy absent (the library module is forbidden from carrying it).
 * The agent jar must therefore bundle its own relocated copy — an unshaded jar
 * fails premain method resolution with {@code NoClassDefFoundError:
 * net/bytebuddy/matcher/ElementMatcher}, which the JVM escalates to a fatal
 * startup abort. That is exactly the failure this test exists to catch: it shipped
 * unnoticed through eight release candidates because no gate ever attached the
 * packaged jar standalone.
 *
 * <p>Runs under Maven Failsafe only (needs the packaged jar, so it must run after
 * {@code package}). Failsafe is configured with {@code useManifestOnlyJar=false}
 * so {@code java.class.path} holds the real entries — a manifest-only booter jar
 * would smuggle Byte Buddy past the filter below. The Gradle build excludes
 * {@code *IT} from its test task.
 */
class AgentJarPremainIT {

    @Test
    void packagedJarPremainMustNotAbortJvmStartup() throws Exception {
        assertChildJvmCompletes(List.of("-javaagent:" + packagedAgentJar()),
                PremainChildMain.class.getName(), PremainChildMain.MARKER);
    }

    /**
     * The second documented attach mode. Surefire's {@code SelfAttachTest} covers the
     * logic pre-shade; this scenario re-runs it against the packaged jar, where
     * {@code selfAttach()} must reach the relocated {@code byte-buddy-agent} bundled
     * inside — the published pom no longer declares Byte Buddy at all.
     */
    @Test
    void packagedJarSelfAttachMustSucceedWithoutByteBuddyOnClasspath() throws Exception {
        packagedAgentJar(); // fail fast with the clearer message if the jar is absent
        assertChildJvmCompletes(List.of("-Djdk.attach.allowAttachSelf=true"),
                SelfAttachChildMain.class.getName(), SelfAttachChildMain.MARKER);
    }

    private static String packagedAgentJar() {
        String agentJar = System.getProperty("agent.jar");
        assertNotNull(agentJar, "agent.jar system property not set — run via Maven Failsafe");
        assertTrue(new File(agentJar).isFile(), "packaged agent jar missing: " + agentJar);
        return agentJar;
    }

    private static void assertChildJvmCompletes(List<String> jvmFlags, String mainClass, String marker)
            throws Exception {
        // Failsafe substitutes the packaged (shaded) jar for target/classes on this
        // classpath, so the child JVM runs the artifact consumers actually get — the
        // premain scenario proved that substitution: with target/classes present, the
        // unshaded AsyncTestAgent would have been found first and aborted the child.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvmFlags);
        command.addAll(List.of("-Dlicense.mock.mode=true", "-cp", classpathWithoutByteBuddy(), mainClass));

        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child JVM did not exit within 60s:\n" + output);

        assertEquals(0, child.exitValue(),
                "child JVM failed (" + mainClass + ") with the packaged agent jar:\n" + output);
        assertTrue(output.contains(marker),
                "child JVM exited 0 but never printed its marker:\n" + output);
    }

    /**
     * This JVM's classpath minus every Byte Buddy entry. Matching on the Maven
     * artifact directory names ({@code byte-buddy}, {@code byte-buddy-agent})
     * keeps async-test-lib, JUnit and the test classes while guaranteeing the
     * child can only get Byte Buddy from inside the agent jar itself.
     */
    private static String classpathWithoutByteBuddy() {
        List<String> kept = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.replace(File.separatorChar, '/').contains("byte-buddy")) {
                kept.add(entry);
            }
        }
        return String.join(File.pathSeparator, kept);
    }
}
