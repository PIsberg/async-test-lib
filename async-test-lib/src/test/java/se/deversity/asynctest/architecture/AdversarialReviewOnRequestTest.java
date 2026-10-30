package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two machine review lanes run only when a pull request asks for one (#1137).
 *
 * <p>The Inquisitor reran on every push to every pull request, and the Copilot lane on every opened
 * one. Both spend a quota the maintainer pays for (a Claude subscription token, Copilot Free), and
 * most of those runs reviewed work-in-progress nobody had asked a reviewer to read. The request is
 * now the {@value #LABEL} label: each lane triggers on {@code labeled} alone and runs its first job
 * only for that label, so adding it once is one review, and removing and adding it again is the
 * next. Any other trigger type, or a gate that forgets the label name, silently brings the
 * per-push spending back, and nothing else in the build reads the trigger.
 */
class AdversarialReviewOnRequestTest {

    static final String LABEL = "adversarial-review";

    private static final Pattern TYPES = Pattern.compile("(?m)^\\s+types:\\s*\\[([^\\]]*)]");

    /** A job header: a key indented by exactly two spaces under {@code jobs:}. */
    private static final Pattern JOB = Pattern.compile("\n  [a-z][a-z0-9_-]*:\n");

    @Test
    @DisplayName("the Inquisitor runs only when the review label is added")
    void inquisitorRunsOnlyOnRequest() throws IOException {
        assertRunsOnlyOnRequest("inquisitor.yml");
    }

    @Test
    @DisplayName("the Copilot review is requested only when the review label is added")
    void copilotReviewRunsOnlyOnRequest() throws IOException {
        assertRunsOnlyOnRequest("copilot-review.yml");
    }

    private static void assertRunsOnlyOnRequest(String workflow) throws IOException {
        Path file = repoRoot().resolve(".github/workflows").resolve(workflow);
        assertTrue(Files.isRegularFile(file), file + " is missing");
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");

        String on = text.substring(text.indexOf("\non:"), text.indexOf("\njobs:"));
        assertTrue(on.contains("\n  pull_request:\n"), workflow + " no longer triggers on pull_request:\n" + on);
        Matcher types = TYPES.matcher(on);
        assertTrue(types.find(), workflow + " lists no pull_request types, so it runs on every push:\n" + on);
        assertEquals("labeled", types.group(1).strip(),
                workflow + " runs on more than the label being added, which reviews unrequested pushes");

        String jobs = text.substring(text.indexOf("\njobs:"));
        String gate = "if: github.event.label.name == '" + LABEL + "'";
        Matcher job = JOB.matcher(jobs);
        assertTrue(job.find(), workflow + " has no job");
        String first = job.find() ? jobs.substring(0, job.start()) : jobs;
        assertTrue(first.contains(gate),
                "the first job of " + workflow + " does not carry `" + gate + "`, so adding any label"
                        + " requests a review:\n" + first);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve(".github"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding pom.xml and .github/) above "
                        + Path.of("").toAbsolutePath());
    }
}
