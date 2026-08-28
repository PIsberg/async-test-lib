package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A workflow may not push to a protected branch, and a {@code paths:} filter must match something.
 *
 * <p><strong>Both failures had already happened, in one file, undetected for weeks.</strong>
 * {@code demo.yml} re-recorded the README demo and pushed the result straight to {@code main}:
 *
 * <pre>
 * remote: error: GH006: Protected branch update failed for refs/heads/main.
 * remote: - Changes must be made through a pull request.
 *  ! [remote rejected] HEAD -&gt; main (protected branch hook declined)
 * </pre>
 *
 * <p>Branch protection requires a pull request, so that push could not succeed on any run, and it
 * did not: every run failed from the day protection went on. The retry loop could not have
 * rescued it either, because the step stamping a version into {@code tools/demo/pom.xml} left the
 * tree dirty and {@code git rebase} refuses to start there.
 *
 * <p>The second fault is why nobody noticed the first. The same workflow filtered on
 * {@code paths: src/**}, and there has been no top-level {@code src/} since this repository became
 * a reactor, so the trigger only fired for changes under {@code tools/}. A dead glob is invisible:
 * it does not error, it silently narrows what a workflow runs on, and a workflow that rarely runs
 * is one whose failures rarely get read.
 *
 * <p>Neither check can be replaced by watching the pipeline. A push to a protected branch fails in
 * a workflow that runs after the merge, so no pull request ever goes red for it; and a dead path
 * filter produces no run at all, which looks exactly like "nothing to do".
 */
class WorkflowPushTargetAndPathsTest {

    /** Branches this repository protects; a workflow reaching one directly cannot succeed. */
    private static final Set<String> PROTECTED_BRANCHES = Set.of("main", "master");

    /** A {@code paths:} or {@code paths-ignore:} key, capturing its indentation. */
    private static final Pattern PATHS_KEY = Pattern.compile("^( *)paths(-ignore)?: *$");

    /** One entry in a YAML block sequence. */
    private static final Pattern LIST_ITEM = Pattern.compile("^ *- *(.+?) *$");

    /** The quote characters a YAML scalar may be wrapped in: apostrophe and quotation mark. */
    private static final String QUOTES = "'" + (char) 34;

    /** Directories holding build output or VCS metadata rather than sources. */
    private static final Set<String> NOT_SOURCE =
            Set.of("target", "build", ".git", ".idea", "node_modules", ".m2", "out");

    @Test
    @DisplayName("no workflow pushes directly to a protected branch")
    void noWorkflowPushesToAProtectedBranch() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> findings = new ArrayList<>();
        for (Path file : workflows) {
            List<String> lines = readLines(file);
            String relative = relativeName(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int at = line.indexOf("git push");
                if (at < 0 || line.strip().startsWith("#")) {
                    continue;
                }
                for (String token : line.substring(at).strip().split("[ ]+")) {
                    if (PROTECTED_BRANCHES.contains(branchOf(token))) {
                        findings.add(relative + ":" + (i + 1) + "  " + line.strip());
                        break;
                    }
                }
            }
        }

        assertTrue(findings.isEmpty(),
                "These push to a protected branch, which branch protection rejects with GH006, "
                        + "\"Changes must be made through a pull request\". Such a push cannot "
                        + "succeed on any run, and because it happens after the merge no pull "
                        + "request ever goes red for it: the workflow simply fails forever, on "
                        + "main, where nobody is looking.\n  " + String.join("\n  ", findings)
                        + "\nOpen a pull request instead - push the commit to its own branch and "
                        + "call gh pr create, as .github/workflows/demo.yml now does.");
    }

    /** Shell punctuation that can follow a refspec inside a run block. */
    private static final String SHELL_TAIL = ";&|)" + (char) 34;

    /**
     * {@return the branch a push refspec targets, unwrapping quotes, refs/heads/ and shell tails}
     *
     * <p>The trailing punctuation matters. The workflow that prompted this gate wrote the push
     * twice, once as {@code if git push origin HEAD:main; then} and once bare, and a scan that
     * did not strip the semicolon saw {@code main;} and missed the guarded one.
     */
    private static String branchOf(String token) {
        String target = stripQuotes(token);
        while (!target.isEmpty() && SHELL_TAIL.indexOf(target.charAt(target.length() - 1)) >= 0) {
            target = target.substring(0, target.length() - 1);
        }
        int colon = target.lastIndexOf(':');
        String branch = colon >= 0 ? target.substring(colon + 1) : target;
        return branch.startsWith("refs/heads/")
                ? branch.substring("refs/heads/".length()) : branch;
    }

    @Test
    @DisplayName("every paths: filter matches at least one file that exists")
    void everyPathsFilterMatchesSomething() {
        List<Path> workflows = yamlFiles(repoRoot().resolve(".github/workflows"));
        assertFalse(workflows.isEmpty(),
                "No workflow files found; the scan has nothing to check, which is not a pass.");

        List<String> present = sourceFiles(repoRoot());
        assertFalse(present.isEmpty(),
                "Walked the repository and found no files, so every glob would look dead. That "
                        + "is a broken scan, not a broken workflow.");

        List<String> findings = new ArrayList<>();
        int globs = 0;
        for (Path file : workflows) {
            List<String> lines = readLines(file);
            String relative = relativeName(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher key = PATHS_KEY.matcher(lines.get(i));
                if (!key.matches()) {
                    continue;
                }
                int indent = key.group(1).length();
                for (int j = i + 1; j < lines.size(); j++) {
                    String line = lines.get(j);
                    if (line.isBlank()) {
                        continue;
                    }
                    if (indentOf(line) <= indent) {
                        break;
                    }
                    if (line.strip().startsWith("#")) {
                        continue;
                    }
                    Matcher item = LIST_ITEM.matcher(line);
                    if (!item.matches()) {
                        break;
                    }
                    globs++;
                    Pattern compiled = globToRegex(stripQuotes(item.group(1)));
                    if (present.stream().noneMatch(path -> compiled.matcher(path).matches())) {
                        findings.add(relative + ":" + (j + 1) + "  " + stripQuotes(item.group(1)));
                    }
                }
            }
        }

        assertTrue(findings.isEmpty(),
                "These paths: globs match no file in the repository, so they contribute nothing "
                        + "to the filter holding them. A dead glob does not error - it silently "
                        + "narrows what the workflow triggers on, and a workflow that stops "
                        + "running stops reporting:\n  " + String.join("\n  ", findings)
                        + "\nPoint each at a path that exists, or delete it.");

        assertTrue(globs > 0,
                "Found no paths: globs at all, so the scan stopped matching and is checking "
                        + "nothing. That is not the same as workflows having no filters.");
    }

    /** {@return {@code glob} as a regex, with GitHub's rule that a doubled star crosses slashes} */
    private static Pattern globToRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '*') {
                out.append(Pattern.quote(String.valueOf(c)));
            } else if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                out.append(".*");
                i++;
            } else {
                out.append("[^/]*");
            }
        }
        return Pattern.compile(out.append('$').toString());
    }

    private static String stripQuotes(String value) {
        String out = value;
        while (!out.isEmpty() && QUOTES.indexOf(out.charAt(0)) >= 0) {
            out = out.substring(1);
        }
        while (!out.isEmpty() && QUOTES.indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** {@return every repository-relative path, with build output and VCS metadata pruned} */
    private static List<String> sourceFiles(Path root) {
        List<String> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && NOT_SOURCE.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    found.add(slashed(root.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not walk " + root, e);
        }
        return found;
    }

    /** {@return {@code path} with the platform separator normalised to a forward slash} */
    private static String slashed(Path path) {
        StringBuilder out = new StringBuilder();
        for (Path part : path) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(part);
        }
        return out.toString();
    }

    private static String relativeName(Path file) {
        return slashed(repoRoot().relativize(file));
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static List<Path> yamlFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + root, e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
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
