package se.deversity.asynctest.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Attaches the instrumentation agent from inside the test run when the user asks for it with
 * {@code -Dasynctest.agent=<agentArgs>}.
 *
 * <p><strong>Why this exists.</strong> Field-level detection needs the agent, and getting the
 * agent attached was the step that lost people. The documented route is
 * {@code -javaagent:/path/to/async-test-agent-<version>.jar}, which means knowing where Maven or
 * Gradle put that jar — a path that differs per machine and per build tool, and that changes on
 * every version bump. The agent has always been able to attach itself at runtime; nothing in the
 * library ever asked it to. This does, behind an explicit property, so the user writes one flag
 * instead of resolving a path.
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
     * System property carrying the agent argument string. Presence triggers the attach; the value
     * is passed through verbatim, so {@code -Dasynctest.agent=includes=com.myapp,fields=true}
     * behaves exactly as the same string after {@code -javaagent:...=} would.
     */
    static final String PROPERTY = "asynctest.agent";

    /** Fully-qualified name of the agent entry point, resolved reflectively. */
    private static final String AGENT_CLASS = "se.deversity.asynctest.agent.AsyncTestAgent";

    /** At-most-once per JVM. The agent has its own gate; this one avoids repeating the logging. */
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();

    private AgentAutoAttach() {}

    /**
     * Attaches the agent if {@code asynctest.agent} is set, at most once per JVM.
     *
     * <p>Does nothing when the property is absent, which is the default and keeps the zero-config
     * path free of reflection and of any agent lookup.
     */
    static void attachIfRequested() {
        String agentArgs = System.getProperty(PROPERTY);
        if (agentArgs == null || !ATTEMPTED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> agent = Class.forName(AGENT_CLASS);
            Method selfAttach = agent.getMethod("selfAttach", String.class);
            selfAttach.invoke(null, agentArgs.isEmpty() ? null : agentArgs);
            log.info("runner.agent.attached args=\"{}\"", agentArgs);
        } catch (ClassNotFoundException e) {
            log.warn("runner.agent.attach.failed reason=artifact-missing "
                    + "hint=\"-D{} was set but {} is not on the test classpath; add the "
                    + "async-test-agent artifact as a test dependency, or attach it with "
                    + "-javaagent: instead. Continuing without instrumentation.\"",
                    PROPERTY, AGENT_CLASS);
        } catch (Throwable t) { // NOPMD - broad by design: no attach failure may fail a test run
            log.warn("runner.agent.attach.failed reason=attach-refused "
                    + "hint=\"self-attachment was rejected by this JVM; run with "
                    + "-Djdk.attach.allowAttachSelf=true or attach with -javaagent: instead. "
                    + "Continuing without instrumentation.\" cause={}", t.toString());
        }
    }
}
