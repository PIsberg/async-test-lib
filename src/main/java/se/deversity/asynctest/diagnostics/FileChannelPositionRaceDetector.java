package se.deversity.asynctest.diagnostics;

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
 * Detects {@link FileChannel} / {@link SeekableByteChannel} instances whose
 * <em>implicit</em> position is read or mutated from more than one thread.
 *
 * <p><strong>Why it matters.</strong> {@code FileChannel} itself is documented
 * as safe for concurrent use, but that guarantee only covers internal state
 * consistency — it says nothing about the shared, stateful cursor that
 * {@link FileChannel#read(ByteBuffer)}, {@link FileChannel#write(ByteBuffer)},
 * {@link FileChannel#position(long)}, {@code truncate(long)}, and
 * {@code transferFrom(...)} all read and advance implicitly. When two threads
 * call these methods on the same channel instance, one thread's
 * {@code position()} call — or the position advance from another thread's
 * {@code read}/{@code write} — can land between another thread's
 * "seek then read/write" pair. The result is I/O performed at the wrong
 * offset: interleaved reads return bytes from the wrong region, interleaved
 * writes silently overwrite or corrupt unrelated file contents, and lost
 * updates are common because the race is rarely reproducible under test.
 *
 * <p>The positional variants {@link FileChannel#read(ByteBuffer, long)} and
 * {@link FileChannel#write(ByteBuffer, long)} do <em>not</em> touch the shared
 * position — each call is self-contained and safe to invoke concurrently on
 * the same channel. This detector only flags implicit-position operations;
 * positional-only concurrent access is not a violation.
 *
 * <p>The safe patterns are: use the positional {@code read(buffer, position)}/
 * {@code write(buffer, position)} overloads, open one {@code FileChannel} per
 * thread, or switch to {@code AsynchronousFileChannel}, whose read/write
 * methods always take an explicit position.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new FileChannelPositionRaceDetector();
 * d.recordImplicitPositionAccess(channel, "read");     // uses the shared cursor
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

    private static final class State {
        final String label;
        final Set<String> operations = ConcurrentHashMap.newKeySet();
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label) {
            this.label = label;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an implicit-position operation: one of {@code read}, {@code write},
     * {@code position}, {@code truncate}, or {@code transferFrom}. These
     * operations read and/or advance the channel's shared cursor and are the
     * source of the race this detector reports on.
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
        Thread thread = Thread.currentThread();
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
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
        int id = System.identityHashCode(channel);
        State s = instances.get(id);
        if (s == null) {
            final String label = channel.getClass().getSimpleName() + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label));
        }
        return s;
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "Channel '%s' had implicit-position operations (%s) from %d threads (%s) — "
                            + "the shared cursor is advanced by every implicit read/write/position call; "
                            + "concurrent use interleaves I/O at unpredictable offsets, corrupting file "
                            + "contents or silently losing writes.",
                    s.label,
                    String.join(", ", s.operations),
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "FileChannelPositionRace",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "operations", String.join(",", s.operations),
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "FILE CHANNEL POSITION RACE — clean";
            StringBuilder sb = new StringBuilder("FILE CHANNEL POSITION RACE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use the positional overloads read(buffer, position) / write(buffer, position),\n")
              .append("      which never touch the shared implicit cursor.\n")
              .append("    - Or open one FileChannel per thread instead of sharing a single instance.\n")
              .append("    - Or switch to AsynchronousFileChannel, whose read/write always take an explicit position.\n");
            return sb.toString();
        }
    }
}
