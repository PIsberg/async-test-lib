package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when a detector inserts into a shared map only because a lookup found nothing.
 *
 * <p>Why this gate exists: a detector's record path runs on the racing threads themselves, so a
 * conditional insert there is a check-then-act between the very threads the detector is watching.
 * Several threads reach a brand new key together, all find nothing, all build a state object, and
 * all but the last are discarded by the insert that follows. Each thread then counts into an
 * object no longer reachable from the map, and the detector's own numbers come out short. Nothing
 * throws. The report simply says fewer threads and fewer accesses than there were, which is the
 * direction that makes a detector go quiet on the contention it exists to find.
 *
 * <p>Both shapes below have already shipped in this repository once each, which is why they are
 * worth a standing gate rather than a review habit:
 *
 * <ul>
 *   <li>{@code SimpleDateFormatDetector} had the get / null-check / put form. Its record path now
 *       carries a comment describing exactly what was lost.</li>
 *   <li>{@code HttpClientConcurrencyDetector} had the {@code orElseGet} form, which accounted for
 *       352 of 400 recorded requests under four threads before it was fixed.</li>
 * </ul>
 *
 * <p>The window is one round wide in both cases: once the entry exists every later lookup is a
 * hit, so a single-shot test never reaches it and only a barrier-forced collision on a fresh
 * instance does. That makes this cheaper to enforce structurally than to test per detector, which
 * is the point of doing it here rather than writing 147 concurrency tests.
 *
 * <p><strong>What this gate does not do.</strong> It recognises two concrete shapes, not
 * check-then-act in general. It reads source text rather than bytecode, because asm is confined to
 * {@code async-test-analysis} by {@code ArchitectureTest}. A conditional insert written a third
 * way passes here, so this narrows the opening rather than closing it. It is deliberately blind to
 * unconditional puts, which are last-writer-wins on purpose in several detectors.
 */
class DetectorConditionalInsertTest {

    /** How far after a null check the matching insert is still taken to belong to it. */
    private static final int WINDOW = 800;

    private static final Pattern GET_ASSIGNMENT =
            Pattern.compile("(\\w+)\\s*=\\s*([\\w.]+)\\.get\\(");

    @Test
    void noDetectorInsertsIntoASharedMapOnlyBecauseALookupFoundNothing() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String source = maskCommentsAndLiterals(Files.readString(file));
            offences.addAll(getThenPut(file, source));
            offences.addAll(orElseGetThenPut(file, source));
        }

        assertTrue(offences.isEmpty(),
                "A detector inserts into a shared map only when a lookup found nothing. Racing "
                        + "threads all find nothing and all but the last are discarded, so the "
                        + "detector undercounts its own evidence and can go silent. Use "
                        + "computeIfAbsent or putIfAbsent instead:\n  "
                        + String.join("\n  ", offences));
    }

    /** The get / null-check / put form. */
    private static List<String> getThenPut(Path file, String source) {
        List<String> offences = new ArrayList<>();
        Matcher assignment = GET_ASSIGNMENT.matcher(source);
        while (assignment.find()) {
            String variable = assignment.group(1);
            String receiver = assignment.group(2);
            String tail = source.substring(assignment.end(),
                    Math.min(source.length(), assignment.end() + WINDOW));

            Matcher nullCheck = Pattern.compile("if\\s*\\(\\s*" + Pattern.quote(variable)
                    + "\\s*==\\s*null\\s*\\)").matcher(tail);
            if (!nullCheck.find()) {
                continue;
            }
            String afterCheck = tail.substring(nullCheck.end());
            if (Pattern.compile(Pattern.quote(receiver) + "\\.put\\(").matcher(afterCheck).find()) {
                offences.add(file.getFileName() + ":" + lineOf(source, assignment.start())
                        + " " + receiver + ".get(...) then null check then " + receiver + ".put(...)");
            }
        }
        return offences;
    }

    /** The {@code orElseGet(() -> { ... put ... })} form. */
    private static List<String> orElseGetThenPut(Path file, String source) {
        List<String> offences = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = source.indexOf("orElseGet(", from);
            if (start < 0) {
                return offences;
            }
            int open = start + "orElseGet".length();
            int close = matchingParen(source, open);
            if (close < 0) {
                return offences;
            }
            String lambda = source.substring(open, close);
            if (Pattern.compile("\\.put\\(").matcher(lambda).find()) {
                offences.add(file.getFileName() + ":" + lineOf(source, start)
                        + " orElseGet(...) whose fallback puts into a map");
            }
            from = close;
        }
    }

    private static int matchingParen(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@return {@code source} with comments, string literals, text blocks and char literals blanked}
     *
     * <p>Length and line breaks are preserved so reported line numbers stay true. Blanking is not
     * optional: several detectors carry the very shape this gate forbids inside their Javadoc, as
     * the illustration of the bug being described, and a scan over raw text reports those.
     */
    private static String maskCommentsAndLiterals(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                while (i < out.length && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                for (int j = 0; j < 2 && i < out.length; j++) {
                    out[i++] = ' ';
                }
            } else if (c == '"' && i + 2 < out.length && out[i + 1] == '"' && out[i + 2] == '"') {
                out[i++] = ' ';
                out[i++] = ' ';
                out[i++] = ' ';
                while (i + 2 < out.length
                        && !(out[i] == '"' && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                for (int j = 0; j < 3 && i < out.length; j++) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < out.length && out[i] != quote) {
                    boolean escaped = out[i] == '\\';
                    out[i] = ' ';
                    i++;
                    if (escaped && i < out.length) {
                        out[i++] = ' ';
                    }
                }
                if (i < out.length) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** {@return every detector source, plus the agent hooks that feed them} */
    private static List<Path> scannedFiles() throws IOException {
        Path main = repoRoot().resolve(Path.of("async-test-lib", "src", "main", "java",
                "se", "deversity", "asynctest"));
        List<Path> files = new ArrayList<>();
        try (Stream<Path> detectors = Files.list(main.resolve("diagnostics"))) {
            detectors.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        try (Stream<Path> hooks = Files.list(main)) {
            hooks.filter(p -> p.getFileName().toString().startsWith("Agent")
                    && p.toString().endsWith("Hooks.java")).forEach(files::add);
        }
        assertTrue(files.size() > 100, "expected the detector sources, found " + files.size());
        return files;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException(
                "Could not find the reactor root above " + Path.of("").toAbsolutePath()));
    }
}
