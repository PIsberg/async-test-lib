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

    /** A volatile gauge: bumped with a read-then-write, read by everyone else. */
    static final class Gauge {
        volatile long latest;
    }

    /**
     * One thread reads the gauge, then another bumps it with a read-then-write; with
     * {@code secondWriter} a third bumps it too, from a clock that never saw the second's write.
     */
    private static boolean volatileGaugeReported(boolean secondWriter) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Gauge gauge = new Gauge();
        Stamped reader = onNewThread(() -> { }, () -> { });
        Stamped writer = onNewThread(() -> { }, () -> HappensBefore.release(gauge));
        access(validator, "Gauge.latest", false, reader, true, NO_CONSTANT, gauge);
        access(validator, "Gauge.latest", false, writer, true, NO_CONSTANT, gauge);
        access(validator, "Gauge.latest", true, writer, true, NO_CONSTANT, gauge);
        if (secondWriter) {
            Stamped other = onNewThread(() -> { }, () -> HappensBefore.release(gauge));
            access(validator, "Gauge.latest", false, other, true, NO_CONSTANT, gauge);
            access(validator, "Gauge.latest", true, other, true, NO_CONSTANT, gauge);
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream()
                .anyMatch(line -> line.startsWith("Gauge.latest"));
    }

    @Test
    @DisplayName("a volatile bumped by a single writer and read by other threads is not a finding")
    void aSingleWriterVolatileIsNotAFinding() throws InterruptedException {
        assertFalse(volatileGaugeReported(false),
                "one writer's read-then-write cannot lose an update, and a volatile's reads are "
                        + "synchronization rather than data races: the race detector says so for "
                        + "the same field, and this detector claims the same model");
    }

    @Test
    @DisplayName("the same volatile bumped by two writers is still a finding: volatile count++")
    void twoWritersOnAVolatileStillReport() throws InterruptedException {
        assertTrue(volatileGaugeReported(true),
                "two threads' read-then-write on one volatile lose updates whatever the "
                        + "visibility, which is the finding volatile count++ exists to keep");
    }
}
