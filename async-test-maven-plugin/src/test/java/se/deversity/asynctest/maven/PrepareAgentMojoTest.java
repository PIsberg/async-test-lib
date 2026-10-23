package se.deversity.asynctest.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code async-test:prepare-agent} leaves in the {@code argLine} property surefire starts the
 * test JVM with (#1045). That a real build attaches the agent and finds a race through it, next to
 * JaCoCo's agent, is {@code consumer-fixture-langs/quickstart-plugin}'s job.
 */
class PrepareAgentMojoTest {

    @TempDir
    Path dir;

    /** A mojo over a project whose resolved test artifacts include an agent jar at {@code agentDir}. */
    private PrepareAgentMojo mojo(Path agentDir, boolean withAgent) throws Exception {
        MavenProject project = new MavenProject();
        if (withAgent) {
            Files.createDirectories(agentDir);
            File jar = Files.write(agentDir.resolve("async-test-agent-1.0.jar"), new byte[0]).toFile();
            Artifact agent = new DefaultArtifact("se.deversity.async-test-lib", "async-test-agent", "1.0",
                    Artifact.SCOPE_TEST, "jar", null, new DefaultArtifactHandler("jar"));
            agent.setFile(jar);
            project.setArtifacts(Set.of(agent));
        } else {
            project.setArtifacts(Set.of());
        }
        PrepareAgentMojo mojo = new PrepareAgentMojo();
        mojo.project = project;
        mojo.includes = List.of("com.example");
        mojo.fields = true;
        mojo.collections = true;
        mojo.propertyName = "argLine";
        return mojo;
    }

    private static String argLine(PrepareAgentMojo mojo) {
        return mojo.project.getProperties().getProperty("argLine");
    }

    @Test
    @DisplayName("argLine becomes -javaagent: with the agent's resolved path and the options")
    void setsTheAgentArgument() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("repo"), true);
        mojo.execute();
        String jar = dir.resolve("repo/async-test-agent-1.0.jar").toFile().getAbsolutePath();
        assertEquals("-javaagent:" + jar + "=fields=true,collections=true,includes=com.example", argLine(mojo));
    }

    @Test
    @DisplayName("an argLine JaCoCo set first is kept, so the two agents compose in either order")
    void keepsAnArgLineAlreadySet() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("repo"), true);
        mojo.project.getProperties().setProperty("argLine", "-javaagent:/m2/jacoco.jar=destfile=jacoco.exec");
        mojo.execute();
        assertTrue(argLine(mojo).startsWith("-javaagent:/m2/jacoco.jar=destfile=jacoco.exec -javaagent:"),
                argLine(mojo));
        assertTrue(argLine(mojo).endsWith("async-test-agent-1.0.jar=fields=true,collections=true,includes=com.example"),
                argLine(mojo));
    }

    @Test
    @DisplayName("several includes use the agent's ';' separator, and no includes passes none")
    void includesFollowTheAgentGrammar() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("repo"), true);
        mojo.includes = List.of("com.example", "org.acme");
        mojo.fields = false;
        mojo.execute();
        assertTrue(argLine(mojo).endsWith("=fields=false,collections=true,includes=com.example;org.acme"), argLine(mojo));

        PrepareAgentMojo none = mojo(dir.resolve("repo2"), true);
        none.includes = null;
        none.execute();
        assertTrue(argLine(none).endsWith("=fields=true,collections=true"), argLine(none));
    }

    @Test
    @DisplayName("a path with a space is quoted, or surefire would split the argument in two")
    void quotesAPathWithASpace() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("John Doe/repo"), true);
        mojo.execute();
        assertTrue(argLine(mojo).startsWith("\"-javaagent:") && argLine(mojo).endsWith("\""), argLine(mojo));
    }

    @Test
    @DisplayName("without async-test-agent among the test dependencies the build fails, naming what to add")
    void failsWithoutTheAgentDependency() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("repo"), false);
        MojoExecutionException e = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(e.getMessage().contains("se.deversity.async-test-lib:async-test-agent"), e.getMessage());
    }

    @Test
    @DisplayName("skip leaves argLine untouched")
    void skipLeavesArgLineAlone() throws Exception {
        PrepareAgentMojo mojo = mojo(dir.resolve("repo"), true);
        mojo.skip = true;
        mojo.execute();
        assertNull(argLine(mojo));
    }
}
