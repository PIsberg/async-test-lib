package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects untracked or uncommitted changes in the Git repository.
 * Helps ensure test consistency and alerts developers to forgotten files.
 *
 * <p><strong>The {@code git status} subprocess runs at most once per JVM.</strong> This detector
 * asks a question about the working tree, not about the test that just ran, and the working tree
 * does not change while a suite executes — so every {@code @AsyncTest} method in a JVM was forking
 * a process to compute the same answer. Measured on Windows, that fork cost ~287&nbsp;ms and
 * accounted for 99% of the whole 127-detector analysis sweep, which totalled 290&nbsp;ms: a class
 * with 20 {@code @AsyncTest} methods paid roughly six seconds for one repeated answer. Surefire's
 * {@code reuseForks=false} gives one JVM per test class, so in this project's own build the cache
 * is effectively per test class.
 *
 * @since 1.4.0
 */
public class UncommittedChangesDetector {

    /**
     * How many times the {@code git status} subprocess has actually been forked in this JVM.
     * Package-private so {@code UncommittedChangesDetectorTest} can pin the at-most-once contract:
     * timing cannot pin it, and the whole point of the cache is the fork that does not happen.
     */
    static final AtomicInteger GIT_INVOCATIONS = new AtomicInteger();

    /**
     * The parsed working-tree state, computed on first use. Volatile rather than synchronized: a
     * benign race can fork {@code git} twice on the very first concurrent call, which costs one
     * extra subprocess and produces the same answer, whereas holding a lock across a process fork
     * would stall every worker thread behind it.
     */
    private static volatile @Nullable GitStatus cached;

    /**
     * Executes 'git status --porcelain' to find modified or untracked files, or replays the result
     * of the first such call in this JVM.
     *
     * @return a report of detected changes
     */
    public UncommittedChangesReport analyze() {
        return new UncommittedChangesReport(cachedStatus());
    }

    /**
     * The working-tree snapshot, computed on first use. Static rather than inlined into
     * {@link #analyze()} because the cache is per JVM, not per detector instance, and writing a
     * static field from an instance method is the shape SpotBugs flags as
     * {@code ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD} — correctly, since it reads as
     * per-instance state when it is not.
     */
    private static GitStatus cachedStatus() {
        GitStatus status = cached;
        if (status == null) {
            status = readGitStatus();
            cached = status;
        }
        return status;
    }

    /** Forks {@code git status} and parses its output. */
    private static GitStatus readGitStatus() {
        GIT_INVOCATIONS.incrementAndGet();
        List<String> uncommitted = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        String error = null;
        try {
            // --porcelain=v1 is stable for parsing
            Process process = new ProcessBuilder("git", "status", "--porcelain=v1")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    // Format is: XY PATH
                    // X = status in index, Y = status in work tree
                    if (line.length() < 4) continue;

                    String status = line.substring(0, 2);
                    String file = line.substring(3);

                    if ("??".equals(status)) {
                        untracked.add(file);
                    } else {
                        uncommitted.add(file + " [" + status.trim() + "]");
                    }
                }
            }

            // Small timeout to avoid hanging if git takes too long (should be instant for porcelain)
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                error = "git status timed out after 5 seconds";
            } else if (process.exitValue() != 0) {
                error = "git status failed with exit code " + process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Failed to check git status: " + e.getMessage();
        } catch (IOException e) {
            // Git may not be available or not a repository
            error = "Failed to check git status: " + e.getMessage();
        }
        return new GitStatus(List.copyOf(uncommitted), List.copyOf(untracked), error);
    }

    /**
     * Drops the cached working-tree state so the next {@link #analyze()} forks {@code git} again.
     * For tests that need to observe a tree they just changed; production code has no reason to
     * call it, because the tree does not change under a running suite.
     */
    static void invalidateCache() {
        cached = null;
    }

    /** Immutable snapshot of one {@code git status --porcelain=v1} run. */
    private record GitStatus(List<String> uncommitted, List<String> untracked,
                             @Nullable String error) { }

    /**
     * Report class for uncommitted changes analysis.
     */
    public static class UncommittedChangesReport {
        final List<String> uncommittedFiles = new ArrayList<>();
        final List<String> untrackedFiles = new ArrayList<>();
        @Nullable String error;

        /** Creates an empty report; callers populate the lists directly. */
        public UncommittedChangesReport() {
            // Intentionally empty — see the Javadoc above.
        }

        private UncommittedChangesReport(GitStatus status) {
            uncommittedFiles.addAll(status.uncommitted());
            untrackedFiles.addAll(status.untracked());
            error = status.error();
        }

        /**
         * Check if any untracked or uncommitted changes were detected.
         */
        public boolean hasIssues() {
            return !uncommittedFiles.isEmpty() || !untrackedFiles.isEmpty() || error != null;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.LOW.format()).append(": Uncommitted repository changes detected\n");
            sb.append("Impact: ").append(IssueSeverity.LOW.getDescription()).append("\n");

            if (error != null) {
                sb.append("  ⚠️ Error: ").append(error).append("\n");
            }

            if (!uncommittedFiles.isEmpty()) {
                sb.append("  Uncommitted files (modified/added/deleted):\n");
                for (String f : uncommittedFiles) {
                    sb.append("    - ").append(f).append("\n");
                }
            }

            if (!untrackedFiles.isEmpty()) {
                sb.append("  Untracked files (not in git):\n");
                for (String f : untrackedFiles) {
                    sb.append("    - ").append(f).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No uncommitted changes detected.\n");
            }

            sb.append("  Recommended fix: git add and git commit your changes to ensure a clean test baseline.");
            return sb.toString();
        }
    }
}
