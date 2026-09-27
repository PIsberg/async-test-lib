package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
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
 * <p><strong>What is judged.</strong> A {@code position} call a thread records opens a seek; that
 * thread's next other implicit-position call on the same channel, in the same invocation round,
 * is the I/O relying on it, and the two are one sequence. The verdict is {@link SelfGuard}'s,
 * taken per invocation round, with each sequence's I/O as the write and every other
 * implicit-position call as a read: a round is reported when a thread completed a sequence and
 * another thread made an implicit-position call that no lock common to both and no
 * happens-before edge keeps out of it. The channel's own monitor counts as a lock, as does one
 * declared through {@link HeldLocks}; a read lock guards the self-contained calls and not a
 * sequence, since it admits other readers. The locks are probed at the seek and at the I/O, so a
 * lock released and taken again between them reads as held across, and that interleaving is not
 * reported. A self-contained call relying on where an earlier one left the cursor, such as a
 * {@code write(buffer)} followed by {@code position()} to learn where it landed, is not a
 * sequence either.
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

    private static final class State extends SelfGuard.ThreadTrackedInstance {
        final String label;
        final Set<String> operations = ConcurrentHashMap.newKeySet();

        State(String label) {
            this.label = label;
        }
    }

    /** The channel a thread's latest recorded seek set, and the round it was recorded in. */
    private static final class Seek {
        @Nullable Object channel;
        int round;
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

    /**
     * The calling thread's open seek, if it recorded one. Confined to its thread; set on the
     * thread's first seek and reused after that, and its channel is cleared by the I/O that
     * closes the seek.
     */
    private final ThreadLocal<Seek> seeks = new ThreadLocal<>();

    /**
     * Record an implicit-position operation: one of {@code read}, {@code write},
     * {@code position}, or {@code truncate}. These operations use or move the
     * channel's shared cursor.
     *
     * <p>An operation whose name starts with {@code position}, for {@code position(long)} or
     * {@code position()}, is a seek: the calling thread's next other implicit-position call on
     * the same channel, in the same invocation round, is the read or write relying on it. Only
     * such a sequence can be reported, and only when another thread's implicit-position call can
     * land inside it; a self-contained call is recorded so that it can be that other call.
     *
     * @param channel   the channel instance (null-safe)
     * @param operation short name of the operation, e.g. {@code "read"}
     */
    public void recordImplicitPositionAccess(Object channel, String operation) {
        if (channel == null) return;
        State s = stateFor(channel);
        if (operation != null) {
            s.operations.add(operation);
        }
        boolean closesSeek;
        if (operation != null && operation.startsWith("position")) {
            openSeek(channel);
            closesSeek = false;
        } else {
            closesSeek = closeSeek(channel);
        }
        // The I/O closing a seek is the write in SelfGuard's terms: it needs a lock that excludes
        // every other implicit-position call. Everything else is a read, which a round of reads
        // alone never reports and a read lock guards.
        s.noteAccess(channel, closesSeek, Thread.currentThread());
    }

    private void openSeek(Object channel) {
        Seek seek = seeks.get();
        if (seek == null) {
            seek = new Seek();
            seeks.set(seek);
        }
        seek.channel = channel;
        seek.round = SelfGuard.RoundThreads.roundNow();
    }

    /** {@return whether the calling thread's open seek is on {@code channel}, closing it if so} */
    @SuppressWarnings("ReferenceEquality") // channels are tracked by identity, as instances is
    private boolean closeSeek(Object channel) {
        Seek seek = seeks.get();
        if (seek == null || seek.channel != channel) { // NOPMD CompareObjectsWithEquals - channels by identity
            return false;
        }
        seek.channel = null;
        // A seek left open by an earlier round's body says nothing about this round's I/O.
        return seek.round == SelfGuard.RoundThreads.roundNow();
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
            final String label = channel.getClass().getSimpleName() + "@" + id.hashCode();
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
        return r;
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
