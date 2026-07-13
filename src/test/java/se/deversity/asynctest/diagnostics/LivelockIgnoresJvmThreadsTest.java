package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code captureSnapshot()} recorded {@code threadMXBean.dumpAllThreads(...)} — <em>every</em>
 * thread in the JVM, not just the test's workers.
 *
 * <p>The JVM's own daemons ({@code Finalizer}, {@code Reference Handler}, {@code Common-Cleaner},
 * the JUnit infrastructure threads) sit permanently in {@code WAITING} and burn no CPU. That is
 * precisely the signature {@code isStarved()} looks for — the last five snapshots all
 * BLOCKED/WAITING, with unchanged CPU time — and {@code madeProgress()} rejects them for the same
 * reason.
 *
 * <p>So they landed in {@code starvedThreads} / {@code noProgressThreads} on essentially every
 * run, and livelock detection is on by default under {@code detectAll}. The detector reported
 * starvation against a perfectly healthy JVM, on tests that had nothing wrong with them.
 *
 * <p>Only the threads running the test can be starved by the code under test. The runner calls
 * {@code captureSnapshot()} from each worker's own {@code finally} block, so the caller is always
 * a worker — and that is how the detector knows which threads are its own.
 */
class LivelockIgnoresJvmThreadsTest {

    @Test
    void idleJvmDaemonThreadsAreNotReportedAsStarved() {
        LivelockDetector detector = new LivelockDetector();

        // Six snapshots, as a worker would take over six invocations. Nothing in this JVM is
        // starved: the idle daemons are simply parked, which is what idle daemons do.
        for (int i = 0; i < 6; i++) {
            detector.captureSnapshot();
        }

        LivelockDetector.LivelockReport report = detector.analyzeLivelocks();

        assertFalse(report.hasIssues(),
            "the JVM's own parked daemon threads are not a livelock in the code under test:\n"
                + report);
    }
}
