package se.deversity.asynctest.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.util.List;

/**
 * {@code async-test:prepare-agent}: puts {@code -javaagent:async-test-agent.jar} into the property
 * surefire starts the test JVM with (#1045).
 *
 * <p>Attaching at startup is the route JEP 451 endorses; the runner's runtime fallback prints the
 * JVM's warning on JDK 21 and later and a future JDK will refuse it. By hand it took a
 * {@code maven-dependency-plugin:properties} execution, to learn the agent jar's path, and a surefire
 * {@code argLine} naming it, which replaced any {@code argLine} JaCoCo had set unless the user knew
 * to write {@code @{argLine}}. This goal does what {@code jacoco:prepare-agent} does: it appends to
 * the {@code argLine} property instead of owning it, so the two compose whichever runs first, and
 * surefire picks the property up with no configuration at all. A surefire {@code <argLine>} that
 * neither is nor contains {@code @{argLine}} still overrides the property, for this goal as for
 * JaCoCo's.
 *
 * <p>The agent jar is the project's own {@code se.deversity.async-test-lib:async-test-agent} test
 * dependency, so which agent the tests get stays visible in the pom, as the library does.
 */
@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE,
        requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

    static final String GROUP_ID = "se.deversity.async-test-lib";
    static final String AGENT_ARTIFACT_ID = "async-test-agent";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * The package or class name prefixes to weave, the agent's {@code includes=}. None, the default,
     * passes no {@code includes=}, and the agent weaves every class it is allowed to, which is slow;
     * name your own packages.
     */
    @Parameter(property = "asynctest.includes")
    List<String> includes;

    /** Whether field accesses are woven, the agent's {@code fields=}. */
    @Parameter(property = "asynctest.fields", defaultValue = "true")
    boolean fields;

    /** Whether collection, lock and blocking calls are woven, the agent's {@code collections=}. */
    @Parameter(property = "asynctest.collections", defaultValue = "true")
    boolean collections;

    /** The property to append the argument to; surefire and failsafe both read {@code argLine}. */
    @Parameter(property = "asynctest.propertyName", defaultValue = "argLine")
    String propertyName;

    /** Leaves the property untouched. */
    @Parameter(property = "asynctest.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("async-test agent not prepared: asynctest.skip is set");
            return;
        }
        String argument = argument(agentJarPath());
        String existing = project.getProperties().getProperty(propertyName);
        String value = existing == null || existing.isBlank() ? argument : existing + " " + argument;
        project.getProperties().setProperty(propertyName, value);
        getLog().info(propertyName + " set to " + value);
    }

    /** {@return the resolved path of the project's async-test-agent test dependency} */
    private String agentJarPath() throws MojoExecutionException {
        for (Artifact artifact : project.getArtifacts()) {
            if (GROUP_ID.equals(artifact.getGroupId()) && AGENT_ARTIFACT_ID.equals(artifact.getArtifactId())
                    && artifact.getFile() != null) {
                return artifact.getFile().getAbsolutePath();
            }
        }
        throw new MojoExecutionException("async-test:prepare-agent found no " + GROUP_ID + ":" + AGENT_ARTIFACT_ID
                + " among the project's dependencies. Add it with <scope>test</scope>, the same version as "
                + "async-test-lib.");
    }

    /** {@return the {@code -javaagent:} argument, quoted when the path holds a space} */
    private String argument(String jar) {
        StringBuilder options = new StringBuilder()
                .append("fields=").append(fields)
                .append(",collections=").append(collections);
        if (includes != null && !includes.isEmpty()) {
            // The agent's grammar: one key, several values separated by ';' (AgentOptions).
            options.append(",includes=").append(String.join(";", includes));
        }
        String argument = "-javaagent:" + jar + "=" + options;
        // Surefire splits argLine on whitespace outside quotes; a path with a space would become two
        // arguments and the JVM would refuse to start. JaCoCo quotes its agent argument the same way.
        return argument.indexOf(' ') >= 0 ? "\"" + argument + "\"" : argument;
    }
}
