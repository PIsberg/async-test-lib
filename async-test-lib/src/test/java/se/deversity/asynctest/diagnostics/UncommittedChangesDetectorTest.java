package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UncommittedChangesDetector.
 */
public class UncommittedChangesDetectorTest {

    @BeforeEach
    void freshCache() {
        UncommittedChangesDetector.invalidateCache();
        UncommittedChangesDetector.GIT_INVOCATIONS.set(0);
    }

    @Test
    void testDetectorAnalysis() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();
        UncommittedChangesDetector.UncommittedChangesReport report = detector.analyze();

        assertNotNull(report);
        // Since we are running in a repo where we are currently making changes,
        // it's possible it has issues. We can't strictly assert false for hasIssues().
        // But we can check that it doesn't throw and toString() works.
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("Uncommitted repository changes detected") || reportStr.contains("No uncommitted changes detected"));
    }

    @Test
    void testReportHasIssues() {
        UncommittedChangesDetector.UncommittedChangesReport report = new UncommittedChangesDetector.UncommittedChangesReport();
        assertFalse(report.hasIssues());

        report.uncommittedFiles.add("ModifiedFile.java [M]");
        assertTrue(report.hasIssues());

        report.uncommittedFiles.clear();
        report.untrackedFiles.add("NewFile.java");
        assertTrue(report.hasIssues());

        report.untrackedFiles.clear();
        report.error = "Git error";
        assertTrue(report.hasIssues());
    }

    @Test
    void testReportToString() {
        UncommittedChangesDetector.UncommittedChangesReport report = new UncommittedChangesDetector.UncommittedChangesReport();
        report.uncommittedFiles.add("ModifiedFile.java [M]");
        report.untrackedFiles.add("NewFile.java");

        String reportStr = report.toString();
        assertTrue(reportStr.contains("Uncommitted files"));
        assertTrue(reportStr.contains("ModifiedFile.java [M]"));
        assertTrue(reportStr.contains("Untracked files"));
        assertTrue(reportStr.contains("NewFile.java"));
        assertTrue(reportStr.contains("Recommended fix"));
    }

    // ── The subprocess cache ─────────────────────────────────────────────────────────────────

    /**
     * The cost this detector used to impose was one {@code git status} fork per {@code @AsyncTest}
     * method — 99% of the whole 127-detector analysis sweep. Timing cannot pin the fix, because
     * the thing to assert is the fork that no longer happens.
     */
    @Test
    @DisplayName("git is forked once no matter how many analyses run")
    void gitRunsOncePerJvm() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();

        for (int i = 0; i < 25; i++) {
            assertNotNull(detector.analyze());
        }

        assertEquals(1, UncommittedChangesDetector.GIT_INVOCATIONS.get(),
            "git status must be forked once per JVM, not once per analysis");
    }

    @Test
    @DisplayName("the cache is shared across detector instances, not per instance")
    void cacheIsSharedAcrossInstances() {
        new UncommittedChangesDetector().analyze();
        new UncommittedChangesDetector().analyze();
        new UncommittedChangesDetector().analyze();

        assertEquals(1, UncommittedChangesDetector.GIT_INVOCATIONS.get(),
            "a fresh detector per test method must reuse the working-tree snapshot");
    }

    @Test
    @DisplayName("every analysis sees the same working-tree answer")
    void repeatedAnalysesAgree() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();

        UncommittedChangesDetector.UncommittedChangesReport first = detector.analyze();
        UncommittedChangesDetector.UncommittedChangesReport second = detector.analyze();

        assertNotSame(first, second, "each call gets its own report, so a caller cannot "
            + "mutate the snapshot other tests will read");
        assertEquals(first.uncommittedFiles, second.uncommittedFiles);
        assertEquals(first.untrackedFiles, second.untrackedFiles);
        assertEquals(first.toString(), second.toString());
    }

    /**
     * Mutating a returned report must not leak into the next one — the reports are per-call
     * copies precisely so the shared snapshot stays immutable.
     */
    @Test
    @DisplayName("mutating a report does not corrupt the shared snapshot")
    void reportsAreIndependentCopies() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();

        UncommittedChangesDetector.UncommittedChangesReport first = detector.analyze();
        int before = first.untrackedFiles.size();
        first.untrackedFiles.add("injected-by-a-caller.java");

        assertEquals(before, detector.analyze().untrackedFiles.size(),
            "the next report must not see the previous caller's edit");
    }

    @Test
    @DisplayName("concurrent first use forks git at most once per racing thread, never per call")
    void concurrentFirstUseDoesNotForkPerCall() throws Exception {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Object>> work = java.util.Collections.nCopies(threads * 4,
                () -> { assertNotNull(detector.analyze()); return null; });
            for (Future<Object> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        int forks = UncommittedChangesDetector.GIT_INVOCATIONS.get();
        assertTrue(forks >= 1 && forks <= threads,
            () -> "the first-call race may fork at most once per racing thread, but got " + forks
                + " forks for " + (threads * 4) + " analyses");
    }

    @Test
    @DisplayName("invalidateCache forces the next analysis to re-read the tree")
    void invalidateCacheForcesReread() {
        UncommittedChangesDetector detector = new UncommittedChangesDetector();

        detector.analyze();
        assertEquals(1, UncommittedChangesDetector.GIT_INVOCATIONS.get());

        UncommittedChangesDetector.invalidateCache();
        detector.analyze();

        assertEquals(2, UncommittedChangesDetector.GIT_INVOCATIONS.get(),
            "a test that changed the tree must be able to see it again");
    }
}
