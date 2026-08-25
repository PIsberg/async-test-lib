package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Requires every switched-off example demonstration to say why it is switched off.
 *
 * <p><strong>What this protects.</strong> Most of the examples disable their {@code @AsyncTest}
 * demonstration on purpose: it demonstrates code that fails, so leaving it on would make the
 * examples pipeline permanently red. That is a reasonable trade, but it has a failure mode. A
 * demonstration that was disabled because it broke looks exactly like one disabled because it is
 * meant to fail, and the pipeline stays green either way. The reason string is the only thing that
 * tells the two apart, so it is required rather than encouraged.
 *
 * <p>The count is deliberately not pinned. Adding an example should not fail this test; disabling
 * one without explaining should.
 */
class ExampleDisabledDemoTest {

    /** Matches {@code @Disabled} used as an annotation, capturing its argument list if any. */
    private static final Pattern DISABLED_ANNOTATION = Pattern.compile(
            "^\\s*@Disabled\\s*(\\((.*)\\))?\\s*$", Pattern.MULTILINE);

    @Test
    @DisplayName("every @Disabled in the examples carries a reason")
    void disabledDemonstrationsExplainThemselves() {
        Path examples = repoRoot().resolve("examples");
        if (!Files.isDirectory(examples)) {
            throw new IllegalStateException(
                    "examples/ is missing. If the examples moved, point this test at the new "
                            + "location rather than deleting it.");
        }

        List<String> unexplained = new ArrayList<>();
        int explained = 0;

        for (Path file : javaFilesUnder(examples)) {
            Matcher m = DISABLED_ANNOTATION.matcher(read(file));
            while (m.find()) {
                String argument = m.group(2);
                if (argument == null || argument.isBlank() || argument.replace("\"", "").isBlank()) {
                    unexplained.add(repoRoot().relativize(file).toString().replace('\\', '/'));
                } else {
                    explained++;
                }
            }
        }

        assertTrue(unexplained.isEmpty(),
                "These disable a test without saying why: " + unexplained
                        + ". A bare @Disabled is indistinguishable from one that was switched off "
                        + "because it broke. Give it a reason, as the other "
                        + explained + " do.");

        assertTrue(explained > 0,
                "No @Disabled annotations were found in examples/ at all. Either every "
                        + "demonstration is now enabled, which would be worth saying in "
                        + "examples/README.md, or this test's pattern stopped matching and it is "
                        + "checking nothing.");
    }

    /**
     * Requires every switched-off demonstration to be able to fail once it is switched on.
     *
     * <p><strong>The failure this prevents, measured rather than imagined.</strong> On 2026-08-25
     * every {@code @Disabled} demonstration in {@code examples/} was enabled and the reactor run:
     * 72 of 97 <em>passed</em>. They advertise "Remove {@code @Disabled} to see X detected by
     * YDetector", and a reader who followed that instruction got a green test. The reason is a
     * default: {@link se.deversity.asynctest.FailOn} is {@code NONE}, which reports a finding
     * without failing the run, and no example set it. Adding {@code failOn} took the count that
     * fail reliably from 25 to 66.
     *
     * <p>So a demonstration that cannot fail is a demonstration that proves nothing, and the
     * pipeline could never notice because these tests are disabled. This is the static half of
     * that check: it costs nothing to run and it holds for every example, whereas actually
     * enabling them all is a deliberate exercise rather than a build step.
     *
     * <p>{@code minTrust} is deliberately not required. Its default is
     * {@link se.deversity.asynctest.diagnostics.TrustTier#ADVISORY}, the lowest tier, so every
     * detector already passes that filter; requiring it would be cargo cult.
     */
    @Test
    @DisplayName("every disabled @AsyncTest demonstration sets failOn, so enabling it can fail")
    void disabledDemonstrationsCanActuallyFailWhenEnabled() {
        Path examples = repoRoot().resolve("examples");
        List<String> cannotFail = new ArrayList<>();
        int checked = 0;

        for (Path file : javaFilesUnder(examples)) {
            List<String> lines = List.of(read(file).split("\r?\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).strip().startsWith("@Disabled")) {
                    continue;
                }
                String annotation = asyncTestAnnotationAfter(lines, i);
                if (annotation == null) {
                    continue;   // a @Disabled on something that is not an @AsyncTest demo
                }
                checked++;
                if (!annotation.contains("failOn")) {
                    cannotFail.add(repoRoot().relativize(file).toString().replace('\\', '/')
                            + ":" + (i + 1));
                }
            }
        }

        assertTrue(cannotFail.isEmpty(),
                "These disabled @AsyncTest demonstrations do not set failOn, so removing "
                        + "@Disabled prints the detector report and leaves the test green - which "
                        + "is the opposite of what their README tells a reader to expect:\n  "
                        + String.join("\n  ", cannotFail)
                        + "\nAdd failOn = FailOn.LOW to the @AsyncTest, as the other "
                        + (checked - cannotFail.size()) + " do.");

        assertTrue(checked > 0,
                "No disabled @AsyncTest demonstrations were found at all. Either every "
                        + "demonstration is enabled now, or this scan stopped matching and is "
                        + "checking nothing.");
    }

    /**
     * {@return the whole {@code @AsyncTest(...)} annotation following {@code start}, or null}
     *
     * <p>Accumulates lines until the parentheses balance, because the examples wrap long
     * attribute lists over several lines and a line-at-a-time check would miss the attribute it
     * is looking for.
     */
    private static String asyncTestAnnotationAfter(List<String> lines, int start) {
        int at = -1;
        for (int i = start + 1; i < Math.min(start + 12, lines.size()); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("@AsyncTest")) {
                at = i;
                break;
            }
            if (line.startsWith("void ") || line.contains(" void ")) {
                return null;    // reached the method without finding one
            }
        }
        if (at < 0) {
            return null;
        }
        StringBuilder annotation = new StringBuilder();
        int depth = 0;
        for (int i = at; i < lines.size(); i++) {
            String line = lines.get(i);
            annotation.append(line);
            for (char c : line.toCharArray()) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth <= 0) {
                break;
            }
        }
        return annotation.toString();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static List<Path> javaFilesUnder(Path start) {
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + start, e);
        }
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
        throw new IllegalStateException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
