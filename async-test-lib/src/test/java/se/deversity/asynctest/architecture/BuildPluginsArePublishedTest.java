package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both build plugins ship with every release (#1099): {@code async-test-maven-plugin} to Maven
 * Central in the publish job, {@code async-test-gradle-plugin} to the Gradle Plugin Portal in a job
 * that waits for it.
 *
 * <p>The plugins were built and tested for two releases without being published, so a user who
 * read about them could apply neither. What this pins is the wiring that a refactor of
 * {@code publish.yml} or of either build file would drop silently: the Maven plugin's deploy sits
 * between the reactor's deploy and the Central wait, so the wait covers it; the wait script names
 * the plugin; the Gradle job needs the publish job and runs {@code publishPlugins}; the Maven
 * plugin's pom flattens away its unpublished parent and carries the Central profile; the Gradle
 * build applies the portal plugin with the metadata the portal refuses to publish without.
 */
class BuildPluginsArePublishedTest {

    private static final String WAIT_SCRIPT = ".github/scripts/wait-for-central.sh";

    @Test
    @DisplayName("publish.yml deploys the Maven plugin after the reactor and before the Central wait")
    void mavenPluginIsDeployedBeforeTheCentralWait() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        int reactorDeploy = publish.indexOf("clean deploy -P release");
        int pluginDeploy = publish.indexOf("-f async-test-maven-plugin/pom.xml clean deploy -P release");
        int wait = publish.indexOf(WAIT_SCRIPT);
        assertTrue(reactorDeploy >= 0 && pluginDeploy > reactorDeploy,
                "publish.yml must deploy async-test-maven-plugin after the reactor's deploy");
        assertTrue(wait > pluginDeploy,
                "publish.yml must run " + WAIT_SCRIPT + " after the plugin's deploy, so the wait covers it");
        assertTrue(publish.contains("async-test-maven-plugin/target/async-test-maven-plugin-${VERSION}.jar"),
                "the cosign loop and the GitHub Release must list the plugin's jar");
    }

    @Test
    @DisplayName("wait-for-central.sh waits for the Maven plugin as well")
    void centralWaitNamesTheMavenPlugin() throws IOException {
        String script = read(WAIT_SCRIPT);
        assertTrue(script.contains("async-test-maven-plugin"),
                WAIT_SCRIPT + " must poll async-test-maven-plugin too, or a release whose plugin bundle"
                        + " was rejected goes green");
    }

    @Test
    @DisplayName("the Gradle plugin is published in a job that waits for the Central publish")
    void gradlePluginJobNeedsThePublishJob() throws IOException {
        String publish = read(".github/workflows/publish.yml");
        int job = publish.indexOf("publish-gradle-plugin:");
        assertTrue(job >= 0, "publish.yml has no publish-gradle-plugin job");
        String body = publish.substring(job, nextJob(publish, job));
        assertTrue(body.contains("needs: publish"),
                "the Gradle Plugin Portal job must need the publish job: the plugin asks for the agent of its"
                        + " version, which must be on Central first");
        assertTrue(body.contains("publishPlugins"), "the job must run publishPlugins");
        assertTrue(body.contains("GRADLE_PUBLISH_KEY") && body.contains("GRADLE_PUBLISH_SECRET"),
                "publishPlugins reads GRADLE_PUBLISH_KEY and GRADLE_PUBLISH_SECRET from the environment");
        assertTrue(publish.contains("gradle-plugin-portal-key-missing:"),
                "a release without the portal key must say so in a job of its own, not skip quietly");
    }

    @Test
    @DisplayName("the Maven plugin's pom flattens its unpublished parent and carries the Central profile")
    void mavenPluginPomPublishes() throws IOException {
        String pom = read("async-test-maven-plugin/pom.xml");
        assertTrue(pom.contains("<artifactId>flatten-maven-plugin</artifactId>")
                        && pom.contains("<flattenMode>ossrh</flattenMode>"),
                "the plugin's parent versions/pom.xml is never published and its version is a property of it;"
                        + " flatten-maven-plugin in ossrh mode writes the pom a consumer can resolve");
        int profile = pom.indexOf("<id>release</id>");
        assertTrue(profile >= 0, "the plugin pom has no release profile");
        String release = pom.substring(profile);
        for (String plugin : new String[] {"maven-source-plugin", "maven-javadoc-plugin", "maven-gpg-plugin",
                "central-publishing-maven-plugin"}) {
            assertTrue(release.contains("<artifactId>" + plugin + "</artifactId>"),
                    "the release profile must declare " + plugin + ", as the reactor's does");
        }
        for (String required : new String[] {"<licenses>", "<developers>", "<scm>", "<description>", "<url>"}) {
            assertTrue(pom.contains(required), "Central refuses a pom without " + required);
        }
    }

    @Test
    @DisplayName("the Gradle plugin build applies plugin-publish with the metadata the portal requires")
    void gradlePluginBuildPublishes() throws IOException {
        String build = read("async-test-gradle-plugin/build.gradle.kts");
        assertTrue(build.contains("id(\"com.gradle.plugin-publish\")"),
                "async-test-gradle-plugin must apply com.gradle.plugin-publish for publishPlugins");
        for (String required : new String[] {"website.set(", "vcsUrl.set(", "tags.set("}) {
            assertTrue(build.contains(required), "publishPlugins refuses a plugin without " + required);
        }
    }

    private static int nextJob(String yaml, int from) {
        int next = yaml.indexOf("\n  " + "gradle-plugin-portal-key-missing:", from);
        return next < 0 ? yaml.length() : next;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above "
                + Path.of("").toAbsolutePath());
    }
}
