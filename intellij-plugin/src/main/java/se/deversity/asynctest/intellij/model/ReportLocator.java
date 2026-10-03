package se.deversity.asynctest.intellij.model;

import java.io.File;
import java.nio.file.Path;

/**
 * Finds the JSON report the tool window shows: the first of the configured path patterns that
 * names a file under the project's base path.
 *
 * <p>Kept free of the IntelliJ platform so it is testable without booting an IDE (#723).
 */
public final class ReportLocator {

    private ReportLocator() {
    }

    /**
     * @param basePath the project's base path, or {@code null} for a project without one
     * @param patterns comma-separated paths relative to {@code basePath}, tried in order; blank
     *                 entries are skipped and each is trimmed
     * @return the first that names a regular file, or {@code null} when none does; a directory
     *         matching a pattern is skipped, since parsing it would show an empty report
     */
    public static Path locate(String basePath, String patterns) {
        if (basePath == null || patterns == null) return null;

        for (String pattern : patterns.split(",")) {
            pattern = pattern.trim();
            if (pattern.isEmpty()) continue;
            File candidate = new File(basePath, pattern);
            if (candidate.isFile()) return candidate.toPath();
        }
        return null;
    }
}
