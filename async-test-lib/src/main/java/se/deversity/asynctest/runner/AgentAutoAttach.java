package se.deversity.asynctest.runner;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attaches the instrumentation agent from inside the test run: by default whenever the agent
 * artifact is on the test classpath, or as the user asks with {@code -Dasynctest.agent=<agentArgs>}.
 *
 * <p><strong>Why this exists.</strong> Field-level detection needs the agent, and getting the
 * agent attached was the step that lost people. The documented route is
 * {@code -javaagent:/path/to/async-test-agent-<version>.jar}, which means knowing where Maven or
 * Gradle put that jar — a path that differs per machine and per build tool, and that changes on
 * every version bump. The agent has always been able to attach itself at runtime; nothing in the
 * library ever asked it to. From 1.9.2 a property asked. Now adding the artifact is the ask: a user
 * who added it and saw the README's {@code counter++} pass silently had done everything but find
 * one flag.
 *
 * <h4>Which arguments</h4>
 * See {@link #plan}. The default weaves field instructions and collection calls, the two feeds most
 * agent-fed detectors need on unmodified code, but only in the test's own project: the first three
 * segments of the test class's package, so {@code com.acme.shop.order.OrderTest} weaves
 * {@code com.acme.shop}. Without an include the agent would weave JUnit and the build tool as well.
 *
 * <h4>Why reflection</h4>
 * {@code ArchitectureTest} forbids the library from depending on the agent module, and rightly:
 * the agent carries Byte Buddy, and a compile-time edge would drag a bytecode-manipulation library
 * into every consumer's test classpath whether or not they instrument anything. The agent's own
 * javadoc names the two supported entry points as {@code -javaagent:} and {@code selfAttach()},
 * so reaching the latter reflectively is the sanctioned route rather than a way around the rule.
 *
 * <h4>Failure policy</h4>
 * Every failure degrades to "no instrumentation" and logs once. A missing agent artifact, a JVM
 * that forbids self-attachment ({@code -Djdk.attach.allowAttachSelf=false}), a security manager —
 * none of them are worth failing somebody's test suite over, because the run is still valid, it
 * just observes less. The absence is already surfaced: {@code ConcurrencyRunner} logs
 * {@code runner.agent.absent} when a detector that needs woven events finds the pipeline dark.
 *
 * @since 1.9.2
 */
final class AgentAutoAttach {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoAttach.class);

    /**
     * System property carrying the agent argument string. When set, the value is passed through
     * verbatim, so {@code -Dasynctest.agent=includes=com.myapp,fields=true} behaves exactly as the
     * same string after {@code -javaagent:...=} would; {@code off} or {@code false} opts out of the
     * default attach.
     */
    static final String PROPERTY = "asynctest.agent";

    /** Fully-qualified name of the agent entry point, resolved reflectively. */
    private static final String AGENT_CLASS = "se.deversity.asynctest.agent.AsyncTestAgent";

    /** The prefix the agent always ignores, so a test class under it has nothing to weave. */
    private static final String LIBRARY_PACKAGE = "se.deversity.asynctest.";

    /** Package segments of the test class kept as the default {@code includes=}. */
    private static final int INCLUDED_SEGMENTS = 3;

    /** At-most-once per JVM. The agent has its own gate; this one avoids repeating the logging. */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private AgentAutoAttach() {}

    /**
     * What to attach.
     *
     * @param args   the agent argument string, empty for the agent's own defaults
     * @param source {@code property} when the user set {@code asynctest.agent}, {@code classpath}
     *               when the artifact's presence asked for the default
     */
    record Plan(String args, String source) {}

    /**
     * Decides whether to attach and with what, from everything the decision depends on.
     *
     * @param property         the value of {@code asynctest.agent}, or {@code null} when unset
     * @param agentRunning     whether an agent already feeds the telemetry pipeline
     * @param agentOnClasspath whether the agent's entry point resolves
     * @param testPackage      the test class's package, {@code ""} for the default package, or
     *                         {@code null} when the run has no test class
     * @return the attach to make, or {@code null} for none
     */
    static @Nullable Plan plan(@Nullable String property, boolean agentRunning, boolean agentOnClasspath,
                               @Nullable String testPackage) {
        if (agentRunning) {
            return null;
        }
        if (property != null) {
            String switchValue = property.strip().toLowerCase(Locale.ROOT);
            if ("off".equals(switchValue) || "false".equals(switchValue)) {
                return null;
            }
            return new Plan(property, "property");
        }
        // The library's own package is AsyncTestRunner's BodyHolder, and the agent never weaves it.
        if (!agentOnClasspath || testPackage == null || testPackage.isEmpty()
                || (testPackage + ".").startsWith(LIBRARY_PACKAGE)) {
            return null;
        }
        String[] segments = testPackage.split("\\.");
        String includes = segments.length <= INCLUDED_SEGMENTS
                ? testPackage
                : String.join(".", Arrays.copyOf(segments, INCLUDED_SEGMENTS));
        return new Plan("fields=true,collections=true,includes=" + includes, "classpath");
    }

    /**
     * Attaches the agent if {@link #plan} says to, at most once per JVM.
     *
     * <p>Without the property and without the agent artifact, which is the default for a consumer
     * that did not add it, this costs one failed class lookup per JVM and logs nothing.
     *
     * @param testClass the class of the test being run, or {@code null} when there is none
     */
    static void attachIfRequested(@Nullable Class<?> testClass) {
        if (!ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        @Nullable Plan plan = plan(System.getProperty(PROPERTY), TelemetryRegistry.isRunning(),
                agentOnClasspath(), testClass == null ? null : testClass.getPackageName());
        if (plan == null) {
            return;
        }
        String agentArgs = plan.args();
        try {
            Class<?> agent = Class.forName(AGENT_CLASS);
            Method selfAttach = agent.getMethod("selfAttach", String.class);
            selfAttach.invoke(null, agentArgs.isEmpty() ? null : agentArgs);
            log.info("runner.agent.attached source={} args=\"{}\"", plan.source(), agentArgs);
        } catch (ClassNotFoundException e) {
            log.warn("runner.agent.attach.failed reason=artifact-missing "
                    + "hint=\"-D{} was set but {} is not on the test classpath; add the "
                    + "async-test-agent artifact as a test dependency, or attach it with "
                    + "-javaagent: instead. Continuing without instrumentation.\"",
                    PROPERTY, AGENT_CLASS);
        } catch (Throwable t) { // NOPMD - broad by design: no attach failure may fail a test run
            log.warn("runner.agent.attach.failed reason=attach-refused source={} "
                    + "hint=\"self-attachment was rejected by this JVM; run with "
                    + "-Djdk.attach.allowAttachSelf=true or attach with -javaagent: instead, or set "
                    + "-D{}=off to stop trying. Continuing without instrumentation.\" cause={}",
                    plan.source(), PROPERTY, t.toString());
        }
    }

    private static boolean agentOnClasspath() {
        try {
            Class.forName(AGENT_CLASS, false, AgentAutoAttach.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
