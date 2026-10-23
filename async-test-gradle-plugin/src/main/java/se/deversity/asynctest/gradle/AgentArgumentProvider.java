package se.deversity.asynctest.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

import java.util.List;

/**
 * The {@code -javaagent:} argument a {@code Test} task starts its JVM with (#1045).
 *
 * <p>A provider rather than a {@code jvmArgs} call in {@code doFirst}, which is what the hand-written
 * snippet needed: the jar resolves only when the task runs, the inputs are declared so a changed
 * agent or option reruns the tests, and the configuration cache can store it.
 */
public abstract class AgentArgumentProvider implements CommandLineArgumentProvider {

    /** {@return the agent jar: the {@code asyncTestAgent} configuration, which holds exactly one file} */
    @Classpath
    public abstract ConfigurableFileCollection getAgentJar();

    /** {@return the package prefixes the agent weaves} */
    @Input
    public abstract ListProperty<String> getIncludes();

    /** {@return the agent's {@code fields=}} */
    @Input
    public abstract Property<Boolean> getFields();

    /** {@return the agent's {@code collections=}} */
    @Input
    public abstract Property<Boolean> getCollections();

    @Override
    public Iterable<String> asArguments() {
        StringBuilder options = new StringBuilder()
                .append("fields=").append(getFields().get())
                .append(",collections=").append(getCollections().get());
        List<String> includes = getIncludes().get();
        if (!includes.isEmpty()) {
            // The agent's grammar: one key, several values separated by ';' (AgentOptions).
            options.append(",includes=").append(String.join(";", includes));
        }
        return List.of("-javaagent:" + getAgentJar().getSingleFile().getAbsolutePath() + "=" + options);
    }
}
