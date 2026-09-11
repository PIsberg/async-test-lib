package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps every {@code @AIKeepInSync(mirrors = ...)} entry pointing at something that exists.
 *
 * <p><strong>Why this exists.</strong> The whole value of a {@code mirrors} entry is that it
 * names the file you have to open next. Nothing checked those names: {@code mirrors} is free
 * text, so a rename left the guardrail pointing at a type that is not there, and the likeliest
 * reaction to an unresolvable name is to assume the mirror is stale and move on. That is the
 * one failure mode a keep-in-sync guardrail exists to prevent. {@code DetectorType} carried
 * {@code se.deversity.asynctest.spi.LegacyDetectorFactories} after the class moved to
 * {@code spi.adapters}, and no gate noticed.
 *
 * <p>{@code @AIKeepInSync} is {@link java.lang.annotation.RetentionPolicy#SOURCE}, so there is
 * no reflective route to it and this gate reads the source files. Scanning source also lets it
 * cover modules the library is forbidden to depend on.
 *
 * <p>An entry resolves three ways, because all three are in use: a Java type, found as a
 * {@code .java} file under any module source root; a repo-relative path such as
 * {@code docs/DETECTOR_CATALOG.md}; or a classpath resource such as
 * {@code META-INF/services/...}, found under any module resource root.
 */
class KeepInSyncMirrorsResolveTest {

    private static final char QUOTE = '"';
    private static final String ANNOTATION = "@AIKeepInSync";

    @Test
    @DisplayName("every @AIKeepInSync mirrors entry resolves to a type, a file or a resource")
    void everyMirrorResolves() throws IOException {
        Path root = repoRoot();
        Map<String, List<String>> unresolved = new LinkedHashMap<>();

        for (Map.Entry<Path, List<String>> declaration : mirrorsByFile(root).entrySet()) {
            for (String mirror : declaration.getValue()) {
                if (!resolves(root, mirror)) {
                    unresolved.computeIfAbsent(
                            root.relativize(declaration.getKey()).toString(),
                            key -> new ArrayList<>()).add(mirror);
                }
            }
        }

        assertTrue(unresolved.isEmpty(),
                "A mirrors entry that resolves to nothing is worse than no mirror at all: the "
                        + "reader concludes the guardrail is stale and stops trusting the rest. "
                        + "Fix the name, or drop the entry if the partner is gone. Unresolved: "
                        + System.lineSeparator() + render(unresolved));
    }

    @Test
    @DisplayName("the scanner actually finds the annotations it claims to check")
    void scannerIsNotVacuous() throws IOException {
        Map<Path, List<String>> found = mirrorsByFile(repoRoot());
        int entries = found.values().stream().mapToInt(List::size).sum();

        // Without this, a parser that silently matches nothing turns the gate above green
        // forever. Both numbers are floors, not pins, so adding an annotation never fails.
        assertFalse(found.isEmpty(), "found no " + ANNOTATION + " declarations at all, so the "
                + "source scanner is broken rather than the repo being clean");
        assertTrue(entries >= 6,
                "expected at least 6 mirrors entries across the reactor, found " + entries
                        + ". Either the scanner stopped matching, or annotations were removed "
                        + "and this floor needs lowering deliberately.");
    }

    private static Map<Path, List<String>> mirrorsByFile(Path root) throws IOException {
        Map<Path, List<String>> byFile = new LinkedHashMap<>();
        for (Path sourceRoot : roots(root, "src/main/java", "src/test/java")) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            List<String> mirrors = mirrorsIn(read(path));
                            if (!mirrors.isEmpty()) {
                                byFile.put(path, mirrors);
                            }
                        });
            }
        }
        return byFile;
    }

    /**
     * Pulls the string literals out of every {@code mirrors} block belonging to an
     * {@code @AIKeepInSync}. Deliberately not a regex: the annotation spans lines and its
     * reason is concatenated prose full of punctuation, so a pattern coping with both is
     * harder to read, and harder to trust, than a scan.
     */
    private static List<String> mirrorsIn(String source) {
        List<String> mirrors = new ArrayList<>();
        int at = source.indexOf(ANNOTATION);
        while (at >= 0) {
            int cursor = at + ANNOTATION.length();
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            // A javadoc mention is not a declaration: require the open paren to follow the
            // name directly, or the scan drifts into the next unrelated parenthesis in the file.
            if (cursor >= source.length() || source.charAt(cursor) != '(') {
                at = source.indexOf(ANNOTATION, at + ANNOTATION.length());
                continue;
            }
            int open = cursor;
            int close = matchingParen(source, open);
            if (close < 0) {
                break;
            }
            String body = source.substring(open + 1, close);
            int mirrorsAt = body.indexOf("mirrors");
            if (mirrorsAt >= 0) {
                int braceOpen = body.indexOf('{', mirrorsAt);
                int braceClose = braceOpen < 0 ? -1 : body.indexOf('}', braceOpen);
                if (braceOpen >= 0 && braceClose > braceOpen) {
                    mirrors.addAll(literals(body.substring(braceOpen + 1, braceClose)));
                }
            }
            at = source.indexOf(ANNOTATION, close);
        }
        return mirrors;
    }

    private static int matchingParen(String source, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == QUOTE) {
                inString = !inString;
            } else if (!inString && c == '(') {
                depth++;
            } else if (!inString && c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> literals(String block) {
        List<String> found = new ArrayList<>();
        int i = 0;
        while (i < block.length()) {
            int start = block.indexOf(QUOTE, i);
            int end = start < 0 ? -1 : block.indexOf(QUOTE, start + 1);
            if (end < 0) {
                break;
            }
            String literal = block.substring(start + 1, end).trim();
            if (!literal.isEmpty()) {
                found.add(literal);
            }
            i = end + 1;
        }
        return found;
    }

    private static boolean resolves(Path root, String mirror) throws IOException {
        return typeExists(root, mirror)
                || Files.exists(root.resolve(mirror))
                || resourceExists(root, mirror);
    }

    /**
     * Walks the name down a segment at a time, so a nested type or an enum constant still
     * resolves through its declaring file while a wrong package resolves through nothing.
     */
    private static boolean typeExists(Path root, String mirror) throws IOException {
        if (mirror.indexOf('/') >= 0) {
            return false;
        }
        List<Path> sourceRoots = roots(root, "src/main/java", "src/test/java");
        String candidate = mirror;
        while (candidate.indexOf('.') > 0) {
            String relative = candidate.replace('.', '/') + ".java";
            for (Path sourceRoot : sourceRoots) {
                if (Files.isRegularFile(sourceRoot.resolve(relative))) {
                    return true;
                }
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
        }
        return false;
    }

    private static boolean resourceExists(Path root, String mirror) throws IOException {
        for (Path resourceRoot : roots(root, "src/main/resources", "src/test/resources")) {
            if (Files.exists(resourceRoot.resolve(mirror))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> roots(Path root, String... kinds) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                for (String kind : kinds) {
                    Path candidate = module.resolve(kind);
                    if (Files.isDirectory(candidate)) {
                        found.add(candidate);
                    }
                }
            }
        }
        return found;
    }

    private static String render(Map<String, List<String>> unresolved) {
        StringBuilder out = new StringBuilder();
        unresolved.forEach((file, mirrors) -> out.append("  ")
                .append(file)
                .append(" -> ")
                .append(String.join(", ", mirrors))
                .append(System.lineSeparator()));
        return out.toString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
