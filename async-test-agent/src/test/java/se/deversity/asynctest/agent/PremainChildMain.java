package se.deversity.asynctest.agent;

/**
 * Child entry point for {@link AgentJarPremainIT}. Launched in a fresh JVM with
 * {@code -javaagent:} pointing at the packaged agent jar; reaching {@code main}
 * proves {@code premain} completed without aborting JVM startup.
 *
 * <p>Not a test class — no JUnit annotations — so Surefire, Failsafe and Gradle
 * all ignore it during discovery.
 */
public final class PremainChildMain {

    /** Printed on stdout so the parent can assert the JVM survived premain. */
    static final String MARKER = "premain-survived-main-ran";

    private PremainChildMain() {}

    public static void main(String[] args) {
        System.out.println(MARKER);
    }
}
