package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ProcessStreamStarvationDetector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A child process waited for before anyone drains its output, reported through the woven
 * {@code Process} hooks alone.
 *
 * <p>The children are the running JVM's own {@code java -version}: on every machine the suite
 * runs on, quick, and writing to stderr, so the waits here return; the defect is that a verbose
 * child would not.
 */
class AgentProcessHooksTest {

    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void destroy() {
        started.forEach(Process::destroyForcibly);
    }

    /** Starts the child through the woven {@code ProcessBuilder.start}, as application code does. */
    private Process start(ProcessBuilder builder) throws IOException {
        Process process = AgentConcurrencyUtilHooks.processBuilderStart(builder);
        started.add(process);
        return process;
    }

    private static ProcessBuilder javaVersion() {
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, "-version");
    }

    private static void installed() {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    @Test
    @DisplayName("waitFor before either pipe is taken is reported")
    void waitForWithNothingTakenIsReported() throws Exception {
        installed();
        try {
            Process process = start(javaVersion());
            AgentConcurrencyUtilHooks.processWaitFor(process);

            ProcessStreamStarvationDetector.Report report =
                    AsyncTestContext.processStreamStarvationDetector().analyze();
            assertTrue(report.hasIssues(),
                    "the woven waitFor is the only feed, and stdout and stderr are pipes nobody "
                            + "took: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the timed waitFor is judged the same way")
    void timedWaitForIsReported() throws Exception {
        installed();
        try {
            Process process = start(javaVersion());
            assertTrue(AgentConcurrencyUtilHooks.processWaitFor(process, 30, TimeUnit.SECONDS));

            assertTrue(AsyncTestContext.processStreamStarvationDetector().analyze().hasIssues());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    // The JDK 25 waitFor(Duration) is judged the same way. No @DisplayName: this test skips on
    // JDK 21, and .github/skipped-tests.txt matches the name Gradle's report carries, which would
    // otherwise be the display name rather than the method name Maven's carries.
    @Test
    void durationWaitForIsReported() throws Exception {
        assumeTrue(Runtime.version().feature() >= 25, "Process.waitFor(Duration) is JDK 25+");
        installed();
        try {
            Process process = start(javaVersion());
            assertTrue(AgentConcurrencyUtilHooks.processWaitFor(process, Duration.ofSeconds(30)));

            assertTrue(AsyncTestContext.processStreamStarvationDetector().analyze().hasIssues());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a merged output read through the woven getter before the wait stays silent")
    void mergedOutputReadFirstIsSilent() throws Exception {
        installed();
        try {
            Process process = start(javaVersion().redirectErrorStream(true));
            try (InputStream out = AgentConcurrencyUtilHooks.processInputStream(process)) {
                out.readAllBytes();
            }
            AgentConcurrencyUtilHooks.processWaitFor(process);

            assertFalse(AsyncTestContext.processStreamStarvationDetector().analyze().hasIssues(),
                    AsyncTestContext.processStreamStarvationDetector().analyze().toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a merged child started through the woven startPipeline, read first, stays silent")
    void mergedPipelineReadFirstIsSilent() throws Exception {
        installed();
        try {
            List<Process> pipeline = AgentConcurrencyUtilHooks.processBuilderStartPipeline(
                    List.of(javaVersion().redirectErrorStream(true)));
            Process process = pipeline.get(0);
            started.add(process);
            try (InputStream out = AgentConcurrencyUtilHooks.processInputStream(process)) {
                out.readAllBytes();
            }
            AgentConcurrencyUtilHooks.processWaitFor(process);

            assertFalse(AsyncTestContext.processStreamStarvationDetector().analyze().hasIssues(),
                    AsyncTestContext.processStreamStarvationDetector().analyze().toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("both pipes taken through the woven readers, one on a drain thread, stay silent")
    void bothReadersTakenStaySilent() throws Exception {
        installed();
        try {
            Process process = start(javaVersion());
            AsyncTestContext context = AsyncTestContext.get();
            Thread drainErr = new Thread(() -> {
                AsyncTestContext.install(context);
                try (BufferedReader err =
                             AgentConcurrencyUtilHooks.processErrorReader(process, StandardCharsets.UTF_8)) {
                    err.lines().count();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    AsyncTestContext.uninstall();
                }
            });
            drainErr.start();
            try (BufferedReader out = AgentConcurrencyUtilHooks.processInputReader(process)) {
                out.lines().count();
            }
            AgentConcurrencyUtilHooks.processWaitFor(process);
            drainErr.join();

            assertFalse(AsyncTestContext.processStreamStarvationDetector().analyze().hasIssues(),
                    AsyncTestContext.processStreamStarvationDetector().analyze().toString());
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the hooks return the original results with no run installed")
    void noRunRecordsNothing() throws Exception {
        Process process = start(javaVersion().redirectErrorStream(true));
        try (BufferedReader out = AgentConcurrencyUtilHooks.processInputReader(process, StandardCharsets.UTF_8);
             InputStream err = AgentConcurrencyUtilHooks.processErrorStream(process);
             BufferedReader errReader = AgentConcurrencyUtilHooks.processErrorReader(process)) {
            assertTrue(out.lines().anyMatch(line -> line.contains("version")));
            assertTrue(err.read() < 0, "merged, so nothing is ever written to stderr");
            assertTrue(errReader.readLine() == null);
        }
        assertTrue(AgentConcurrencyUtilHooks.processWaitFor(process) >= 0);
    }
}
