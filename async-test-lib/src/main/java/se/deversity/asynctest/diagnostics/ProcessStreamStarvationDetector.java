package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;
import org.jspecify.annotations.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects {@code Process.waitFor()} on a child whose output pipe nobody takes.
 *
 * <p>A child process writes its stdout and stderr into pipes of a few kilobytes (about 64 KiB on
 * Linux). Once a pipe is full the child blocks in its write until the parent reads, so a parent
 * that waits for the child before draining it waits for a child that is waiting for it, and
 * neither ever moves. The {@code Process} javadoc warns of exactly this. It shows up only when the
 * child writes enough: the same code passes on a quiet command and hangs on a verbose one, which
 * is why it survives tests.
 *
 * <p>The first wait for a process is recorded with the pipes nobody had taken by then, and judged
 * at analysis: a stream the {@code ProcessBuilder} redirected (to a file, {@code DISCARD} or
 * {@code INHERIT}) has no pipe to fill and is the JDK's null stream, which is not reported. A
 * stderr merged into stdout is the null stream on Windows only; on Linux it is a pipe object at end
 * of stream, so it is recognised from the builder, through {@link #recordStarted}. A pipe taken
 * before the wait through {@link #recordStreamTaken} is assumed to be drained, and so is one a
 * thread other than the waiter took at any point, the drain task that may start after the wait.
 * Both err toward silence. The waiter reading the pipe itself after its wait returned is the
 * defect, and is reported. Reading the output on another thread is the usual fix, or redirecting
 * what is not needed.
 *
 * <p>A pipe handed out by {@link #observeStream} is not taken at its word (#998): the stream notes
 * who reads it and whether it reached its end. Taken by the waiter before the wait, it counts as
 * drained only if a read had reached the end by then; read by another thread at any time, it counts
 * as drained. Take, wait, then read hangs a verbose child exactly like an untaken pipe and is
 * reported.
 *
 * <p>With the agent attached ({@code collections=true}) the woven {@code ProcessBuilder.start} and
 * {@code startPipeline}, the woven {@code waitFor}, and the woven {@code getInputStream},
 * {@code getErrorStream}, {@code inputReader} and {@code errorReader} that take a pipe, feed this
 * detector with no recording call. The woven {@code getInputStream} and {@code getErrorStream} go
 * through {@link #observeStream}, so their caller reads a {@code FilterInputStream} over the pipe
 * rather than the JDK's pipe class; the readers keep the JDK's own.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.PROCESS_STREAM_STARVATION)}:
 * <pre>{@code
 * var d = AsyncTestContext.processStreamStarvationDetector();
 * ProcessBuilder b = new ProcessBuilder("make");
 * Process p = b.start();
 * d.recordStarted(b, p);                      // tells a merged stderr from a pipe
 * d.recordWaitFor(p, Thread.currentThread()); // stdout and stderr still unread: reported
 * p.waitFor();
 * }</pre>
 *
 * @since 1.13.2
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Per-process state in a ConcurrentHashMap keyed by identity, with a get-then-computeIfAbsent "
        + "hot path; takers are concurrent sets; the first wait is claimed once with a CAS.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ProcessStreamStarvationDetectorTest.java"
)
public final class ProcessStreamStarvationDetector {

    /** The two output pipes a child process has. */
    public enum Stream {
        /** The child's standard output, {@code Process.getInputStream()}. */
        STDOUT("stdout"),
        /** The child's standard error, {@code Process.getErrorStream()}. */
        STDERR("stderr");

        private final String label;

        Stream(String label) {
            this.label = label;
        }
    }

    /**
     * Appended when no thread was seen taking a reported pipe at all: a drain in code the agent
     * does not weave takes it unseen, so the finding says how to let the take be seen (#996).
     */
    private static final String NOBODY_SEEN_TAKING = ". No thread was seen taking it: if a library "
            + "drains it in code the agent does not weave (commons-exec, zt-exec), add that library's "
            + "package to the agent's includes= so its take is seen";

    /** The JDK's stream for an output the builder redirected: there is no pipe behind it. */
    private static final String NULL_STREAM = "java.lang.ProcessBuilder$NullInputStream";

    /** The first wait for one process: who waited, and which pipes nobody had taken by then. */
    private record Wait(long waiterId, String waiterName, String process, Set<Stream> untaken) {
    }

    /**
     * A pipe handed out by {@link #observeStream}: the stream itself, which notes who reads it and
     * whether it reached its end. Every read through a reader or a {@code readAllBytes} ends in
     * one of the two {@code read} methods here.
     */
    private static final class ObservedPipe extends FilterInputStream {
        /** Ids of the threads that read from it. */
        final Set<Long> readers = ConcurrentHashMap.newKeySet();
        /** Whether a read returned end of stream. */
        volatile boolean ended;

        ObservedPipe(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            noteRead(b < 0);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            noteRead(n < 0);
            return n;
        }

        private void noteRead(boolean atEnd) {
            long reader = Thread.currentThread().threadId();
            if (!readers.contains(reader)) {
                readers.add(reader);
            }
            if (atEnd) {
                ended = true;
            }
        }
    }

    private static final class State {
        /** Per stream, the ids of the threads that took it through {@link #recordStreamTaken}. */
        final Map<Stream, Set<Long>> takers = new ConcurrentHashMap<>();
        /** Per stream, the pipe {@link #observeStream} handed out, whose reads are seen. */
        final Map<Stream, ObservedPipe> observed = new ConcurrentHashMap<>();
        /** Per stream, the ids of the threads that took it through {@link #observeStream}. */
        final Map<Stream, Set<Long>> observedTakers = new ConcurrentHashMap<>();
        final AtomicReference<@Nullable Wait> firstWait = new AtomicReference<>();
        /** Whether the builder merged stderr into stdout, from {@link #recordStarted}. */
        volatile boolean stderrMerged;
    }

    private final Map<IdentityKey, State> processes = new ConcurrentHashMap<>();

    private State stateOf(Process process) {
        State state = processes.get(IdentityKey.lookup(process));
        if (state == null) {
            state = processes.computeIfAbsent(new IdentityKey(process), k -> new State());
        }
        return state;
    }

    /**
     * Records the builder a process was started from, so a stderr the builder merged into stdout
     * is not taken for a pipe to drain. On Windows the JDK hands out its null stream for a merged
     * stderr, and {@link #recordWaitFor} sees that alone; on Linux it hands out a pipe object at
     * end of stream, which only the builder tells apart from a real one. Without this record a
     * merged stderr on Linux is reported as undrained.
     *
     * @param builder the builder whose {@code start()} returned {@code process} (null-safe)
     * @param process the child process (null-safe)
     */
    public void recordStarted(ProcessBuilder builder, Process process) {
        if (builder == null || process == null) return;
        if (builder.redirectErrorStream()) {
            stateOf(process).stderrMerged = true;
        }
    }

    /**
     * Records that a thread took one of the child's output streams, which is taken to mean it
     * drains it.
     *
     * @param process the child process (null-safe)
     * @param stream  the stream taken
     */
    public void recordStreamTaken(Process process, Stream stream) {
        if (process == null || stream == null) return;
        stateOf(process).takers.computeIfAbsent(stream, s -> ConcurrentHashMap.newKeySet())
                .add(Thread.currentThread().threadId());
    }

    /**
     * Records that the calling thread took {@code raw}, one of the child's output streams, and
     * returns it for the caller to read.
     *
     * @param process the child process
     * @param stream  which output {@code raw} is
     * @param raw     the stream the process returned
     * @return the stream to read
     * @since 1.13.2
     */
    public InputStream observeStream(Process process, Stream stream, InputStream raw) {
        if (process == null || stream == null || !isPipe(raw)) {
            return raw;
        }
        State state = stateOf(process);
        state.observedTakers.computeIfAbsent(stream, s -> ConcurrentHashMap.newKeySet())
                .add(Thread.currentThread().threadId());
        return state.observed.computeIfAbsent(stream, s -> new ObservedPipe(raw));
    }

    /**
     * Records a wait for {@code process}. Only the first wait is kept: the pipes that are real
     * (not redirected by the builder) and that no thread had taken yet. {@link #analyze()} judges
     * them, so a drain task that takes its pipe after the wait began still excuses it.
     *
     * @param process the child process (null-safe)
     * @param thread  the waiting thread (null-safe)
     */
    public void recordWaitFor(Process process, Thread thread) {
        if (process == null || thread == null) return;
        State state = stateOf(process);
        if (state.firstWait.get() != null) return;
        Set<Stream> untaken = EnumSet.noneOf(Stream.class);
        if (undrained(state, Stream.STDOUT) && isPipe(process.getInputStream())) {
            untaken.add(Stream.STDOUT);
        }
        if (!state.stderrMerged && undrained(state, Stream.STDERR) && isPipe(process.getErrorStream())) {
            untaken.add(Stream.STDERR);
        }
        state.firstWait.compareAndSet(null,
                new Wait(thread.threadId(), thread.getName(), describe(process), untaken));
    }

    /**
     * {@return whether {@code stream} counts as not yet drained when the wait begins}: nobody took
     * it through {@link #recordStreamTaken}, and if it was taken through {@link #observeStream},
     * no read had reached its end (#998). A recorded take is still taken at its word.
     */
    private static boolean undrained(State state, Stream stream) {
        if (state.takers.containsKey(stream)) {
            return false;
        }
        ObservedPipe pipe = state.observed.get(stream);
        return pipe == null || !pipe.ended;
    }

    private static boolean isPipe(@Nullable InputStream stream) {
        return stream != null && !NULL_STREAM.equals(stream.getClass().getName());
    }

    /** {@code process 1234}, or the class name where the implementation has no pid. */
    private static String describe(Process process) {
        try {
            return "process " + process.pid();
        } catch (UnsupportedOperationException noPid) {
            return "process " + process.getClass().getName();
        }
    }

    /**
     * Analyses what has been recorded about waited-for processes and builds the report. A pipe
     * untaken at the first wait is excused when a thread other than the waiter took it at any
     * point: that thread is draining while the waiter waits. The waiter taking it itself after
     * the wait returned is the defect, and is reported.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State state : processes.values()) {
            Wait wait = state.firstWait.get();
            if (wait == null) continue;
            List<String> undrained = new ArrayList<>();
            boolean nobodyTookOne = false;
            for (Stream stream : wait.untaken()) {
                if (onlyTheWaiter(state.takers.getOrDefault(stream, Set.of()), wait)
                        && onlyTheWaiter(state.observedTakers.getOrDefault(stream, Set.of()), wait)
                        && onlyTheWaiter(readersOf(state, stream), wait)) {
                    undrained.add(stream.label);
                    nobodyTookOne |= !state.takers.containsKey(stream) && !state.observedTakers.containsKey(stream);
                }
            }
            if (undrained.isEmpty()) continue;
            String finding = String.format(
                    "Thread '%s' waited for %s while no other thread drained its %s - once the "
                            + "child fills the pipe it blocks writing and the wait never returns. "
                            + "Drain the output on another thread, or redirect what is not needed",
                    wait.waiterName(), wait.process(), String.join(" or ", undrained))
                    + (nobodyTookOne ? NOBODY_SEEN_TAKING : "");
            r.violations.add(finding);
            r.structuredViolations.add(new Violation(
                    "ProcessStreamStarvation",
                    IssueSeverity.MEDIUM,
                    finding,
                    List.of(),
                    Map.of(),
                    Instant.now()));
        }
        r.violations.sort(null);
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    private static boolean onlyTheWaiter(Set<Long> threads, Wait wait) {
        return threads.stream().allMatch(id -> id == wait.waiterId());
    }

    private static Set<Long> readersOf(State state, Stream stream) {
        ObservedPipe pipe = state.observed.get(stream);
        return pipe == null ? Set.of() : pipe.readers;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "PROCESS STREAM STARVATION — clean";
            StringBuilder sb = new StringBuilder("PROCESS STREAM STARVATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Read stdout and stderr on their own threads before or while waiting.\n")
              .append("    - Or redirect them: ProcessBuilder.redirectErrorStream(true) merges the two,\n")
              .append("      Redirect.DISCARD, a file or INHERIT leaves no pipe to fill.\n");
            return sb.toString();
        }
    }
}
