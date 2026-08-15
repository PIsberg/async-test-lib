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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the documentation set navigable: every document is routed from {@code docs/INDEX.md}, and
 * every relative link in the set resolves.
 *
 * <p><strong>The failure this prevents.</strong> A document nobody links to is a document nobody
 * finds, and for an agent that starts from the index it does not exist at all: on 2026-08-15 four
 * real reference documents ({@code SUPPORT_POLICY.md}, {@code architecture/guardrails.md},
 * {@code architecture/logging.md}, {@code diagrams/GENERATE_DIAGRAMS.md}) were unrouted, two of
 * them written that same week and linked only from the architecture hub. A dangling relative link
 * is the same defect from the other side: it sends the reader, human or agent, to a file that
 * moved, and the reader acts on the absence. Both are cheap to check and were never checked.
 *
 * <p>Two rules, both from the documentation checklist this repository follows: the index routes to
 * every document and a test proves nothing is missing; every relative link resolves, checked in CI.
 * Historical write-ups under {@code docs/analysis/} are included in both: they are dated, but they
 * are routed from the index's Analysis section and their links should still land.
 */
class DocsIndexCoverageTest {

    /**
     * Documents under {@code docs/} that the index deliberately does not route.
     *
     * <p>{@code README.md} is a redirect stub that points back at the index; routing the index to it
     * would be a cycle with no reader on it. Add to this list only with a reason of that shape.
     */
    private static final Set<String> UNROUTED_BY_DESIGN = Set.of("README.md");

    /**
     * Files outside {@code docs/} whose links are checked as well: the entry points a reader or an
     * agent opens first, and the module orientation files.
     */
    private static final List<String> ROOT_DOCUMENTS = List.of(
            "README.md", "CLAUDE.md", "AGENTS.md", "CONTRIBUTING.md", "SECURITY.md",
            "async-test-lib/CLAUDE.md", "async-test-agent/CLAUDE.md", "async-test-analysis/CLAUDE.md");

    /**
     * A Markdown link target: {@code ](target)}. Images use the same syntax and are checked too.
     * The target stops at the first whitespace, so an optional title ({@code [t](x "title")}) does
     * not become part of the path.
     */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)");

    @Test
    @DisplayName("docs/INDEX.md routes to every document under docs/")
    void everyDocumentIsRoutedFromTheIndex() {
        Path docs = repoRoot().resolve("docs");
        Path index = docs.resolve("INDEX.md");
        assertTrue(Files.isRegularFile(index), "docs/INDEX.md is missing; it is the routing table "
                + "for the whole documentation set and this test has nothing to check without it.");

        Set<Path> routed = new TreeSet<>();
        Matcher m = LINK.matcher(read(index));
        while (m.find()) {
            String target = stripFragment(m.group(1));
            if (isExternal(target) || target.isEmpty()) {
                continue;
            }
            routed.add(index.getParent().resolve(target).normalize());
        }

        List<String> unrouted = new ArrayList<>();
        for (Path doc : markdownFiles(docs)) {
            if (doc.equals(index)) {
                continue;
            }
            String rel = docs.relativize(doc).toString().replace('\\', '/');
            if (UNROUTED_BY_DESIGN.contains(rel)) {
                continue;
            }
            if (!routed.contains(doc.normalize())) {
                unrouted.add(rel);
            }
        }

        assertTrue(unrouted.isEmpty(),
                "These documents under docs/ are not linked from docs/INDEX.md:\n  "
                        + String.join("\n  ", unrouted)
                        + "\nAdd a row to the table that owns the topic (Using / Understanding / "
                        + "Releasing / Analysis). A document the index does not route to is one an "
                        + "agent starting from the index will never read. If the file is a redirect "
                        + "stub with no content of its own, list it in UNROUTED_BY_DESIGN with the "
                        + "reason.");
    }

    @Test
    @DisplayName("every relative link in the documentation set resolves to an existing file")
    void everyRelativeLinkResolves() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>(markdownFiles(root.resolve("docs")));
        for (String entry : ROOT_DOCUMENTS) {
            Path p = root.resolve(entry);
            if (Files.isRegularFile(p)) {
                files.add(p);
            }
        }

        List<String> broken = new ArrayList<>();
        for (Path file : files) {
            Matcher m = LINK.matcher(read(file));
            while (m.find()) {
                String raw = m.group(1);
                String target = stripFragment(raw);
                if (isExternal(target) || target.isEmpty()) {
                    continue;
                }
                Path resolved = file.getParent().resolve(target).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(root.relativize(file).toString().replace('\\', '/') + " -> " + raw);
                }
            }
        }

        assertTrue(broken.isEmpty(),
                "These relative links point at files that do not exist:\n  "
                        + String.join("\n  ", broken)
                        + "\nFix the link or the file. A dangling link sends the reader to an "
                        + "absence and the reader acts on it; an agent does so without noticing.");
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static String stripFragment(String target) {
        int hash = target.indexOf('#');
        String stripped = hash >= 0 ? target.substring(0, hash) : target;
        // Angle-bracketed targets: [t](<path with spaces>)
        if (stripped.startsWith("<") && stripped.endsWith(">")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static boolean isExternal(String target) {
        return target.startsWith("http://") || target.startsWith("https://")
                || target.startsWith("mailto:") || target.contains("://");
    }

    private static List<Path> markdownFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
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
