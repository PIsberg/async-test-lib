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
        assertTrue(report.violations.get(0).contains("includes="),
                "a pipe nobody was seen taking may be drained by a library the agent does not "
                        + "weave, so the finding has to say how to let the take be seen (#996): "
                        + report.violations.get(0));
    }

    @Test
    @DisplayName("a process whose output was taken, or redirected away, is not reported")
    void drainedOrRedirectedIsSilent() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();

        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process drained = java(merged);
        detector.recordStarted(merged, drained);
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
        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process process = java(merged);
        detector.recordStarted(merged, process);

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
    @DisplayName("one thread writing and reading one piped stream pair is reported (#994)")
    void onePipeUsedFromOneThreadIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        java.io.PipedInputStream in = new java.io.PipedInputStream();
        java.io.PipedOutputStream out = new java.io.PipedOutputStream(in);

        detector.recordPipeWrite(in, Thread.currentThread());
        out.write(42);
        detector.recordPipeRead(in, Thread.currentThread());
        assertEquals(42, in.read());

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(), "the PipedInputStream javadoc warns that using both ends "
                + "from one thread may deadlock it: once the buffer fills, the write waits for a "
                + "read the same thread can never make: " + report);
        assertTrue(report.toString().contains("PipedInputStream"), report.toString());
    }

    @Test
    @DisplayName("a pipe written on one thread and read on another is not reported (#994)")
    void onePipeWrittenAndReadOnTwoThreadsIsSilent() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        java.io.PipedInputStream in = new java.io.PipedInputStream();
        java.io.PipedOutputStream out = new java.io.PipedOutputStream(in);

        Thread writer = new Thread(() -> {
            detector.recordPipeWrite(in, Thread.currentThread());
            try {
                out.write(42);
                out.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        writer.start();
        detector.recordPipeRead(in, Thread.currentThread());
        assertEquals(42, in.read());
        writer.join();

        assertFalse(detector.analyze().hasIssues(), "the javadoc's own pattern, one thread per "
                + "end: " + detector.analyze());
    }

    @Test
    @DisplayName("a pipe the waiter took before its wait but read only after it is reported (#998)")
    void takenBeforeTheWaitButReadAfterItIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process process = java(merged);
        detector.recordStarted(merged, process);

        java.io.InputStream out = detector.observeStream(process,
                ProcessStreamStarvationDetector.Stream.STDOUT, process.getInputStream());
        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(out.readAllBytes().length > 0, "java -version writes its banner");

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(), "the take came first but the read came after the wait, "
                + "which hangs a verbose child exactly like an untaken pipe: " + report);
        assertTrue(report.toString().contains("stdout"), report.toString());
    }

    @Test
    @DisplayName("a pipe the waiter read to its end before waiting is not reported (#998)")
    void readToTheEndBeforeTheWaitIsSilent() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process process = java(merged);
        detector.recordStarted(merged, process);

        java.io.InputStream out = detector.observeStream(process,
                ProcessStreamStarvationDetector.Stream.STDOUT, process.getInputStream());
        assertTrue(out.readAllBytes().length > 0, "java -version writes its banner");
        detector.recordWaitFor(process, Thread.currentThread());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("a pipe the waiter took and another thread reads is not reported (#998)")
    void takenByTheWaiterAndReadByAnotherThreadIsSilent() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process process = java(merged);
        detector.recordStarted(merged, process);

        java.io.InputStream out = detector.observeStream(process,
                ProcessStreamStarvationDetector.Stream.STDOUT, process.getInputStream());
        Thread drain = new Thread(() -> {
            try {
                out.readAllBytes();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        detector.recordWaitFor(process, Thread.currentThread());
        drain.start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        drain.join();

        assertFalse(detector.analyze().hasIssues(), "the stream was handed to a drain thread, the "
                + "fix itself: " + detector.analyze());
    }

    @Test
    @DisplayName("a pipe the waiting thread takes only after its wait is still reported")
    void waiterTakingAfterItsWaitIsReported() throws Exception {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        ProcessBuilder merged = javaVersion().redirectErrorStream(true);
        Process process = java(merged);
        detector.recordStarted(merged, process);

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

    /**
     * A child as Linux hands it out from a builder that merged stderr into stdout: the stderr is
     * still a pipe object, at end of stream from the start, not the JDK's null stream as on
     * Windows. Only the builder says that nothing can ever be written to it.
     */
    private static Process mergedOnLinux() {
        return new Process() {
            private final java.io.InputStream out = new java.io.ByteArrayInputStream(new byte[0]);
            private final java.io.InputStream err = new java.io.ByteArrayInputStream(new byte[0]);
            @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
            @Override public java.io.InputStream getInputStream() { return out; }
            @Override public java.io.InputStream getErrorStream() { return err; }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
    }

    @Test
    @DisplayName("a merged stderr is not a pipe to drain, even where the JDK hands out a pipe object for it")
    void mergedStderrIsSilentWhenTheBuilderSaysSo() {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = mergedOnLinux();

        detector.recordStarted(new ProcessBuilder("tool").redirectErrorStream(true), process);
        detector.recordStreamTaken(process, ProcessStreamStarvationDetector.Stream.STDOUT);
        detector.recordWaitFor(process, Thread.currentThread());

        assertFalse(detector.analyze().hasIssues(),
                "the builder merged stderr into stdout, which was taken: " + detector.analyze());
    }

    @Test
    @DisplayName("a builder that did not merge leaves stderr a pipe, and it is reported")
    void unmergedStderrIsStillReportedWhenTheBuilderIsKnown() {
        ProcessStreamStarvationDetector detector = new ProcessStreamStarvationDetector();
        Process process = mergedOnLinux();

        detector.recordStarted(new ProcessBuilder("tool"), process);
        detector.recordStreamTaken(process, ProcessStreamStarvationDetector.Stream.STDOUT);
        detector.recordWaitFor(process, Thread.currentThread());

        ProcessStreamStarvationDetector.Report report = detector.analyze();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.violations.get(0).contains("stderr"), report.toString());
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
