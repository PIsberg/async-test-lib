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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that published javadoc says something the signature does not already say.
 *
 * <p>Doclint answers one question: is the tag present. It cannot tell {@code @param timeout the
 * timeout} from a description, so a mechanical pass that adds a tag per parameter turns a build
 * with hundreds of warnings into a green one without a reader learning anything. That is exactly
 * what happened here: closing the warnings left 432 {@code @param} lines, 166 {@code @return}
 * lines and 135 one-line summaries that restated the identifier, all on public members, all
 * published to whoever depends on this library. One of them read {@code /** The totcou races. *}{@code /}
 * over a field recording time-of-check-to-time-of-use races.
 *
 * <p>These tests ask the question doclint cannot. A description fails when, ignoring a leading
 * article, it is nothing but the identifier it is attached to respelled: {@code @param lockName
 * the lock name}, {@code @return the size} on {@code size()}. Anything that adds a unit, a null
 * rule, a range, an identity-versus-equality note, or any other fact a caller could not read off
 * the signature passes.
 *
 * <p>They deliberately do not measure length or wording. A short description can be complete
 * ({@code @return this builder}), and no rule about prose style survives contact with 127
 * detectors. The only thing pinned is that the text is not pure restatement.
 *
 * @see BuildMetadataSyncTest
 */
class JavadocDescribesRatherThanRestatesTest {

    /** Matches a {@code @param} tag, capturing the name and its description. */
    private static final Pattern PARAM =
            Pattern.compile("^\\s*\\*\\s*@param\\s+<?(\\w+)>?\\s+(\\S.*?)\\s*$");

    /** Matches a {@code @return} tag, capturing its description. */
    private static final Pattern RETURN =
            Pattern.compile("^\\s*\\*\\s*@return\\s+(\\S.*?)\\s*$");

    /** Matches a whole javadoc block written on one line, capturing its text. */
    private static final Pattern SUMMARY =
            Pattern.compile("^\\s*/\\*\\*\\s*(.+?)\\s*\\*/\\s*$");

    /** Matches the name of the member a javadoc block sits on: a method, constructor or field. */
    private static final Pattern DECLARED_MEMBER =
            Pattern.compile("(\\w+)\\s*(?:[;=]|\\()");

    /** Splits an identifier into its words, so {@code lockName} and "lock name" compare equal. */
    private static final Pattern IDENTIFIER_WORDS = Pattern.compile("(?<!^)(?=[A-Z])");

    /** A leading article carries no information and is ignored when comparing. */
    private static final Pattern LEADING_ARTICLE = Pattern.compile("^(the|a|an)\\s+");

    @Test
    @DisplayName("no @param or @return in main sources merely restates the identifier")
    void tagsDescribeRatherThanRestate() {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            offenders.addAll(restatementsIn(file));
        }
        assertTrue(offenders.isEmpty(),
                "These javadoc tags only respell the identifier they document, so they reach "
                        + "consumers without saying anything the signature does not. Give the "
                        + "unit, the null rule, the range, or what the value is used for:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("no one-line javadoc summary in main sources merely restates the identifier")
    void summariesDescribeRatherThanRestate() {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = SUMMARY.matcher(lines.get(i));
                if (!m.matches() || m.group(1).startsWith("{@return")) {
                    continue;
                }
                String subject = declaredNameAfter(lines, i);
                if (subject != null && restates(subject, m.group(1))) {
                    offenders.add(report(file, i, lines.get(i)));
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "These javadoc summaries only respell the member they describe. A public field "
                        + "surfaced in a report has to say what its entries mean, not repeat its "
                        + "own name:\n  " + String.join("\n  ", offenders));
    }

    /** {@return every restating {@code @param} or {@code @return} tag in {@code file}} */
    private static List<String> restatementsIn(Path file) {
        List<String> lines = readLines(file);
        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher param = PARAM.matcher(line);
            if (param.matches() && restates(param.group(1), param.group(2))) {
                offenders.add(report(file, i, line));
                continue;
            }

            Matcher ret = RETURN.matcher(line);
            if (ret.matches()) {
                String subject = declaredNameAfter(lines, i);
                if (subject != null && restates(subject, ret.group(1))) {
                    offenders.add(report(file, i, line));
                }
            }
        }
        return offenders;
    }

    /**
     * Whether {@code description} is nothing but {@code identifier} respelled.
     *
     * @param identifier  the member or parameter name the text is attached to
     * @param description the text as written, without its tag
     * @return {@code true} when the description adds nothing to the identifier
     */
    private static boolean restates(String identifier, String description) {
        String stripped = LEADING_ARTICLE
                .matcher(description.toLowerCase(Locale.ROOT).trim())
                .replaceFirst("")
                .replaceAll("\\.$", "")
                .trim();
        String words = IDENTIFIER_WORDS.matcher(identifier).replaceAll(" ").toLowerCase(Locale.ROOT);
        return stripped.equals(words)
                || stripped.replace(" ", "").equals(words.replace(" ", ""));
    }

    /**
     * The member declared after the javadoc block containing line {@code i}, skipping blank
     * lines, comments and annotations.
     *
     * @param lines the file's lines
     * @param i     the index of the tag or summary being judged
     * @return the declared name, or {@code null} when nothing recognisable follows the block
     */
    private static String declaredNameAfter(List<String> lines, int i) {
        int end = i;
        while (end < lines.size() && !lines.get(end).contains("*/")) {
            end++;
        }
        int decl = end + 1;
        while (decl < lines.size()) {
            String candidate = lines.get(decl).trim();
            if (candidate.isEmpty() || candidate.startsWith("//") || candidate.startsWith("@")) {
                decl++;
                continue;
            }
            Matcher m = DECLARED_MEMBER.matcher(candidate);
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

    /** {@return a {@code path:line} report for the javadoc on line {@code i}} */
    private static String report(Path file, int i, String line) {
        return repoRoot().relativize(file) + ":" + (i + 1) + "  " + line.trim();
    }

    /** {@return every {@code .java} file under any published module's {@code src/main/java}} */
    private static List<Path> mainSources() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        for (String module : List.of("async-test-lib", "async-test-agent", "async-test-analysis")) {
            Path src = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(src)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not walk " + src, e);
            }
        }
        assertTrue(files.size() > 100,
                "Expected to scan the published sources but found only " + files.size()
                        + " files under " + root + ". The test is looking in the wrong place, "
                        + "which would let it pass by scanning nothing.");
        return files;
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    /** {@return the reactor root, found by walking up to the directory holding the parent pom} */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))
                    && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding both pom.xml and "
                        + "settings.gradle.kts) above " + Path.of("").toAbsolutePath());
    }
}
