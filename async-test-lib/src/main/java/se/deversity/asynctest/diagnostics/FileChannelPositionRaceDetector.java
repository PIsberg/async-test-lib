package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects a {@link FileChannel} / {@link SeekableByteChannel} whose implicit position one
 * thread sets and then relies on while another thread's call can move it in between.
 *
 * <p><strong>Why it matters.</strong> {@code FileChannel} is documented as safe for use by
 * multiple concurrent threads, and it runs one operation involving the position at a time, so a
 * single {@link FileChannel#read(ByteBuffer)} or {@link FileChannel#write(ByteBuffer)} always
 * completes whole, at the offset the cursor held when it started. What the channel cannot make
 * atomic is a sequence of calls: a thread that calls {@link FileChannel#position(long)} (or reads
 * {@link FileChannel#position()}) and then reads or writes relying on that offset has nothing to
 * stop another thread's read, write or {@code position} call from landing between the two. The
 * I/O then happens at an offset its thread did not choose: a read returns bytes from the wrong
 * region, and a write lands somewhere other than where it was aimed, over whatever is there. A
 * probe on JDK 21 and 26 (8 threads, 5,000 operations each) read the wrong bytes about 1,500
 * times in 40,000 with an unguarded {@code position(n)} then {@code read(buffer)}, and lost
 * nothing with unguarded self-contained {@code read(buffer)} or {@code write(buffer)} calls, which
 * is why those alone are no finding (#819).
 *
 * <p>The positional variants {@link FileChannel#read(ByteBuffer, long)} and
 * {@link FileChannel#write(ByteBuffer, long)} do <em>not</em> touch the shared
 * position — each call is self-contained and safe to invoke concurrently on
 * the same channel, and they are recorded through {@link #recordPositionalAccess}.
 *
 * <p>The safe patterns are: use the positional {@code read(buffer, position)}/
 * {@code write(buffer, position)} overloads, hold one lock across the seek and the I/O on every
 * thread that uses the channel, open one {@code FileChannel} per thread, or switch to
 * {@code AsynchronousFileChannel}, whose read/write methods always take an explicit position.
 *
 * <p><strong>What is judged.</strong> A {@code position} call a thread records opens a seek, and
 * every read or write that thread makes on the same channel after it, in the same invocation round
 * and until its next seek on that channel, is I/O relying on it: each starts where the seek and the
 * calls since left the cursor, so a second read after one seek relies on it as much as the first,
 * and so does a read after a seek on another channel. A thread keeps an open seek on up to
 * four channels at once, and a seek on one more forgets the one sought longest
 * ago (#835). Any other call, such as a {@code truncate}, relies on nothing and leaves the seek
 * open (#831). A call that neither uses nor moves the position ({@code size}, {@code force},
 * {@code transferTo}, {@code transferFrom}, {@code map}, {@code lock}, {@code tryLock}) is not
 * recorded at all, so it is no call that can land inside a sequence either; any other name counts,
 * as {@code truncate} does (#835). The verdict
 * is {@link SelfGuard}'s, taken per invocation round, with each sequence's I/O as the write and
 * every other implicit-position call as a read: a round is reported when a thread completed a
 * sequence and another thread made an implicit-position call that no lock common to both and no
 * happens-before edge keeps out of it. The channel's own monitor counts as a lock, as does one
 * declared through {@link HeldLocks}; a read lock guards the self-contained calls and not a
 * sequence, since it admits other readers. A lock guards a sequence only if its thread held it
 * without a break from the seek to the I/O: one released and taken again between them lets another
 * thread's call in, and is not counted for the I/O (#831). That is decided from {@link HeldLocks},
 * so it covers declared locks and, with the agent attached, woven {@code synchronized} blocks and
 * {@code Lock} calls; the channel's own monitor left and entered again in code the agent does not
 * weave still reads as held across, since nothing reports its release. For the same reason a
 * monitor that code the agent does not weave held at the seek counts at the I/O whenever the thread
 * holds it there, woven re-entries of it included (#835): an unwoven {@code synchronized (channel)}
 * around the sequence guards it with a woven block around the read inside it, and an unwoven block
 * around the seek, left before a woven block around the read, reads as held across. A woven block
 * around the seek inside an unwoven one, left and entered again before the read, still reads as a
 * break, since nothing tells the outer hold from none. A {@code position} call is
 * always a seek, never I/O relying on the call before it: the operation name cannot tell
 * {@code position()} from {@code position(long)}, and even a {@code position()} after a write
 * starts the next sequence as often as it asks where the write landed. So a self-contained call
 * relying on where an earlier one left the cursor, such as a {@code write(buffer)} followed by
 * {@code position()} to learn where it landed, is not a sequence; reading it as one would report
 * every thread that seeks afresh under its lock after an earlier call.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new FileChannelPositionRaceDetector();
 * d.recordImplicitPositionAccess(channel, "position");  // the seek
 * d.recordImplicitPositionAccess(channel, "read");      // the read relying on it
 * d.recordPositionalAccess(channel, "read");            // explicit offset, safe
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet() and track only implicit-position accessors.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/FileChannelPositionRaceDetectorTest.java"
)
public final class FileChannelPositionRaceDetector {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    private static final class State extends SelfGuard.ThreadTrackedInstance {
        final String label;
        final Set<String> operations = ConcurrentHashMap.newKeySet();

        State(String label) {
            this.label = label;
        }
    }

    /**
     * How many channels a thread keeps an open seek on at once. A seek on one more replaces the
     * one sought longest ago, and I/O relying on that one is then judged as self-contained.
     */
    private static final int SEEK_SLOTS = 4;

    /**
     * Operation names that neither use nor move the position. {@code FileChannel} moves it only in
     * {@code read}, {@code write}, {@code position(long)} and {@code truncate}; {@code size} and
     * {@code force} do not touch it, and {@code transferTo}, {@code transferFrom}, {@code map},
     * {@code lock} and {@code tryLock} take an explicit position, the transfers documented as not
     * modifying the channel's. Any other name may move it, and counts.
     */
    private static final String[] NEUTRAL_OPERATIONS = {
        "size", "force", "transferTo", "transferFrom", "map", "lock", "tryLock"
    };

    /**
     * The channel one of a thread's recorded seeks set, the round it was recorded in, and where
     * the thread's lock acquisitions stood at it.
     */
    private static final class Seek {
        @Nullable Object channel;
        int round;

        /** {@link HeldLocks#acquisitionMark()} at the seek; only locks held since guard the I/O. */
        long locksMark;

        /**
         * {@link HeldLocks#heldUnseen(Object)} for the channel at the seek: its monitor held by
         * code the agent did not weave, whose release nothing reports (#835).
         */
        boolean monitorHeldUnseen;

        /** When the thread made this seek, in its {@link Seeks#clock}; the oldest is replaced. */
        long soughtAt;
    }

    /** A thread's open seeks, at most one per channel and {@link #SEEK_SLOTS} of them. */
    private static final class Seeks {
        final Seek[] slots = new Seek[SEEK_SLOTS];

        /** Counts this thread's seeks, to order its slots by age. */
        long clock;

        /** Whether these seeks are on {@link #openSeeks}, where a round start finds them. */
        boolean listed;

        /** The seeks listed before these on {@link #openSeeks}, or {@code null} at the end. */
        @Nullable Seeks next;

        Seeks() {
            for (int i = 0; i < SEEK_SLOTS; i++) {
                slots[i] = new Seek();
            }
        }

        /**
         * {@return the slot a seek on {@code channel} goes in: the one already on it, else a free
         * one or one from an earlier round, else the one sought longest ago}
         */
        @SuppressWarnings("ReferenceEquality") // channels are tracked by identity, as instances is
        Seek slotFor(Object channel, int round) {
            Seek replaced = slots[0];
            for (Seek slot : slots) {
                if (slot.channel == channel) { // NOPMD CompareObjectsWithEquals - channels by identity
                    return slot;
                }
                if (slot.channel == null || slot.round != round) {
                    replaced = slot;
                } else if (replaced.channel != null && replaced.round == round
                        && slot.soughtAt < replaced.soughtAt) {
                    replaced = slot;
                }
            }
            return replaced;
        }

        /** {@return the slot holding a seek on {@code channel}, or {@code null}} */
        @SuppressWarnings("ReferenceEquality") // channels are tracked by identity, as instances is
        @Nullable Seek slotOn(Object channel) {
            for (Seek slot : slots) {
                if (slot.channel == channel) { // NOPMD CompareObjectsWithEquals - channels by identity
                    return slot;
                }
            }
            return null;
        }
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

    /**
     * The calling thread's open seeks, if it recorded any. Confined to its thread; set on the
     * thread's first seek and reused after that, each slot replaced by a later seek on its channel
     * or, when every slot is taken, on another, and every channel cleared at the next round start.
     */
    private final ThreadLocal<Seeks> seeks = new ThreadLocal<>();

    /**
     * Every thread's seeks opened since the last round start, linked through {@link Seeks#next},
     * so that {@link #markInvocationStart()}, which runs on the runner thread, can reach the slots
     * of the workers. The holders are the links, so listing one allocates nothing.
     */
    private final AtomicReference<@Nullable Seeks> openSeeks = new AtomicReference<>();

    /**
     * Record an implicit-position operation: one of {@code read}, {@code write},
     * {@code position}, or {@code truncate}. These operations use or move the
     * channel's shared cursor. {@code transferTo} and {@code transferFrom} do neither, by the
     * {@code FileChannel} javadoc, so they are not recorded here.
     *
     * <p>An operation whose name starts with {@code position}, for {@code position(long)} or
     * {@code position()}, is a seek. One whose name starts with {@code read} or {@code write},
     * made by the calling thread on the same channel after a seek, in the same invocation round
     * and until its next seek on that channel, is I/O relying on that seek. A name starting with
     * {@code size}, {@code force}, {@code transferTo}, {@code transferFrom}, {@code map},
     * {@code lock} or {@code tryLock} neither uses nor moves the position and is ignored. Any
     * other name, {@code truncate} among them, relies on nothing and leaves an open seek open.
     * Only a seek with I/O relying on it can be reported, and only when another thread's
     * implicit-position call can land inside it; every call but an ignored one is recorded so
     * that it can be that other call.
     *
     * @param channel   the channel instance (null-safe)
     * @param operation short name of the operation, e.g. {@code "read"}
     */
    public void recordImplicitPositionAccess(Object channel, String operation) {
        if (channel == null || neutral(operation)) return;
        State s = stateFor(channel);
        if (operation != null) {
            s.operations.add(operation);
        }
        Thread caller = Thread.currentThread();
        Seek reliedOn = null;
        if (operation != null && operation.startsWith("position")) {
            openSeek(channel);
        } else if (operation != null
                && (operation.startsWith("read") || operation.startsWith("write"))) {
            reliedOn = seekReliedOn(channel);
        }
        if (reliedOn == null) {
            // Everything but I/O relying on a seek is a read in SelfGuard's terms, which a round
            // of reads alone never reports and a read lock guards.
            s.noteAccess(channel, false, caller);
            return;
        }
        // I/O relying on a seek is the write: it needs a lock that excludes every other
        // implicit-position call, and one released and taken again since the seek did not (#831).
        long previous = HeldLocks.countOnlyHeldSince(reliedOn.locksMark, reliedOn.monitorHeldUnseen);
        try {
            s.noteAccess(channel, true, caller);
        } finally {
            HeldLocks.countOnlyHeldSince(previous);
        }
    }

    /** {@return whether {@code operation} names a call that neither uses nor moves the position} */
    private static boolean neutral(@Nullable String operation) {
        if (operation == null) {
            return false;
        }
        for (String name : NEUTRAL_OPERATIONS) {
            if (operation.startsWith(name)) {
                return true;
            }
        }
        return false;
    }

    private void openSeek(Object channel) {
        Seeks mine = seeks.get();
        if (mine == null) {
            mine = new Seeks();
            seeks.set(mine);
        }
        int round = SelfGuard.RoundThreads.roundNow();
        Seek seek = mine.slotFor(channel, round);
        seek.channel = channel;
        seek.round = round;
        seek.locksMark = HeldLocks.acquisitionMark();
        seek.monitorHeldUnseen = HeldLocks.heldUnseen(channel);
        mine.clock++;
        seek.soughtAt = mine.clock;
        if (!mine.listed) {
            mine.listed = true;
            Seeks head;
            do {
                head = openSeeks.get();
                mine.next = head;
            } while (!openSeeks.compareAndSet(head, mine));
        }
    }

    /**
     * {@return the calling thread's open seek on {@code channel} when it is from this round, or
     * {@code null} when the call relies on no seek}
     *
     * <p>The seek stays open for the calls after this one, which start where this one leaves the
     * cursor.
     */
    private @Nullable Seek seekReliedOn(Object channel) {
        Seeks mine = seeks.get();
        Seek seek = mine == null ? null : mine.slotOn(channel);
        if (seek == null) {
            return null;
        }
        if (seek.round != SelfGuard.RoundThreads.roundNow()) {
            // A seek left open by an earlier round's body says nothing about this round's I/O.
            seek.channel = null;
            return null;
        }
        return seek;
    }

    /**
     * Starts a new invocation round: every thread's open seeks are forgotten, and their slots let go
     * of the channels.
     *
     * <p>A seek is relied on only within its round, which the round clock already decides, but the
     * slot of a pooled worker held the channel reference until that worker sought again, if it ever
     * did (#831). No worker is running when the runner calls this; a seek that a thread the test
     * left running records meanwhile may stay listed or be forgotten, and either way is not relied
     * on in a later round.
     *
     * @since 1.12.3
     */
    public void markInvocationStart() {
        Seeks listed = openSeeks.getAndSet(null);
        while (listed != null) {
            Seeks next = listed.next;
            for (Seek slot : listed.slots) {
                slot.channel = null;
            }
            listed.next = null;
            listed.listed = false;
            listed = next;
        }
    }

    /**
     * Record a positional operation: {@code read(buffer, position)} or
     * {@code write(buffer, position)}. These take an explicit offset, never
     * touch the shared cursor, and are safe to call concurrently — this
     * detector never flags them as a race.
     *
     * @param channel   the channel instance (null-safe)
     * @param operation short name of the operation, e.g. {@code "read"}
     */
    public void recordPositionalAccess(Object channel, String operation) {
        if (channel == null) return;
        stateFor(channel);
    }

    private State stateFor(Object channel) {
        IdentityKey id = new IdentityKey(channel);
        State s = instances.get(id);
        if (s == null) {
            final String label = unnamedLabels.of(id, channel.getClass().getSimpleName());
            s = instances.computeIfAbsent(id, k -> new State(label));
        }
        return s;
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (!s.sharedAndUnguarded()) continue;
            String msg = String.format(
                    "Channel '%s' had implicit-position operations (%s) from %d threads (%s), and "
                            + "one thread set or read the position and then read or wrote relying on "
                            + "it; another thread's call can land between the seek and the I/O, so "
                            + "the I/O runs at an offset its thread did not choose: a read returns "
                            + "bytes from the wrong region, a write lands away from where it was "
                            + "aimed, over whatever is there." + SelfGuard.REPORT_NOTE,
                    s.label,
                    String.join(", ", s.operations),
                    s.threadCount(),
                    String.join(", ", s.threadNames()));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "FileChannelPositionRace",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "operations", String.join(",", s.operations),
                            "threadCount", s.threadCount()),
                    Instant.now()));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "FILE CHANNEL POSITION RACE — clean";
            StringBuilder sb = new StringBuilder("FILE CHANNEL POSITION RACE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use the positional overloads read(buffer, position) / write(buffer, position),\n")
              .append("      which never touch the shared implicit cursor.\n")
              .append("    - Or hold one lock across the seek and the I/O on every thread that uses the channel.\n")
              .append("    - Or open one FileChannel per thread instead of sharing a single instance.\n")
              .append("    - Or switch to AsynchronousFileChannel, whose read/write always take an explicit position.\n");
            return sb.toString();
        }
    }
}
