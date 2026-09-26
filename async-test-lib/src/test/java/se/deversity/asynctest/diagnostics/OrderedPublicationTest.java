package se.deversity.asynctest.diagnostics;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock-free publication the atomicity lockset cannot see, excused by the happens-before model.
 *
 * <p>The events are the ones the bridge forwards for the agent's field stream, with the stamps
 * taken on real threads the way {@code TelemetryRegistry} takes them. Each case has a twin whose
 * reader skips the one acquire the publication gives it, and must keep its finding: without the
 * edge the accesses are exactly the race the lockset reports.
 */
class OrderedPublicationTest {

    private static final int NO_CONSTANT = Integer.MIN_VALUE;

    /** A plain field published by a volatile flag, written by one thread with no lock at all. */
    static final class Pub {
        int data;
        volatile boolean ready;
    }

    /** A fresh object built with setters, then handed over through a concurrent map. */
    static final class Cfg {
        int x;
    }

    private record Stamped(long thread, HappensBefore.Stamp stamp) {
    }

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

    private static boolean volatileFlagReported(boolean readerAcquires) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Pub pub = new Pub();
        // Writer: data, then ready = true, which releases the object after its own event.
        AtomicReference<Stamped[]> writer = new AtomicReference<>();
        Thread writing = new Thread(() -> {
            long me = Thread.currentThread().threadId();
            Stamped data = new Stamped(me, HappensBefore.current());
            Stamped ready = new Stamped(me, HappensBefore.current());
            HappensBefore.release(pub);
            writer.set(new Stamped[] {data, ready});
        });
        writing.start();
        writing.join();
        // Reader: reads ready, then data after the volatile read, acquiring if it saw the write.
        AtomicReference<Stamped[]> reader = new AtomicReference<>();
        Thread reading = new Thread(() -> {
            long me = Thread.currentThread().threadId();
            Stamped ready = new Stamped(me, HappensBefore.current());
            if (readerAcquires) {
                HappensBefore.acquire(pub);
            }
            Stamped data = new Stamped(me, HappensBefore.current());
            reader.set(new Stamped[] {ready, data});
        });
        reading.start();
        reading.join();

        access(validator, "Pub.data", true, writer.get()[0], false, NO_CONSTANT, pub);
        access(validator, "Pub.ready", true, writer.get()[1], true, 1, pub);
        access(validator, "Pub.ready", false, reader.get()[0], true, NO_CONSTANT, pub);
        // The bridge marks this read safely published, which arrives as the volatile bit.
        access(validator, "Pub.data", false, reader.get()[1], true, NO_CONSTANT, pub);
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream()
                .anyMatch(line -> line.startsWith("Pub.data"));
    }

    @Test
    @DisplayName("a lock-free single writer publishing through a volatile flag is not a finding")
    void volatileFlagPublicationIsOrdered() throws InterruptedException {
        assertFalse(volatileFlagReported(true),
                "the reader's access follows its volatile read of the flag the writer set after "
                        + "writing data; the lockset required a lock the idiom never takes");
    }

    @Test
    @DisplayName("the same stream without the acquire is still a finding")
    void volatileFlagWithoutTheAcquireStillReports() throws InterruptedException {
        assertTrue(volatileFlagReported(false),
                "if the model ordered this, it would be ordering by coincidence of timing");
    }

    private static boolean mapPublicationReported(boolean readerAcquires)
            throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Cfg cfg = new Cfg();
        // Builder: setX on a fresh object, then map.put(key, cfg), which releases the value.
        Stamped built = onNewThread(() -> { }, () -> HappensBefore.release(cfg));
        // Reader: map.get(key) acquires the value, then getX.
        Stamped read = onNewThread(() -> {
            if (readerAcquires) {
                HappensBefore.acquire(cfg);
            }
        }, () -> { });
        access(validator, "Cfg.x", false, built, false, NO_CONSTANT, cfg);
        access(validator, "Cfg.x", true, built, false, NO_CONSTANT, cfg);
        access(validator, "Cfg.x", false, read, false, NO_CONSTANT, cfg);
        return validator.analyzeAtomicity().hasIssues();
    }

    @Test
    @DisplayName("an object built and published through a concurrent map in one round is not a finding")
    void sameRoundMapPublicationIsOrdered() throws InterruptedException {
        assertFalse(mapPublicationReported(true),
                "the construction excuse needs later rounds to corroborate it; the put and get "
                        + "are the corroboration, in the round itself");
    }

    @Test
    @DisplayName("the same object read without the get's acquire is still a finding")
    void mapPublicationWithoutTheAcquireStillReports() throws InterruptedException {
        assertTrue(mapPublicationReported(false));
    }
}
