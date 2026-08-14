package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code registerX} method must not discard what has already been observed about its subject.
 *
 * <p><strong>Why this exists.</strong> Registration reads like a setup step, but an
 * {@code @AsyncTest} body runs once per thread - so a consumer who registers inside the body
 * registers once per worker. A registration that installs a fresh state object each time then
 * resets the accumulated observations, and every finding phrased as "more than one thread touched
 * this" becomes unreachable: each worker's accesses end up in state that saw a single thread.
 *
 * <p>The failure is invisible in the ordinary way. The detector is registered, enabled, reachable
 * and unit-tested; it simply never reports. Worse, whether it reports depends on interleaving and
 * on identity hash codes, so it is not even reliably absent - three separate detectors surfaced
 * this way on one CI leg each while passing everywhere else, one of them only on JUnit 5.9.3.
 *
 * <p>Eighteen registrations were fixed at once. This gate is what stops the nineteenth: the shape
 * is easy to write, reads as obviously correct, and cannot be caught by any single-threaded test.
 *
 * <h4>What this does not cover</h4>
 *
 * <p>Only {@code register*}. A {@code record*} method that begins an episode - a compound
 * operation, an optimistic read, a scope - may legitimately replace what was there, and those were
 * deliberately left alone rather than changed without evidence. If one of them turns out to have
 * the same problem it needs its own reasoning, not an entry here.
 */
@DisplayName("registerX must be idempotent: re-registering keeps earlier observations")
class RegistrationIsIdempotentTest {

    private static final Path DETECTOR_DIR =
            Path.of("src/main/java/se/deversity/asynctest/diagnostics");

    /** A {@code public void registerX(...) { ... }} method and its body. */
    private static final Pattern REGISTER_METHOD = Pattern.compile(
            "public (?:synchronized )?void (register\\w+)\\([^)]*\\)\\s*\\{(.*?)\\n    \\}",
            Pattern.DOTALL);

    /** A {@code map.put(key, new State(...))} - installing freshly built state. */
    private static final Pattern INSTALLS_FRESH_STATE = Pattern.compile(
            "\\w+\\.put\\((?:[^;]*?)(?:new \\w+(?:<[^>]*>)?\\(|newKeySet\\(\\))",
            Pattern.DOTALL);

    @Test
    @DisplayName("no registerX installs fresh state unconditionally")
    void registrationDoesNotDiscardEarlierObservations() throws IOException {
        assertTrue(Files.isDirectory(DETECTOR_DIR),
                "Detector sources not found at " + DETECTOR_DIR.toAbsolutePath()
                        + ". The layout moved and this gate is inspecting nothing.");

        List<String> offenders = new ArrayList<>();
        int inspected = 0;

        try (Stream<Path> files = Files.list(DETECTOR_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher method = REGISTER_METHOD.matcher(source);
                while (method.find()) {
                    inspected++;
                    String name = method.group(1);
                    String body = method.group(2);
                    if (!INSTALLS_FRESH_STATE.matcher(body).find()) {
                        continue;   // does not install state; nothing to discard
                    }
                    boolean guarded = body.contains("putIfAbsent")
                            || body.contains("computeIfAbsent")
                            || (body.contains("!= null") && body.contains("return"));
                    if (!guarded) {
                        offenders.add(file.getFileName() + " :: " + name);
                    }
                }
            }
        }

        assertTrue(inspected >= 15,
                "Found only " + inspected + " registerX methods. The detector package holds far "
                        + "more, so the pattern has stopped matching and this gate is inspecting "
                        + "nothing. Fix the pattern, not the number.");

        assertTrue(offenders.isEmpty(),
                "These registrations install fresh state unconditionally, so registering the "
                        + "same subject twice discards everything observed about it since the "
                        + "first time:\n  " + String.join("\n  ", offenders)
                        + "\n\nAn @AsyncTest body runs once per thread, so a consumer registering "
                        + "inside it registers once per worker - and every finding phrased as "
                        + "\"more than one thread touched this\" then becomes unreachable, "
                        + "because each worker's accesses land in state that saw one thread.\n\n"
                        + "Use putIfAbsent or computeIfAbsent. Where the key is an identity-based "
                        + "wrapper rather than the subject itself, look the subject up first and "
                        + "return early - VolatileArrayDetector.registerArray is the worked "
                        + "example.");
    }
}
