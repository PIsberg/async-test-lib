package se.deversity.asynctest.diagnostics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Detects untracked or uncommitted changes in the Git repository.
 * Helps ensure test consistency and alerts developers to forgotten files.
 *
 * @since 1.4.0
 */
public class UncommittedChangesDetector {

    /**
     * Executes 'git status --porcelain' to find modified or untracked files.
     * 
     * @return a report of detected changes
     */
    public UncommittedChangesReport analyze() {
        UncommittedChangesReport report = new UncommittedChangesReport();
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
                        report.untrackedFiles.add(file);
                    } else {
                        report.uncommittedFiles.add(file + " [" + status.trim() + "]");
                    }
                }
            }

            // Small timeout to avoid hanging if git takes too long (should be instant for porcelain)
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                report.error = "git status timed out after 5 seconds";
            } else if (process.exitValue() != 0) {
                report.error = "git status failed with exit code " + process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.error = "Failed to check git status: " + e.getMessage();
        } catch (IOException e) {
            // Git may not be available or not a repository
            report.error = "Failed to check git status: " + e.getMessage();
        }
        return report;
    }

    /**
     * Report class for uncommitted changes analysis.
     */
    public static class UncommittedChangesReport {
        final List<String> uncommittedFiles = new ArrayList<>();
        final List<String> untrackedFiles = new ArrayList<>();
        String error;

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
