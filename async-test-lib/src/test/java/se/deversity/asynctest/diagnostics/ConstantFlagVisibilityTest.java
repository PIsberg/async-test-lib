package se.deversity.asynctest.diagnostics;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain flag whose every write stores one constant, read by a thread that never writes it with
 * nothing ordering the two, is a visibility finding (#1053).
 *
 * <p>{@code AtomicityValidator} excuses a field whose writes all store the same constant, because
 * such writes cannot lose an update. That holds for atomicity and is why the excuse stays. It says
 * nothing about visibility: the classic missing-{@code volatile} stop flag, {@code running = false}
 * read in another thread's loop, is a constant write, and the reader may never see it. The events
 * are the ones the bridge forwards for the agent's field stream, stamped on real threads as
 * {@code OrderedPublicationTest} does.
 */
class ConstantFlagVisibilityTest {

    private static final int NO_CONSTANT = Integer.MIN_VALUE;
    private static final int FALSE = 0;

    static final class Worker {
        boolean running = true;
    }

    private record Stamped(long thread, HappensBefore.Stamp stamp) {
    }

    /** Runs {@code before}, stamps the access, then runs {@code after}, on a new thread. */
    private static Stamped onNewThread(Runnable before, Runnable after) throws InterruptedException {
        AtomicReference<Stamped> seen = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            before.run();
            seen.set(new Stamped(Thread.currentThread().threadId(), HappensBefore.current()));
            after.run();
        });
        thread.start();
        thread.join();
        return seen.get();
    }

    private static void access(AtomicityValidator validator, String field, boolean write,
                               Stamped at, boolean volatileField, int constant, Object owner) {
        validator.recordFieldAccessUnderLocks(field, null, write, at.thread(), 0L, 0, 0,
                volatileField, constant, System.identityHashCode(owner), 0, owner, at.stamp(), 0L);
    }

    /** One thread stores false into the flag, another only reads it. */
    private static AtomicityValidator.AtomicityReport stopFlag(boolean volatileField, boolean ordered)
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Worker worker = new Worker();
        // The write is stamped before its release and the read after its acquire, as a volatile
        // store and load or a lock hand-off would order them.
        Stamped stop = onNewThread(() -> { }, () -> {
            if (ordered) {
                HappensBefore.release(worker);
            }
        });
        Stamped poll = onNewThread(() -> {
            if (ordered) {
                HappensBefore.acquire(worker);
            }
        }, () -> { });
        access(validator, "Worker.running", true, stop, volatileField, FALSE, worker);
        access(validator, "Worker.running", false, poll, volatileField, NO_CONSTANT, worker);
        return validator.analyzeAtomicity();
    }

    private static boolean reportsTheFlag(AtomicityValidator.AtomicityReport report) {
        return report.unorderedPublications.stream().anyMatch(line -> line.startsWith("Worker.running"));
    }

    @Test
    @DisplayName("a plain stop flag written by one thread and read by another, unordered, is reported")
    void aPlainStopFlagIsAVisibilityFinding() throws InterruptedException {
        AtomicityValidator.AtomicityReport report = stopFlag(false, false);
        assertTrue(reportsTheFlag(report), "the reader may never see running = false: " + report);
        assertTrue(report.structuredViolations.stream()
                        .anyMatch(v -> "unorderedPublication".equals(v.attributes().get("kind"))),
                "the finding reaches the structured report with its own kind: " + report.structuredViolations);
    }

    @Test
    @DisplayName("the constant-write excuse still holds for atomicity")
    void theFlagIsStillNotAnAtomicityFinding() throws InterruptedException {
        assertFalse(stopFlag(false, false).unsafeFieldAccesses.stream()
                        .anyMatch(line -> line.startsWith("Worker.running")),
                "constant writes cannot lose an update; only the visibility finding is new");
    }

    @Test
    @DisplayName("the same flag declared volatile is not reported")
    void aVolatileStopFlagIsNot() throws InterruptedException {
        assertFalse(reportsTheFlag(stopFlag(true, false)), "a volatile write is visible to every later read");
    }

    @Test
    @DisplayName("the same plain flag with a happens-before edge from write to read is not reported")
    void anOrderedStopFlagIsNot() throws InterruptedException {
        assertFalse(reportsTheFlag(stopFlag(false, true)), "the edge orders the read after the write");
    }

    @Test
    @DisplayName("a thread reading its own constant write is not a second party")
    void aThreadReadingItsOwnWriteIsNot() throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Worker worker = new Worker();
        Stamped self = onNewThread(() -> { }, () -> { });
        access(validator, "Worker.running", true, self, false, FALSE, worker);
        access(validator, "Worker.running", false, self, false, NO_CONSTANT, worker);
        assertFalse(reportsTheFlag(validator.analyzeAtomicity()), "one thread races nobody");
    }
}
