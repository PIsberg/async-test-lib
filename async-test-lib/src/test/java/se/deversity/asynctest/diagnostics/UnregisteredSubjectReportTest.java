package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Phaser;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every synchronizer detector exposes two kinds of public method: a {@code register*} that names a
 * subject, and {@code record*} methods that flag something that happened to it. Nothing requires
 * the first to be called before the second — no Javadoc precondition, no runtime check — and the
 * two are usually written at different places in a test, the registration in setup and the
 * recording wherever the timeout is actually caught.
 *
 * <p>Skip the registration and the report has a finding to tell, but its {@code toString()} looked
 * the subject up in a registry that never received it and dereferenced the {@code null}. The NPE
 * did not reach the user: {@code DetectorRegistry.ifIssue} catches {@code RuntimeException} around
 * {@code report.toString()} precisely so one bad detector cannot discard the rest of the sweep. So
 * the finding was swallowed — the detector reported nothing at all, and the concurrency bug the
 * user instrumented for went unreported. A silent no-report is the worst possible failure for a
 * detector, because it is indistinguishable from a clean run.
 *
 * <p>{@code CyclicBarrierDetector} already had an ad-hoc {@code "unknown"} fallback at one of its
 * three lookup sites, so the hazard was known; the other four detectors simply never got it.
 *
 * <p>These tests pin the contract that matters to a caller: a {@code record*} call on an
 * unregistered subject still produces a rendered report naming that subject. Found by NullAway
 * ("dereferenced expression 'info' is @Nullable"), which is why the gate exists.
 */
@DisplayName("Detector reports survive record-without-register")
class UnregisteredSubjectReportTest {

    @Test
    @DisplayName("CountDownLatch: recordTimeout without registerLatch still renders")
    void countDownLatchTimeoutWithoutRegistration() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(1);

        detector.recordTimeout(latch);

        CountDownLatchDetector.CountDownLatchReport report = detector.analyze();
        assertTrue(report.hasIssues(), "a recorded timeout is an issue regardless of registration");
        String rendered = assertDoesNotThrow(report::toString,
            "toString() must not NPE on an unregistered latch — ifIssue would swallow it "
                + "and the finding would vanish");
        assertTrue(rendered.contains("Timed Out Latches"), () -> "report lost its finding: " + rendered);
    }

    @Test
    @DisplayName("CyclicBarrier: recordTimeout/recordBroken without registerBarrier still renders")
    void cyclicBarrierTimeoutWithoutRegistration() {
        CyclicBarrierDetector detector = new CyclicBarrierDetector();
        CyclicBarrier barrier = new CyclicBarrier(2);

        detector.recordTimeout(barrier);
        detector.recordBroken(barrier);

        CyclicBarrierDetector.CyclicBarrierReport report = detector.analyze();
        assertTrue(report.hasIssues());
        String rendered = assertDoesNotThrow(report::toString);
        assertTrue(rendered.contains("CYCLICBARRIER ISSUES DETECTED"),
            () -> "report lost its finding: " + rendered);
    }

    @Test
    @DisplayName("Exchanger: recordTimeout/recordInterrupted without registerExchanger still renders")
    void exchangerTimeoutWithoutRegistration() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.recordTimeout(exchanger);
        detector.recordInterrupted(exchanger);

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertTrue(report.hasIssues());
        String rendered = assertDoesNotThrow(report::toString);
        assertTrue(rendered.contains("EXCHANGER ISSUES DETECTED"),
            () -> "report lost its finding: " + rendered);
    }

    @Test
    @DisplayName("Phaser: recordTimeout/recordTermination without registerPhaser still renders")
    void phaserTimeoutWithoutRegistration() {
        PhaserDetector detector = new PhaserDetector();
        Phaser phaser = new Phaser(1);

        detector.recordTimeout(phaser);
        detector.recordTermination(phaser);

        PhaserDetector.PhaserReport report = detector.analyze();
        assertTrue(report.hasIssues());
        String rendered = assertDoesNotThrow(report::toString);
        assertTrue(rendered.contains("PHASER ISSUES DETECTED"),
            () -> "report lost its finding: " + rendered);
    }

    @Test
    @DisplayName("ReentrantLock: recordLockTimeout without registerLock still renders")
    void reentrantLockTimeoutWithoutRegistration() {
        ReentrantLockDetector detector = new ReentrantLockDetector();
        ReentrantLock lock = new ReentrantLock();

        detector.recordLockTimeout(lock);

        ReentrantLockDetector.ReentrantLockReport report = detector.analyze();
        assertTrue(report.hasIssues());
        String rendered = assertDoesNotThrow(report::toString);
        assertTrue(rendered.contains("Lock Timeouts"),
            () -> "report lost its finding: " + rendered);
    }

    /**
     * The registered path must keep naming the subject — the fix for the unregistered case is a
     * fallback label, and a fallback that also replaces real names would be a silent regression in
     * every report the user actually reads.
     */
    @Test
    @DisplayName("Registered subjects are still named by their registered name")
    void registeredSubjectsKeepTheirName() {
        CountDownLatchDetector detector = new CountDownLatchDetector();
        CountDownLatch latch = new CountDownLatch(1);

        detector.registerLatch(latch, "checkout-barrier", 1);
        detector.recordTimeout(latch);

        String rendered = detector.analyze().toString();
        assertTrue(rendered.contains("checkout-barrier"),
            () -> "registered name must survive: " + rendered);
    }
}
