package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The violations this detector finds were collected into a plain {@link java.util.ArrayList}.
 *
 * <p>Both writers — {@code recordFieldAccess} and {@code detectCheckThenActViolation} — are called
 * straight from the user's concurrently running test body, so that list was being mutated by
 * N threads at once. Note the contrast inside the same class: {@code fieldHistory}'s per-field
 * lists are guarded with {@code synchronized (history)}; this one was not.
 *
 * <p>Concurrent {@code ArrayList.add} loses elements (two threads write the same index), so real
 * atomicity violations silently vanished before analysis ever read them — a false negative in the
 * detector's own bookkeeping. It can also throw {@code ArrayIndexOutOfBoundsException} from inside
 * the user's test body, which the runner then attributes to the user's code: a detector bug
 * masquerading as a user bug.
 */
class AtomicityViolationsAreNotLostTest {

    private static final int THREADS = 8;
    private static final int VIOLATIONS_PER_THREAD = 500;

    @Test
    void everyViolationRecordedConcurrentlySurvivesToTheReport() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        AtomicReference<Throwable> blewUp = new AtomicReference<>();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (int t = 0; t < THREADS; t++) {
            final int thread = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < VIOLATIONS_PER_THREAD; i++) {
                        // A check-then-act that does not hold: checked "expected", saw "actual".
                        // Each field name is distinct so the report's Set cannot legitimately
                        // collapse them — anything missing was genuinely lost in the recording.
                        validator.detectCheckThenActViolation(
                            "balance-" + thread + "-" + i, "expected", "actual", true);
                    }
                } catch (Throwable e) {
                    blewUp.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "the recording threads must finish");

        assertNull(blewUp.get(),
            "recording a violation must never throw into the user's test body: " + blewUp.get());

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertEquals(THREADS * VIOLATIONS_PER_THREAD, report.checkThenActViolations.size(),
            "every violation recorded concurrently must reach the report — none may be lost");
    }
}
