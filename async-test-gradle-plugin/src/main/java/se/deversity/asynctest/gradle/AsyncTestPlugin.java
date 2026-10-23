package se.deversity.asynctest.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * {@code id("se.deversity.async-test")}: starts every {@code Test} task's JVM with
 * {@code -javaagent:async-test-agent.jar} (#1045).
 *
 * <p>Attaching at startup is the route JEP 451 endorses; the runner's runtime fallback prints the
 * JVM's warning on JDK 21 and later and a future JDK will refuse it. By hand that took a dedicated
 * configuration and a {@code jvmArgs} call inside {@code doFirst}. Applied, this plugin adds
 *
 * <ul>
 *   <li>the {@code asyncTestAgent} configuration, non-transitive because the published agent jar
 *       already shades Byte Buddy, defaulting to {@code async-test-agent} of the plugin's version;</li>
 *   <li>the {@code asyncTest} extension ({@link AsyncTestExtension}) for the agent's options;</li>
 *   <li>an {@link AgentArgumentProvider} on every {@code Test} task.</li>
 * </ul>
 *
 * <p>The library itself is still the user's {@code testImplementation} dependency: which artifact a
 * test compiles against stays visible in their build.
 */
public final class AsyncTestPlugin implements Plugin<Project> {

    /** The configuration holding the agent jar. */
    public static final String CONFIGURATION = "asyncTestAgent";

    @Override
    public void apply(Project project) {
        AsyncTestExtension extension = project.getExtensions().create("asyncTest", AsyncTestExtension.class);
        extension.getFields().convention(true);
        extension.getCollections().convention(true);
        extension.getAgentVersion().convention(ownVersion());

        Configuration agent = project.getConfigurations().create(CONFIGURATION, c -> {
            c.setTransitive(false);
            c.setCanBeConsumed(false);
            c.setDescription("The async-test-agent jar every Test task starts with -javaagent:");
            c.defaultDependencies(deps -> deps.add(project.getDependencies().create(
                    "se.deversity.async-test-lib:async-test-agent:" + extension.getAgentVersion().get())));
        });

        project.getTasks().withType(Test.class).configureEach(test -> {
            AgentArgumentProvider argument = project.getObjects().newInstance(AgentArgumentProvider.class);
            argument.getAgentJar().from(agent);
            argument.getIncludes().set(extension.getIncludes());
            argument.getFields().set(extension.getFields());
            argument.getCollections().set(extension.getCollections());
            test.getJvmArgumentProviders().add(argument);
        });
    }

    /** {@return the version this plugin was built as, which is the library release it belongs to} */
    static String ownVersion() {
        try (InputStream in = AsyncTestPlugin.class.getResourceAsStream("async-test-gradle-plugin.properties")) {
            if (in == null) {
                throw new IllegalStateException("async-test-gradle-plugin.properties is missing from the plugin jar");
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties.getProperty("version");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
