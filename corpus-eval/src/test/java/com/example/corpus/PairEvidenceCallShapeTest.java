package com.example.corpus;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins what {@link PairEvidence} counts as a call into a detector.
 *
 * <p>The call-shape rule holds a pair back when its two halves reach the detector through different
 * methods, so anything the scan wrongly counts as a detector method is a difference the detector
 * never saw. The {@code NOTIFY_WITHOUT_MONITOR} pair was held back for exactly that: both halves
 * make the one detector call, and the firing half also hands the JVM's exception to
 * {@code CorpusRecorder.recordCrash}, which is harness bookkeeping.
 */
class PairEvidenceCallShapeTest {

    @Test
    @DisplayName("a detector call is counted whether the receiver is a variable or an accessor chain")
    void detectorCallsAreCounted() {
        String body = """
                detector.recordAccess(subject, "label", Thread.currentThread());
                AsyncTestContext.sharedSecureRandomDetector()
                        .recordAccess(subject, "label", Thread.currentThread());
                monitor.registerTimer(timer, "timer");
                """;

        assertEquals(Set.of("recordAccess", "registerTimer"), PairEvidence.detectorCallsIn(body));
    }

    @Test
    @DisplayName("harness bookkeeping and detector accessors are not detector calls")
    void harnessCallsAreNotCounted() {
        String body = """
                CorpusRecorder.recordCrash(thrownByTheJvm);
                CorpusRecorder . recordCrash(thrown);
                AsyncTestContext.recordMutableComponentLeakDetector()
                        .recordRecordShared(subject);
                """;

        assertEquals(Set.of("recordRecordShared"), PairEvidence.detectorCallsIn(body));
    }

    @Test
    @DisplayName("a statement keyword is not a helper, so another method's block is not pulled in")
    void keywordsAreNotHelpers() {
        String source = """
                class Lane {
                    void unrelated() {
                        synchronized (OTHER) {
                            detector.recordSomethingElse(OTHER);
                        }
                        if (ready) {
                            detector.recordAnotherThing(OTHER);
                        }
                    }

                    void underTest() {
                        synchronized (MONITOR) {
                            if (armed) {
                                detector.recordNotifyAttempt(MONITOR, "held");
                            }
                        }
                    }
                }
                """;

        assertEquals(Set.of("recordNotifyAttempt"),
                PairEvidence.detectorCallsIn(LaneSource.bodyWithHelpers(source, "underTest")));
    }

    @Test
    @DisplayName("a private helper the body calls is still read")
    void helpersAreRead() {
        String source = """
                class Lane {
                    void underTest() {
                        guarded(() -> { });
                    }

                    private static void guarded(Runnable operation) {
                        detector.recordGuardedAccess(operation);
                    }
                }
                """;

        assertEquals(Set.of("recordGuardedAccess"),
                PairEvidence.detectorCallsIn(LaneSource.bodyWithHelpers(source, "underTest")));
    }
}
