package se.deversity.asynctest.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests & Build runs two per-class test JVMs at a time on Ubuntu (#898).
 *
 * <p>{@code reuseForks=false} gives every test class its own JVM, and with {@code forkCount=1}
 * about 440 of them started and finished one after another, so most of the test step was JVM
 * start-up paid serially. Two forks share no static state, since each class still gets its own
 * JVM. Ubuntu only: macOS and Windows already run the timing-sensitive e2e tier with a tripled
 * timeout multiplier, and the soak that justified two forks ran on Ubuntu. The value goes through
 * the {@code surefire.forkCount} property, because a {@code <forkCount>} literal in the pom would
 * accept {@code -DforkCount} and ignore it.
 */
class CiForkCountTest {

    private static final String WORKFLOW = ".github/workflows/tests.yml";

    @Test
    @DisplayName("the Ubuntu legs pass -Dsurefire.forkCount=2 and the others keep the default")
    void ubuntuRunsTwoForks() {
        String run = stepBody("Run library tests and install artifact");
        assertTrue(run.contains("${{ matrix.os == 'ubuntu-latest' && '-Dsurefire.forkCount=2' || '' }}"),
                "the build step must pass -Dsurefire.forkCount=2 on Ubuntu only (#898): " + run);
    }

    @Test
    @DisplayName("the pom still routes forkCount through the surefire.forkCount property")
    void thePomReadsTheProperty() {
        String pom = read(repoRoot().resolve("pom.xml"));
        assertTrue(pom.contains("<forkCount>${surefire.forkCount}</forkCount>"),
                "a <forkCount> literal would accept -Dsurefire.forkCount=2 and ignore it");
    }

    /** {@return the lines of the step named {@code name}, up to the next step} */
    private static String stepBody(String name) {
        String workflow = read(repoRoot().resolve(WORKFLOW));
        int start = workflow.indexOf("- name: " + name);
        assertTrue(start >= 0, "no step named '" + name + "' in " + WORKFLOW);
        int end = workflow.indexOf("\n      - name: ", start + 1);
        return workflow.substring(start, end < 0 ? workflow.length() : end);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
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
}
