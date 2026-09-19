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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every statement of this library's licence in the docs to the one the POM publishes.
 *
 * <p><strong>The failure this prevents.</strong> The library is PolyForm Noncommercial, and
 * commercial use needs a paid key. Until #681, {@code USAGE.md}, {@code DISTRIBUTION.md} and
 * {@code QUICK_REFERENCE.md} all said MIT, including a {@code pom.xml} snippet that claimed to show
 * the published metadata. A reader deciding whether they may use the library at work reads a
 * guide, not the LICENSE file, and MIT tells them no key is needed. That is the one claim a
 * compliance aid cannot afford to get wrong.
 *
 * <p>The published name comes from the root {@code pom.xml}, which is what Maven Central shows,
 * so a licence change moves every doc with it or fails here. {@code DEPENDENCIES.md} names the
 * licences of third-party libraries and is excluded; the changelog and {@code analysis/} are
 * historical records.
 */
class LicenseClaimConsistencyTest {

    /** The licence name the POM publishes: {@code <licenses><license><name>}. */
    private static final Pattern POM_LICENSE =
            Pattern.compile("<licenses>\\s*<license>\\s*<name>([^<]+)</name>");

    /**
     * A statement of this project's licence by name: "MIT License", "License: MIT", or the
     * {@code <license><name>} element of a POM snippet.
     */
    private static final Pattern CLAIM = Pattern.compile(
            "(?i)<license>\\s*<name>([^<]+)</name>"
                    + "|\\blicen[sc]e:\\s+([A-Z][\\w.\\- ]+?)\\s*$"
                    + "|\\b(MIT|Apache|BSD|GPL|LGPL|EPL)\\b[\\w.\\- ]{0,10}\\blicen[sc]e\\b",
            Pattern.MULTILINE);

    /** Files that name other projects' licences, or record what was true at the time. */
    private static final List<String> EXCLUDED = List.of("DEPENDENCIES.md", "CHANGELOG.md");

    @Test
    @DisplayName("every licence named in the docs is the one the POM publishes")
    void docsNameThePublishedLicence() {
        Path root = repoRoot();
        Matcher pom = POM_LICENSE.matcher(read(root.resolve("pom.xml")));
        assertTrue(pom.find(), "pom.xml declares no <licenses><license><name>; this test has "
                + "nothing to compare the docs against.");
        String published = pom.group(1).trim();
        assertEquals("PolyForm Noncommercial License 1.0.0", published,
                "The published licence changed. Update this assertion deliberately, then let the "
                        + "test below show every document that still names the old one.");

        List<Path> files = new ArrayList<>(markdownFiles(root.resolve("docs")));
        files.add(root.resolve("README.md"));

        List<String> wrong = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (EXCLUDED.contains(name) || file.toString().contains("analysis")) {
                continue;
            }
            Matcher m = CLAIM.matcher(read(file));
            while (m.find()) {
                String claim = m.group().trim();
                if (!claim.toLowerCase(java.util.Locale.ROOT).contains("polyform")) {
                    wrong.add(root.relativize(file).toString().replace('\\', '/') + ": " + claim);
                }
            }
        }

        assertTrue(wrong.isEmpty(),
                "These documents name a licence other than the published " + published + ":\n  "
                        + String.join("\n  ", wrong)
                        + "\nA reader deciding whether they may use the library reads the guide, "
                        + "not LICENSE. Name the published licence, and point commercial users at "
                        + "the README's License section.");
    }

    private static List<Path> markdownFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not find the reactor root (a directory holding pom.xml and docs/) above "
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
