package se.deversity.asynctest.agent;

/**
 * Child entry point for {@link AgentJarPremainIT}'s self-attach scenario. Launched
 * in a fresh JVM whose classpath holds the packaged (shaded) agent jar and no Byte
 * Buddy; {@code selfAttach()} must find everything it needs — including the
 * relocated {@code byte-buddy-agent} — inside the jar.
 *
 * <p>Not a test class — no JUnit annotations — so Surefire, Failsafe and Gradle
 * all ignore it during discovery.
 */
public final class SelfAttachChildMain {

    /** Printed on stdout after selfAttach() returns without throwing. */
    static final String MARKER = "self-attach-succeeded";

    private SelfAttachChildMain() {}

    public static void main(String[] args) {
        AsyncTestAgent.selfAttach();
        System.out.println(MARKER);
    }
}
