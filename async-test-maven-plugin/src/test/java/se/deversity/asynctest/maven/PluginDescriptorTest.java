package se.deversity.asynctest.maven;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin descriptor maven-plugin-plugin generated for this build runs on the Maven users have.
 *
 * <p>{@code requiredMavenVersion} is copied from the {@code maven-plugin-api} dependency's version,
 * and a user's Maven below it refuses the plugin before any goal runs. Dependabot bumped that
 * dependency to 3.10.0 in #1103, which would have locked every Maven 3.9 build out of the plugin,
 * the version this project itself pins (docs/BUILDING.md); the bump was merged, and nothing went red.
 * This reads the descriptor the build just generated, so the next such bump fails here.
 */
class PluginDescriptorTest {

    private static final Pattern REQUIRED_MAVEN =
            Pattern.compile("<requiredMavenVersion>(\\d+)\\.(\\d+)\\.(\\d+)</requiredMavenVersion>");

    @Test
    @DisplayName("the descriptor requires a Maven below 3.10, so Maven 3.9 builds can use the plugin")
    void requiredMavenStaysBelow310() throws IOException {
        String descriptor;
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertNotNull(in, "META-INF/maven/plugin.xml is not on the test class path: maven-plugin-plugin"
                    + " did not run, or this test runs outside the Maven build");
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = REQUIRED_MAVEN.matcher(descriptor);
        assertTrue(m.find(), "no <requiredMavenVersion> in the descriptor:\n" + descriptor);
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        assertTrue(major == 3 && minor < 10, "requiredMavenVersion is " + m.group(0).replaceAll("<[^>]+>", "")
                + ": a Maven 3.9 build would refuse async-test:prepare-agent. Keep maven-plugin-api.version in"
                + " versions/pom.xml on 3.9.x; .github/dependabot.yml ignores its minor and major bumps.");
    }
}
