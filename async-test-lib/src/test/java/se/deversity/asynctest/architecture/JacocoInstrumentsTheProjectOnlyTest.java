package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JaCoCo's agent instruments only this project's classes, in both builds (#897).
 *
 * <p>With no {@code includes}, every forked test JVM instrumented every class it loaded, JUnit,
 * Byte Buddy and ASM among them, for a report that only ever covers {@code se.deversity.*}. CI
 * starts one JVM per test class, so the cost was paid hundreds of times per run. The named-module
 * fixture stays excluded for its own reason (#668, #862), and the two builds must agree, or one of
 * them measures something the other does not.
 */
class JacocoInstrumentsTheProjectOnlyTest {

    /** The prepare-agent execution's configuration in the root pom. */
    private static final Pattern PREPARE_AGENT = Pattern.compile(
            "<goal>prepare-agent</goal>\\s*</goals>\\s*<configuration>(.*?)</configuration>",
            Pattern.DOTALL);

    /** The JacocoTaskExtension block of the root Gradle build. */
    private static final Pattern GRADLE_EXTENSION = Pattern.compile(
            "configure<JacocoTaskExtension>\\s*\\{([^}]*)}");

    @Test
    @DisplayName("Maven's prepare-agent includes se.deversity.* and still excludes the named-module fixture")
    void mavenInstrumentsTheProjectOnly() {
        Matcher agent = PREPARE_AGENT.matcher(read(repoRoot().resolve("pom.xml")));
        assertTrue(agent.find(), "no configuration on the root pom's prepare-agent execution");
        String configuration = agent.group(1);

        assertTrue(configuration.contains("<include>se.deversity.*</include>"),
                "prepare-agent instruments every class a test JVM loads unless it includes only "
                        + "se.deversity.*, which is all the report covers (#897): " + configuration);
        assertTrue(configuration.contains("<exclude>com.example.namedfixture.*</exclude>"),
                "the named-module fixture must stay excluded (#668, #862): " + configuration);
    }

    @Test
    @DisplayName("Gradle's JacocoTaskExtension mirrors the pom")
    void gradleMirrorsThePom() {
        Matcher extension = GRADLE_EXTENSION.matcher(read(repoRoot().resolve("build.gradle.kts")));
        assertTrue(extension.find(), "no JacocoTaskExtension block in build.gradle.kts");
        String block = extension.group(1);

        assertTrue(block.contains("includes = listOf(\"se.deversity.*\")"),
                "build.gradle.kts must include only se.deversity.*, as the pom does (#897): " + block);
        assertTrue(block.contains("excludes = listOf(\"com.example.namedfixture.*\")"),
                "build.gradle.kts must keep excluding the named-module fixture: " + block);
    }

    /** Walks up from the working directory to the reactor root, as Maven and Gradle both start below it. */
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

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
