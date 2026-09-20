package se.deversity.asynctest.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report that cannot be written must not fail the build it is reporting on: {@code flush()}
 * returns {@code null} and says why on stderr, naming the format. Both listeners share the
 * write path (#701), and the message is the one thing a user sees, so each format's line is
 * pinned here.
 */
@ResourceLock(Resources.SYSTEM_ERR)
class ReportListenerWriteFailureTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream previousErr;

    @BeforeEach
    void captureStderr() {
        previousErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(previousErr);
    }

    /** A regular file where the output directory should be, so creating the directory fails. */
    private static Path blockedDir(Path tmp) throws IOException {
        return Files.writeString(tmp.resolve("not-a-directory"), "x");
    }

    @Test
    void jsonFlushFailureIsReportedAndSwallowed(@TempDir Path tmp) throws IOException {
        JsonReportListener listener = new JsonReportListener(blockedDir(tmp).toString(), false);
        listener.onStructuredReport("RaceConditionDetector", IssueSeverity.HIGH, "finding");

        assertNull(listener.flush());

        String err = captured.toString(StandardCharsets.UTF_8);
        assertTrue(err.startsWith("async-test: Failed to write JSON report: "), err);
        assertEquals(1, listener.getFindingCount(), "the finding is kept, only the file is lost");
    }

    @Test
    void xmlFlushFailureIsReportedAndSwallowed(@TempDir Path tmp) throws IOException {
        JUnitXmlReportListener listener =
                new JUnitXmlReportListener(blockedDir(tmp).toString(), false);
        listener.onStructuredReport("RaceConditionDetector", IssueSeverity.HIGH, "finding");

        assertNull(listener.flush());

        String err = captured.toString(StandardCharsets.UTF_8);
        assertTrue(err.startsWith("async-test: Failed to write JUnit XML report: "), err);
        assertEquals(1, listener.getFindingCount(), "the finding is kept, only the file is lost");
    }
}
