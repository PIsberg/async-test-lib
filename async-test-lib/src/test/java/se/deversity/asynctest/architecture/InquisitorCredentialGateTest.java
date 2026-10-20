package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Inquisitor accepts either model credential, and the one it accepts reaches the model.
 *
 * <p><strong>The failure this prevents, and it had already happened.</strong> The adversarial
 * review lane in {@code inquisitor.yml} is gated by a {@code preflight} job, because
 * {@code jobs.<id>.if} cannot read secrets: with no credential the review job reads Skipped rather
 * than a green tick it did not earn (#868). The preflight looked only for {@code ANTHROPIC_API_KEY}.
 * This repository holds neither that nor a subscription token, and every Inquisitor run so far
 * concluded success with the Adversarial Review job skipped. vibetags runs the same lane on a
 * {@code CLAUDE_CODE_OAUTH_TOKEN} from {@code claude setup-token}, which this preflight would have
 * ignored even once the secret existed.
 *
 * <p>The two halves have to agree. A preflight that accepts the token while the model step is
 * handed only the API key starts a review with no credential, which fails as a harness error on
 * every pull request; a step handed the token behind a preflight that never looks for it is dead
 * code. Both drift silently, since nothing else in the build reads the workflow.
 */
class InquisitorCredentialGateTest {

    private static final String API_KEY = "secrets.ANTHROPIC_API_KEY";
    private static final String OAUTH_TOKEN = "secrets.CLAUDE_CODE_OAUTH_TOKEN";

    @Test
    @DisplayName("the Inquisitor preflight accepts either secret and the review step receives both")
    void eitherCredentialReachesTheModel() throws IOException {
        Path file = repoRoot().resolve(".github/workflows/inquisitor.yml");
        assertTrue(Files.isRegularFile(file), file + " is missing");
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");

        String preflight = section(text, "  preflight:", "\n  [a-z]");
        int has = preflight.indexOf("HAS_KEY:");
        assertTrue(has >= 0, "the preflight no longer sets HAS_KEY:\n" + preflight);
        String condition = preflight.substring(has, preflight.indexOf('\n', has));
        assertTrue(condition.contains(API_KEY) && condition.contains(OAUTH_TOKEN),
                "the preflight does not accept both credentials, so a repository holding only the"
                        + " other one skips the review while looking configured: " + condition);

        String step = section(text, "- name: Run the Inquisitor", "\n      - ");
        assertTrue(step.contains(API_KEY) && step.contains(OAUTH_TOKEN),
                "the step that runs the model is not handed both credentials, so the preflight can"
                        + " start a review that has none:\n" + step);
    }

    /** From {@code start} to the next match of {@code endRegex}, or the end of the file. */
    private static String section(String text, String start, String endRegex) {
        int at = text.indexOf(start);
        assertTrue(at >= 0, "inquisitor.yml has no '" + start.strip() + "'; if it was renamed, update this test");
        Matcher end = Pattern.compile(endRegex).matcher(text);
        int stop = end.find(at + start.length()) ? end.start() : text.length();
        return text.substring(at, stop);
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
