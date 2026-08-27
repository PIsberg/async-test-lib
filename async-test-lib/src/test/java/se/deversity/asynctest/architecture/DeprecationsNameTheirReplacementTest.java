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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that every deprecated public element tells the caller what to use instead.
 *
 * <p>A major release removes what it deprecated, and the only thing standing between a consumer
 * and that removal is the {@code @deprecated} tag. {@code @Deprecated} on its own compiles to a
 * warning that something is going away; it does not say where the behaviour went, so the consumer
 * reads the diff of a release they did not write. With 188 deprecated public elements across this
 * API, that difference decides whether upgrading to the next major is a scripted rename or an
 * afternoon of guessing.
 *
 * <p>The gap this pins was real rather than hypothetical. Seven of the 146 deprecated
 * {@code @AsyncTest} attributes carried {@code @Deprecated} with no {@code @deprecated} tag at
 * all: {@code detectSharedByteBuffer}, {@code detectSharedCharsetCoder},
 * {@code detectSharedChecksum}, {@code detectFileChannelPositionRace},
 * {@code detectSharedIterator}, {@code detectHighContentionAtomic} and
 * {@code detectSharedJsonMapperReconfig}. Nothing was red, because javadoc's own tooling does not
 * ask this question: doclint checks that tags are well formed, not that a deprecation has a
 * destination.
 *
 * <p>The bar is a named replacement, not prose. A tag passes when it points at something with
 * {@code {@link}} or {@code {@code}}; it fails when it only announces the removal, because
 * "deprecated, will be removed in 2.0" leaves the caller exactly where they started. Nothing here
 * measures wording or length: "Use {@link #excludes()}" is a complete answer.
 *
 * @see JavadocDescribesRatherThanRestatesTest
 */
class DeprecationsNameTheirReplacementTest {

    @Test
    @DisplayName("every deprecated public element names what to use instead")
    void deprecationsNameTheirReplacement() {
        List<String> noTag = new ArrayList<>();
        List<String> noReplacement = new ArrayList<>();
        int deprecations = 0;

        for (Path file : mainSources()) {
            List<String> lines = readLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!"@Deprecated".equals(lines.get(i).trim())) {
                    continue;
                }
                deprecations++;
                String tag = deprecatedTagAbove(lines, i);
                String where = repoRoot().relativize(file) + ":" + (i + 1)
                        + "  " + declaredAfter(lines, i);
                if (tag == null) {
                    noTag.add(where);
                } else if (!tag.contains("{@link") && !tag.contains("{@code")) {
                    noReplacement.add(where + "  -> " + tag.trim());
                }
            }
        }

        assertTrue(deprecations > 100,
                "Expected to find the deprecated public surface but saw only " + deprecations
                        + " elements. The scan is looking in the wrong place, which would let "
                        + "this test pass by checking nothing.");

        assertTrue(noTag.isEmpty(),
                noTag.size() + " deprecated element(s) carry @Deprecated with no @deprecated "
                        + "javadoc tag, so the compiler warns a consumer that they must move "
                        + "without saying where to:" + System.lineSeparator() + "  "
                        + String.join(System.lineSeparator() + "  ", noTag));

        assertTrue(noReplacement.isEmpty(),
                noReplacement.size() + " @deprecated tag(s) announce the removal without naming "
                        + "a replacement. Point at the successor with a link so the migration is "
                        + "a rename rather than a search:" + System.lineSeparator() + "  "
                        + String.join(System.lineSeparator() + "  ", noReplacement));
    }

    /**
     * {@return the text of the {@code @deprecated} tag in the javadoc block above line
     * {@code index}, or {@code null} when the block carries no such tag}
     *
     * @param lines every line of the file being scanned
     * @param index the line holding the {@code @Deprecated} annotation
     */
    private static String deprecatedTagAbove(List<String> lines, int index) {
        int close = index - 1;
        while (close >= 0 && !lines.get(close).trim().startsWith("*/")) {
            String trimmed = lines.get(close).trim();
            boolean stillAbove = trimmed.isEmpty() || trimmed.startsWith("*")
                    || trimmed.startsWith("@");
            if (!stillAbove) {
                return null;
            }
            close--;
        }
        if (close < 0) {
            return null;
        }
        int open = close;
        while (open >= 0 && !lines.get(open).contains("/**")) {
            open--;
        }
        if (open < 0) {
            return null;
        }
        StringBuilder tag = new StringBuilder();
        boolean collecting = false;
        for (int i = open; i <= close; i++) {
            String line = lines.get(i);
            if (line.contains("@deprecated")) {
                collecting = true;
                tag.append(line.substring(line.indexOf("@deprecated") + "@deprecated".length()));
            } else if (collecting) {
                String body = stripLeadingAsterisk(line.trim());
                if (body.startsWith("@") || line.trim().startsWith("*/")) {
                    break;
                }
                tag.append(' ').append(body);
            }
        }
        return collecting ? tag.toString() : null;
    }

    /** {@return {@code line} without a leading javadoc asterisk} */
    private static String stripLeadingAsterisk(String line) {
        return line.startsWith("*") ? line.substring(1).trim() : line;
    }

    /**
     * {@return the declaration below line {@code index}, skipping any blank lines}
     *
     * @param lines every line of the file being scanned
     * @param index the line holding the {@code @Deprecated} annotation
     */
    private static String declaredAfter(List<String> lines, int index) {
        int i = index + 1;
        while (i < lines.size() && lines.get(i).trim().isEmpty()) {
            i++;
        }
        return i < lines.size() ? lines.get(i).trim() : "(end of file)";
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
                        + " files under " + root + ".");
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
