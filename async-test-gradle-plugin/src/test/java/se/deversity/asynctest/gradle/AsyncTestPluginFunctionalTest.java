package se.deversity.asynctest.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What applying {@code se.deversity.async-test} does to a consumer's build (#1045), observed from a
 * real Gradle run of a throwaway project.
 *
 * <p>The agent jar is a local file in these builds, declared on the plugin's configuration, so no
 * repository is needed; the argument is read from the {@code test} task's JVM arguments rather than
 * by starting a test JVM, which a placeholder jar could not start. That the real agent attaches and
 * finds a race through the plugin is {@code consumer-fixture-langs/quickstart-plugin}'s job.
 */
class AsyncTestPluginFunctionalTest {

    @TempDir
    Path project;

    /** A consumer build applying the plugin, with {@code extension} as its asyncTest block. */
    private void consumer(String extension) throws IOException {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"consumer\"\n");
        Files.createDirectories(project.resolve("agent"));
        Files.write(project.resolve("agent/async-test-agent.jar"), new byte[0]);
        Files.writeString(project.resolve("build.gradle.kts"), """
                plugins {
                    java
                    id("se.deversity.async-test")
                }
                %s
                tasks.register("printAgentArguments") {
                    val test = tasks.test
                    doLast {
                        println("ARGS " + test.get().allJvmArgs.filter { it.startsWith("-javaagent:") })
                    }
                }
                """.formatted(extension));
    }

    private BuildResult run(String task) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(task, "--stacktrace")
                .forwardOutput()
                .build();
    }

    private String localAgent() {
        return project.resolve("agent/async-test-agent.jar").toFile().getAbsolutePath();
    }

    @Test
    @DisplayName("the test task starts its JVM with -javaagent:, fields, collections and the includes")
    void theTestTaskGetsTheAgentArgument() throws IOException {
        consumer("""
                asyncTest { includes.add("com.example") }
                dependencies { "asyncTestAgent"(files("agent/async-test-agent.jar")) }
                """);
        String output = run("printAgentArguments").getOutput();
        assertTrue(output.contains("-javaagent:" + localAgent() + "=fields=true,collections=true,includes=com.example"),
                output);
    }

    @Test
    @DisplayName("several includes are joined the way the agent's grammar reads them, and fields can be switched off")
    void includesAndSwitchesFollowTheExtension() throws IOException {
        consumer("""
                asyncTest {
                    includes.addAll("com.example", "org.acme")
                    fields.set(false)
                }
                dependencies { "asyncTestAgent"(files("agent/async-test-agent.jar")) }
                """);
        String output = run("printAgentArguments").getOutput();
        assertTrue(output.contains("=fields=false,collections=true,includes=com.example;org.acme"), output);
    }

    @Test
    @DisplayName("with no includes the agent gets none, rather than an empty prefix that matches nothing")
    void noIncludesMeansNoIncludesOption() throws IOException {
        consumer("""
                dependencies { "asyncTestAgent"(files("agent/async-test-agent.jar")) }
                """);
        String output = run("printAgentArguments").getOutput();
        assertTrue(output.contains("=fields=true,collections=true]"), output);
        assertFalse(output.contains("includes="), output);
    }

    @Test
    @DisplayName("left alone, the agent is async-test-agent of the plugin's own version, from the build's repositories")
    void theDefaultAgentIsThePluginsOwnVersion() throws IOException {
        String version = pluginVersion();
        Path jar = project.resolve("repo/se/deversity/async-test-lib/async-test-agent/" + version
                + "/async-test-agent-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[0]);
        consumer("""
                repositories {
                    maven {
                        url = uri("repo")
                        metadataSources { artifact() }
                    }
                }
                """);
        String output = run("printAgentArguments").getOutput();
        assertTrue(output.contains("-javaagent:") && output.contains("async-test-agent-" + version + ".jar="), output);
    }

    /** The version the build baked into the plugin, read the way the plugin reads it. */
    private static String pluginVersion() throws IOException {
        Properties properties = new Properties();
        try (var in = AsyncTestPlugin.class.getResourceAsStream("async-test-gradle-plugin.properties")) {
            assertTrue(in != null, "the plugin's version resource is missing");
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties.getProperty("version");
    }
}
