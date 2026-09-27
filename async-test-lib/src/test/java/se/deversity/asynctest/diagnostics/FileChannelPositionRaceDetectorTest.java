package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileChannelPositionRaceDetector}.
 *
 * <p>The detector tracks channels by identity only ({@code Object} channel);
 * plain {@code Object} stand-ins are used here instead of a real
 * {@code FileChannel} — no file I/O is needed to exercise the bookkeeping and
 * violation logic.
 *
 * <p>The unit it judges is the seek-then-I/O sequence (#819): a thread's {@code position}
 * call and the implicit read or write after it that relies on where the cursor was left.
 * A probe on JDK 21 and 26 (8 threads, 5,000 operations each) lost nothing with unguarded
 * self-contained {@code read(buffer)} and {@code write(buffer)} calls, and read the wrong
 * bytes about 1,500 times in 40,000 with unguarded {@code position(n)} then
 * {@code read(buffer)}.
 */
class FileChannelPositionRaceDetectorTest {

    /** A thread's {@code position(n)} and the {@code read(buffer)} that relies on it. */
    private static void seekThenRead(FileChannelPositionRaceDetector d, Object channel) {
        d.recordImplicitPositionAccess(channel, "position");
        d.recordImplicitPositionAccess(channel, "read");
    }

    private static void inAnotherThread(Runnable action) throws InterruptedException {
        Thread t = new Thread(action);
        t.start();
        t.join();
    }

    @Test
    void cleanWhenNoAccess() {
        var d = new FileChannelPositionRaceDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadImplicitAccessIsNotFlagged() {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        for (int i = 0; i < 5; i++) {
            seekThenRead(d, channel);
            d.recordImplicitPositionAccess(channel, "write");
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void seekThenReadRacingAnotherThreadsSeekThenReadIsFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        seekThenRead(d, channel);
        inAnotherThread(() -> seekThenRead(d, channel));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("2 threads"), "Message should count threads: " + msg);
        assertTrue(msg.contains("position"), "Message should mention observed operation: " + msg);
        assertTrue(msg.contains("read"), "Message should mention observed operation: " + msg);

        assertEquals(1, report.structuredViolations.size());
        var v = report.structuredViolations.get(0);
        assertEquals("FileChannelPositionRace", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(2, v.attributes().get("threadCount"));
    }

    @Test
    void anotherThreadsSelfContainedReadCanLandBetweenASeekAndItsRead() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        seekThenRead(d, channel);
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertTrue(d.analyze().hasIssues(),
            "the other thread's read advances the cursor the first thread's seek set, and nothing "
                + "keeps it out from between that seek and the read relying on it");
    }

    /**
     * The limit #755 held the detector at PROMPT for, now closed (#819).
     *
     * <p>A lone {@code read(ByteBuffer)} or {@code write(ByteBuffer)} per thread is not a race
     * on a real {@code FileChannel}: the channel lets one operation involving the position run
     * at a time, so each call reads or writes whole, at an offset nobody chose and nobody relied
     * on. No thread set the position and then depended on it.
     */
    @Test
    void selfContainedImplicitCallsFromManyThreadsAreNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "write");
        d.recordImplicitPositionAccess(channel, "read");
        inAnotherThread(() -> {
            d.recordImplicitPositionAccess(channel, "write");
            d.recordImplicitPositionAccess(channel, "read");
        });
        assertFalse(d.analyze().hasIssues(),
            "self-contained implicit calls lose no bytes, so two threads making them unguarded "
                + "are no finding: " + d.analyze());
    }

    @Test
    void aSeekNoIoReliesOnIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertFalse(d.analyze().hasIssues(),
            "a seek with no read or write after it on the same thread relies on nothing: "
                + d.analyze());
    }

    @Test
    void aSeekOnOneChannelIsNotReliedOnByIoOnAnother() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object seeked = new Object();
        Object other = new Object();
        d.recordImplicitPositionAccess(seeked, "position");
        d.recordImplicitPositionAccess(other, "read");
        inAnotherThread(() -> d.recordImplicitPositionAccess(other, "read"));
        assertFalse(d.analyze().hasIssues(),
            "the read is on a channel whose position this thread never set: " + d.analyze());
    }

    @Test
    void aSeekLeftOpenByAnEarlierRoundIsNotReliedOnByThisRoundsRead() throws Exception {
        var scope = new SelfGuard.Scope();
        SelfGuard.Scope.bind(scope);
        try {
            var d = new FileChannelPositionRaceDetector();
            Object channel = new Object();
            d.recordImplicitPositionAccess(channel, "position");
            scope.markInvocationStart();
            d.recordImplicitPositionAccess(channel, "read");
            inAnotherThread(() -> {
                SelfGuard.Scope.bind(scope);
                try {
                    d.recordImplicitPositionAccess(channel, "read");
                } finally {
                    SelfGuard.Scope.unbind();
                }
            });
            assertFalse(d.analyze().hasIssues(),
                "the body that sought ended with the earlier round, so this round's reads are "
                    + "self-contained: " + d.analyze());

            d.recordImplicitPositionAccess(channel, "position");
            d.recordImplicitPositionAccess(channel, "read");
            assertTrue(d.analyze().hasIssues(),
                "a seek and its read in the round the other thread read in are a sequence");
        } finally {
            SelfGuard.Scope.unbind();
        }
    }

    /**
     * #831: the per-thread seek slot held the channel until the thread's next seek, so a pooled
     * worker kept it reachable across rounds. The round start clears every slot; seen from outside,
     * a read after it relies on no seek made before it, with or without a round clock bound.
     */
    @Test
    void aRoundStartForgetsEveryOpenSeek() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        d.markInvocationStart();
        d.recordImplicitPositionAccess(channel, "read");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertFalse(d.analyze().hasIssues(),
            "the seek belonged to the round before, so the read relies on nothing: " + d.analyze());
    }

    @Test
    void aSeekMadeAfterARoundStartIsForgottenAtTheNextOne() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        d.markInvocationStart();
        d.recordImplicitPositionAccess(channel, "position");
        d.markInvocationStart();
        d.recordImplicitPositionAccess(channel, "read");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertFalse(d.analyze().hasIssues(),
            "a thread whose slot one round start cleared is cleared again by the next: "
                + d.analyze());
    }

    @Test
    void aSeekAndItsReadAfterARoundStartAreStillASequence() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        d.markInvocationStart();
        seekThenRead(d, channel);
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertTrue(d.analyze().hasIssues(),
            "clearing the slot at the round start must not lose the round's own seek");
    }

    @Test
    void positionalOnlyAccessAcrossThreadsIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordPositionalAccess(channel, "read");
        inAnotherThread(() -> d.recordPositionalAccess(channel, "write"));

        assertFalse(d.analyze().hasIssues(),
                "Positional read(buf, pos)/write(buf, pos) never touch the shared cursor and must not be flagged");
    }

    @Test
    void positionalAccessDoesNotContributeToImplicitViolationThreadCount() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        seekThenRead(d, channel);
        inAnotherThread(() -> d.recordPositionalAccess(channel, "write"));

        assertFalse(d.analyze().hasIssues(),
                "A seek-then-read on one thread plus a positional-only thread is not a race");
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object a = new Object();
        Object b = new Object();
        seekThenRead(d, a);
        seekThenRead(d, b);
        inAnotherThread(() -> seekThenRead(d, a));

        var report = d.analyze();
        assertEquals(1, report.violations.size());
    }

    @Test
    void nullsAreIgnored() {
        var d = new FileChannelPositionRaceDetector();
        d.recordImplicitPositionAccess(null, "read");
        d.recordPositionalAccess(null, "read");
        d.recordImplicitPositionAccess(new Object(), null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportDescribesHazardAndFix() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        d.recordImplicitPositionAccess(channel, "write");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "position"));

        String reportText = d.analyze().toString();
        assertTrue(reportText.contains("can land between"), "Should describe the hazard: " + reportText);
        assertFalse(reportText.contains("losing writes"),
                "self-contained writes lose nothing, and the report must not say they do: " + reportText);
        assertTrue(reportText.contains("read(buffer, position) / write(buffer, position)"),
                "Fix hint should mention the positional overloads: " + reportText);
        assertTrue(reportText.contains("AsynchronousFileChannel"),
                "Fix hint should mention AsynchronousFileChannel: " + reportText);
        assertTrue(reportText.contains("one FileChannel per thread"),
                "Fix hint should mention per-thread channels: " + reportText);
        assertTrue(reportText.contains("one lock across the seek and the I/O"),
                "Fix hint should mention holding one lock over the sequence: " + reportText);
    }

    @Test
    void analyzeIsIdempotent() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        seekThenRead(d, channel);
        inAnotherThread(() -> seekThenRead(d, channel));

        var first = d.analyze();
        var second = d.analyze();
        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(first.toString(), second.toString());
    }

    @Test
    void seekThenReadUnderTheChannelsMonitorOnEveryThreadIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Runnable guarded = () -> {
            synchronized (channel) {
                seekThenRead(d, channel);
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "both threads held the channel's monitor across the seek and the read, so no other "
                + "call can land between them: " + d.analyze());
    }

    @Test
    void seekThenReadUnderTheMonitorIsFlaggedWhenAnotherThreadReadsUnguarded() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        synchronized (channel) {
            seekThenRead(d, channel);
        }
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertTrue(d.analyze().hasIssues(),
            "the other thread's read never takes the monitor, so it can still land between the "
                + "guarded seek and its read");
    }

    @Test
    void seekThenReadUnderADeclaredLockIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        Runnable guarded = () -> {
            try (var held = HeldLocks.holding(lock)) {
                seekThenRead(d, channel);
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "a private lock declared through HeldLocks is in the lockset like the channel's own "
                + "monitor, so a seek-then-read held under it on every thread is guarded: "
                + d.analyze());
    }

    /**
     * #831: the lock is held at the seek and at the read, but not in between. Probing it at both
     * ends read that as held across, so the one interleaving the lock was meant to rule out went
     * unreported.
     */
    @Test
    void aLockReleasedAndTakenAgainBetweenTheSeekAndTheReadDoesNotGuardIt() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        try (var held = HeldLocks.holding(lock)) {
            d.recordImplicitPositionAccess(channel, "position");
        }
        try (var held = HeldLocks.holding(lock)) {
            d.recordImplicitPositionAccess(channel, "read");
        }
        inAnotherThread(() -> {
            try (var held = HeldLocks.holding(lock)) {
                d.recordImplicitPositionAccess(channel, "read");
            }
        });
        assertTrue(d.analyze().hasIssues(),
            "the other thread's read, under the same lock, can run while the lock is released "
                + "between this thread's seek and its read");
    }

    @Test
    void aLockHeldAcrossTheSequenceGuardsItWhileAnotherIsReleasedAndTakenAgain() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object outer = new Object();
        Object inner = new Object();
        Runnable guarded = () -> {
            try (var across = HeldLocks.holding(outer)) {
                try (var held = HeldLocks.holding(inner)) {
                    d.recordImplicitPositionAccess(channel, "position");
                }
                try (var held = HeldLocks.holding(inner)) {
                    d.recordImplicitPositionAccess(channel, "read");
                }
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "the outer lock was held from the seek to the read on both threads, so nothing lands "
                + "between them: " + d.analyze());
    }

    @Test
    void aLockTakenAgainInsideTheOneHeldAcrossTheSequenceStillGuardsIt() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        Runnable guarded = () -> {
            try (var across = HeldLocks.holding(lock)) {
                d.recordImplicitPositionAccess(channel, "position");
                try (var reentered = HeldLocks.holding(lock)) {
                    d.recordImplicitPositionAccess(channel, "read");
                }
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "a reentrant acquisition around the read leaves the outer hold in place: " + d.analyze());
    }

    /**
     * With the agent attached, a woven {@code synchronized (channel)} enters and exits
     * {@link HeldLocks} as well as the monitor, so a monitor left and entered again between the
     * seek and the read is visible, although {@link Thread#holdsLock} answers true at both.
     */
    @Test
    void theChannelsMonitorLeftAndEnteredAgainWhereTheAgentSeesItDoesNotGuardTheSequence()
            throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        synchronized (channel) {
            try (var woven = HeldLocks.holding(channel)) {
                d.recordImplicitPositionAccess(channel, "position");
            }
        }
        synchronized (channel) {
            try (var woven = HeldLocks.holding(channel)) {
                d.recordImplicitPositionAccess(channel, "read");
            }
        }
        inAnotherThread(() -> {
            synchronized (channel) {
                try (var woven = HeldLocks.holding(channel)) {
                    d.recordImplicitPositionAccess(channel, "read");
                }
            }
        });
        assertTrue(d.analyze().hasIssues(),
            "the monitor was free between the seek and the read, and the agent saw it released");
    }

    @Test
    void theChannelsMonitorHeldAcrossTheSequenceWhereTheAgentSeesItGuardsIt() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Runnable guarded = () -> {
            synchronized (channel) {
                try (var woven = HeldLocks.holding(channel)) {
                    seekThenRead(d, channel);
                }
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "one woven synchronized block around the seek and the read guards them: " + d.analyze());
    }

    /**
     * #831: after one seek, a thread that keeps reading without seeking again relies on the seek
     * for every read, since each starts where the one before left the cursor. Only the first read
     * used to count, so a second read after the lock was let go looked self-contained.
     */
    @Test
    void aSecondReadAfterOneSeekReliesOnItToo() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        try (var held = HeldLocks.holding(lock)) {
            seekThenRead(d, channel);
        }
        try (var held = HeldLocks.holding(lock)) {
            d.recordImplicitPositionAccess(channel, "read");
        }
        inAnotherThread(() -> {
            try (var held = HeldLocks.holding(lock)) {
                d.recordImplicitPositionAccess(channel, "read");
            }
        });
        assertTrue(d.analyze().hasIssues(),
            "the second read continues from where the first left the cursor, and the other "
                + "thread's read can move it while the lock is free between the two");
    }

    @Test
    void aChainOfReadsAfterOneSeekUnderOneLockIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        Runnable guarded = () -> {
            try (var held = HeldLocks.holding(lock)) {
                seekThenRead(d, channel);
                d.recordImplicitPositionAccess(channel, "read");
                d.recordImplicitPositionAccess(channel, "write");
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "one lock held from the seek through every read and write relying on it: "
                + d.analyze());
    }

    @Test
    void seekingAgainUnderTheLockBeforeEachReadIsNotFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        Runnable guarded = () -> {
            for (int i = 0; i < 2; i++) {
                try (var held = HeldLocks.holding(lock)) {
                    seekThenRead(d, channel);
                }
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "each read relies on the seek made under the same hold, not on the one before the "
                + "lock was let go: " + d.analyze());
    }

    /**
     * Decided and pinned (#831): a {@code position} call is always a seek and never the call
     * relying on an earlier one. The label cannot tell {@code position()} from
     * {@code position(long)}, and even {@code position()} after a write is as likely to start the
     * next sequence as to ask where the write landed. Reading it as relying on the write would
     * report this correct idiom, every access under the lock and a fresh seek before each read, so
     * a {@code write(buffer)} then {@code position()} to learn where it landed stays unreported.
     */
    @Test
    void aSeekAfterAnEarlierCallStartsASequenceRatherThanRelyingOnTheCall() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lock = new Object();
        Runnable guarded = () -> {
            try (var held = HeldLocks.holding(lock)) {
                d.recordImplicitPositionAccess(channel, "write");
            }
            try (var held = HeldLocks.holding(lock)) {
                seekThenRead(d, channel);
            }
        };
        guarded.run();
        inAnotherThread(guarded);
        assertFalse(d.analyze().hasIssues(),
            "a position call after a self-contained write, under a lock taken again, is the "
                + "next sequence's seek: " + d.analyze());
    }

    /**
     * #831: operation names are free-form, and any call after a seek used to be taken as the I/O
     * relying on it, so a {@code truncate} there was judged as a read or write at an offset the
     * thread chose.
     */
    @Test
    void aTruncateAfterASeekIsNotIoRelyingOnIt() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position");
        d.recordImplicitPositionAccess(channel, "truncate");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read"));
        assertFalse(d.analyze().hasIssues(),
            "truncate(size) acts on the size whatever the cursor says, so nothing relied on the "
                + "seek: " + d.analyze());
    }

    @Test
    void aReadAfterASeekAndATruncateStillReliesOnTheSeek() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        d.recordImplicitPositionAccess(channel, "position(n)");
        d.recordImplicitPositionAccess(channel, "truncate(size)");
        d.recordImplicitPositionAccess(channel, "read(buf)");
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "read(buf)"));
        assertTrue(d.analyze().hasIssues(),
            "the truncate in between leaves the seek open, and the read relies on it");
    }

    @Test
    void anotherThreadsTruncateCanLandInsideASequence() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        seekThenRead(d, channel);
        inAnotherThread(() -> d.recordImplicitPositionAccess(channel, "truncate"));
        assertTrue(d.analyze().hasIssues(),
            "a truncate below the cursor moves it, so another thread's truncate is a call that "
                + "can land between the seek and the read");
    }

    @Test
    void seekThenReadUnderDifferentLocksIsFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        Object lockA = new Object();
        Object lockB = new Object();
        try (var held = HeldLocks.holding(lockA)) {
            seekThenRead(d, channel);
        }
        inAnotherThread(() -> {
            try (var held = HeldLocks.holding(lockB)) {
                seekThenRead(d, channel);
            }
        });
        assertTrue(d.analyze().hasIssues(),
            "each thread held a lock, but no lock was common to both sequences, so nothing "
                + "keeps one thread's seek out from between the other's seek and read");
    }

    @Test
    void seekThenReadUnderASharedReadLockIsFlagged() throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        Runnable underReadLock = () -> {
            rw.readLock().lock();
            HeldLocks.acquired(rw, true);
            try {
                seekThenRead(d, channel);
            } finally {
                HeldLocks.released(rw, true);
                rw.readLock().unlock();
            }
        };
        underReadLock.run();
        inAnotherThread(underReadLock);
        assertTrue(d.analyze().hasIssues(),
            "a read lock admits every other reader, so two sequences under it still interleave");
    }

    @Test
    void selfContainedReadsUnderAReadLockBesideASequenceUnderTheWriteLockAreNotFlagged()
            throws Exception {
        var d = new FileChannelPositionRaceDetector();
        Object channel = new Object();
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        HeldLocks.acquired(rw, false);
        try {
            seekThenRead(d, channel);
        } finally {
            HeldLocks.released(rw, false);
            rw.writeLock().unlock();
        }
        inAnotherThread(() -> {
            rw.readLock().lock();
            HeldLocks.acquired(rw, true);
            try {
                d.recordImplicitPositionAccess(channel, "read");
            } finally {
                HeldLocks.released(rw, true);
                rw.readLock().unlock();
            }
        });
        assertFalse(d.analyze().hasIssues(),
            "the write lock excludes the reader for the whole sequence, and self-contained reads "
                + "need not exclude each other: " + d.analyze());
    }
}
