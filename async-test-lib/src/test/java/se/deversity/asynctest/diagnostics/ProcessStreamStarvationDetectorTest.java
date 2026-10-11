package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A child process waited for while its output pipe was never taken.
 *
 * <p>The processes here are the running JVM's own {@code java -version}: present on every machine
 * the suite runs on, quick, and writing to both pipes.
 */
class ProcessStreamStarvationDetectorTest {

    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void destroy() {
        started.forEach(Process::destroyForcibly);
    }

    private Process java(ProcessBuilder builder) throws IOException {
        Process process = builder.start();
        started.add(process);
        return process;
    }

    private static ProcessBuilder javaVersion() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, "-version");
    }

    @Test
    @DisplayName("waitFor on a process whose piped output nobody took is reported")
    void waitForWithUntakenPipesIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = java(javaVersion());

        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(),
                "stdout and stderr are both pipes and neither was taken, so a child writing more "
                        + "than the pipe buffer would block forever: " + report);
        assertTrue(report.toString().contains("stdout") && report.toString().contains("stderr"),
                report.toString());
        assertEquals(1, report.structuredViolations.size());
    }

    @Test
    @DisplayName("a process whose output was taken, or redirected away, is not reported")
    void drainedOrRedirectedIsSilent() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();

        Process drained = java(javaVersion().redirectErrorStream(true));
        detector.recordStreamTaken(drained, ProcessStreamStarvationDetector.Stream.STDOUT);
        drained.getInputStream().readAllBytes();
        detector.recordWaitFor(drained, Thread.currentThread());
        assertTrue(drained.waitFor(30, TimeUnit.SECONDS));

        Process redirected = java(javaVersion()
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD));
        detector.recordWaitFor(redirected, Thread.currentThread());
        assertTrue(redirected.waitFor(30, TimeUnit.SECONDS));

        assertFalse(detector.analyze().hasIssues(),
                "the merged output was read before waiting, and the discarded one has no pipe "
                        + "to fill: " + detector.analyze());
    }

    @Test
    @DisplayName("stderr left as a pipe is reported even when stdout was read")
    void untakenStderrAloneIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = java(javaVersion());
        detector.recordStreamTaken(process, ProcessStreamStarvationDetector.Stream.STDOUT);
        process.getInputStream().readAllBytes();

        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("stderr"), report.toString());
        assertFalse(report.violations.get(0).contains("stdout"), report.toString());
    }

    @Test
    @DisplayName("a pipe a drain task takes after the wait began is not reported")
    void drainTaskTakingAfterTheWaitIsSilent() throws Exception {
        // The usual fix: submit the drain, then wait. The task may not have taken the stream yet
        // when the wait starts, and the code is still correct.
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = java(javaVersion().redirectErrorStream(true));

        detector.recordWaitFor(process, Thread.currentThread());
        Thread drain = new Thread(() -> {
            detector.recordStreamTaken(process, ProcessStreamStarvationDetector.Stream.STDOUT);
            try {
                process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        drain.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        drain.join();

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a pipe the waiting thread takes only after its wait is still reported")
    void waiterTakingAfterItsWaitIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = java(javaVersion().redirectErrorStream(true));

        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        detector.recordStreamTaken(process, ProcessStreamStarvationDetector.Stream.STDOUT);
        process.getInputStream().readAllBytes();

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(), "read after waiting is the bug itself: " + report);
        assertTrue(report.toString().contains("stdout"), report.toString());
    }

    @Test
    @DisplayName("a Process that does not support pid() is reported, not thrown on")
    void processWithoutPidIsReported() {
        Process custom = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();

        detector.recordWaitFor(custom, Thread.currentThread());

        assertTrue(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("an empty detector reports nothing, and a process is reported once")
    void emptyAndOnce() throws Exception {
        assertFalse(new ProcessStreamStarvationDetector().analyze().hasIssues());

        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = java(javaVersion());
        detector.recordWaitFor(process, Thread.currentThread());
        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(1, detector.analyze().violations.size(),
                "one process, one finding, however often it was waited for");
    }
}
