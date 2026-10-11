package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.ProcessStreamStarvationDetector;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;

/**
 * Phase 25, child processes — {@code PROCESS_STREAM_STARVATION}.
 *
 * <p>The fixture proves the detector is reachable from the published artifact, runs the hazard
 * through its public recording API against a real child process, and asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}. The child is the
 * running JVM's own {@code java -version}: present wherever the fixture builds, and quiet enough
 * that the wait returns; a verbose child would not.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} entry 147 for the buggy-vs-fixed pair.
 */
class Phase25ProcessStreamFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings, "ProcessStreamStarvationDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 30_000, licenseMockMode = true,
               includes = {DetectorType.PROCESS_STREAM_STARVATION})
    void processStreamStarvation() throws IOException, InterruptedException {
        reachable("processStreamStarvationDetector()", AsyncTestContext::processStreamStarvationDetector);

        // The hazard: wait for the child while its merged output is a pipe nobody has taken.
        var detector = AsyncTestContext.processStreamStarvationDetector();
        String java = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(java, "-version").redirectErrorStream(true);
        Process child = builder.start();
        try {
            detector.recordStarted(builder, child);
            detector.recordWaitFor(child, Thread.currentThread());
            child.waitFor(20, TimeUnit.SECONDS);
            detector.recordStreamTaken(child, ProcessStreamStarvationDetector.Stream.STDOUT);
            child.getInputStream().readAllBytes();
        } finally {
            child.destroyForcibly();
        }
    }
}
