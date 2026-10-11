package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ProcessStreamStarvationDetector;
import se.deversity.asynctest.diagnostics.ProcessStreamStarvationDetector.Stream;
import se.deversity.asynctest.example.service.ToolRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for ToolRunner.
 *
 * ========================================================================
 * DETECTOR: ProcessStreamStarvationDetector
 *           (DetectorType.PROCESS_STREAM_STARVATION)
 * ========================================================================
 *
 * A child process writes its stdout and stderr into pipes of a few
 * kilobytes. Once a pipe is full the child blocks in its write until the
 * parent reads. A parent that calls waitFor() before reading waits for a
 * child that is waiting for it, and neither ever moves.
 *
 * It hangs only when the child writes enough, so the same code passes on
 * a quiet command and hangs on a verbose one: ToolRunner.runWaitingFirst
 * passes every test that runs `java -version`.
 *
 * THE BUG:
 *   - waitFor() while stdout or stderr is a pipe nobody is reading
 *
 * THE FIX:
 *   - drain the output on another thread while waiting
 *   - or redirect it: redirectErrorStream(true), Redirect.DISCARD, a file
 *
 * With the agent attached (collections=true) the woven waitFor and stream
 * getters feed the detector with no recording call; these tests record
 * by hand so that they run without it.
 */
class ToolRunnerTest {

    private ProcessStreamStarvationDetector detector;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setUp() {
        detector = new ProcessStreamStarvationDetector();
    }

    @AfterEach
    void destroy() {
        started.forEach(Process::destroyForcibly);
    }

    private static List<String> javaVersion() {
        return List.of(ProcessHandle.current().info().command().orElse("java"), "-version");
    }

    private Process start(ProcessBuilder builder) throws IOException {
        Process process = builder.start();
        started.add(process);
        return process;
    }

    // -----------------------------------------------------------------------
    // Part 1: the shipped version passes. `java -version` prints a few
    // hundred bytes, far below any pipe buffer, so waiting first returns.
    // -----------------------------------------------------------------------

    @Test
    void waitingFirstPassesOnAQuietCommand() throws Exception {
        String out = new ToolRunner().runWaitingFirst(javaVersion());
        assertTrue(out.contains("version"), out);
    }

    // -----------------------------------------------------------------------
    // Part 2: the same sequence, recorded. The wait happens while the merged
    // output is a pipe nobody has taken, and that is reported.
    // -----------------------------------------------------------------------

    @Test
    void waitingBeforeReading_isDetected() throws Exception {
        Process tool = start(new ProcessBuilder(javaVersion()).redirectErrorStream(true));

        detector.recordWaitFor(tool, Thread.currentThread());
        assertTrue(tool.waitFor(30, TimeUnit.SECONDS));
        detector.recordStreamTaken(tool, Stream.STDOUT);
        tool.getInputStream().readAllBytes();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "read only after the wait returned:\n" + report);
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    // -----------------------------------------------------------------------
    // Part 3: the fix. A drain thread takes the pipe; whether it gets there
    // before or after the wait starts does not matter, it is not the waiter.
    // -----------------------------------------------------------------------

    @Test
    void drainingOnAnotherThread_isClean() throws Exception {
        Process tool = start(new ProcessBuilder(javaVersion()).redirectErrorStream(true));

        Thread drain = new Thread(() -> {
            detector.recordStreamTaken(tool, Stream.STDOUT);
            try {
                tool.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        drain.start();
        detector.recordWaitFor(tool, Thread.currentThread());
        assertTrue(tool.waitFor(30, TimeUnit.SECONDS));
        drain.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "the drain runs while the parent waits:\n" + report);
        assertTrue(new ToolRunner().run(javaVersion()).contains("version"));
    }

    // -----------------------------------------------------------------------
    // Part 4: an output that is not a pipe cannot fill. Discarded output is
    // the JDK's null stream, and waiting on it is fine.
    // -----------------------------------------------------------------------

    @Test
    void discardedOutput_isClean() throws Exception {
        Process tool = start(new ProcessBuilder(javaVersion())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD));

        detector.recordWaitFor(tool, Thread.currentThread());
        assertTrue(tool.waitFor(30, TimeUnit.SECONDS));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "nothing to fill:\n" + report);
    }
}
