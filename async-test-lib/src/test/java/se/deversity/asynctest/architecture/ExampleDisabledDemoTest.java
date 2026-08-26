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

    /** Matches a JUnit assertion or an explicit {@code fail(...)} call. */
    private static final Pattern ASSERTION = Pattern.compile("\\b(assert\\w+|fail)\\s*\\(");

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
     * Requires a demonstration to leave the verdict to the run, not to its own body.
     *
     * <p><strong>The failure this prevents, measured rather than imagined.</strong> Two
     * demonstrations analyzed a detector inside the test body and asserted on the result:
     * {@code examples/26-future-blocking} and {@code examples/28-lazy-init}. Both were wrong in
     * the same two ways, and each way hid the other.
     *
     * <p>The assertion races the run. Eight threads are inside one body at the barrier, and the
     * first to reach {@code analyze()} sees only its own recording, so it asserts that a finding
     * exists before the other seven have recorded anything. Nothing about the code under test
     * decides whether that assertion holds.
     *
     * <p>And the detector was the wrong one. Both fed a locally constructed instance, which the
     * runner never reads, so the {@code failOn} gate had nothing to gate on and the demonstration
     * could not have failed on a finding however the subject behaved. That is the defect issue
     * #346 catalogued fourteen times over, and these two escaped that audit for a reason worth
     * remembering: the audit looked for demonstrations that <em>passed</em>, and these failed, on
     * their own broken assertion. A demonstration failing for the wrong reason looks healthy from
     * far enough away. See issue #363.
     *
     * <p>Narrow on purpose. Twenty-eight demonstrations assert something in their body and most
     * are harmless, so the rule is not "no assertions in a demonstration"; it is that a
     * demonstration must not both run the analysis and judge it. That pair cannot be right.
     */
    @Test
    @DisplayName("no disabled demonstration analyzes a detector and asserts on it in its own body")
    void demonstrationsDoNotJudgeTheirOwnDetector() {
        Path examples = repoRoot().resolve("examples");
        List<String> selfJudging = new ArrayList<>();
        int checked = 0;

        for (Path file : javaFilesUnder(examples)) {
            List<String> lines = List.of(read(file).split("\r?\n", -1));
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).strip().startsWith("@Disabled")) {
                    continue;
                }
                if (asyncTestAnnotationAfter(lines, i) == null) {
                    continue;   // a @Disabled on something that is not an @AsyncTest demo
                }
                checked++;
                String body = demonstrationBodyAfter(lines, i);
                if (body.contains(".analyze()") && ASSERTION.matcher(body).find()) {
                    selfJudging.add(repoRoot().relativize(file).toString().replace('\\', '/')
                            + ":" + (i + 1));
                }
            }
        }

        assertTrue(selfJudging.isEmpty(),
                "These disabled demonstrations call analyze() and assert on the result inside "
                        + "the test body:\n  " + String.join("\n  ", selfJudging)
                        + "\nThe first thread through gets there before its peers have recorded "
                        + "anything, so the assertion is about the scheduler rather than about "
                        + "the code under test. Record into the detector the run owns "
                        + "(AsyncTestContext.someDetector()) and let failOn decide.");

        assertTrue(checked > 0,
                "No disabled @AsyncTest demonstrations were found at all. Either every "
                        + "demonstration is enabled now, or this scan stopped matching and is "
                        + "checking nothing.");
    }

    /** {@return the source of the demonstration whose {@code @Disabled} sits at {@code start}} */
    private static String demonstrationBodyAfter(List<String> lines, int start) {
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < Math.min(start + 250, lines.size()); i++) {
            String line = lines.get(i);
            body.append(line).append('\n');
            if (line.indexOf('{') >= 0) {
                opened = true;
            }
            if (opened) {
                for (char c : line.toCharArray()) {
                    if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                    }
                }
                if (depth <= 0) {
                    break;
                }
            }
        }
        return body.toString();
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
