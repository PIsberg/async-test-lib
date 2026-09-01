package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.AsyncTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent-pair lane: JDK types, with test bodies that record nothing at all.
 *
 * <p><strong>Why it exists.</strong> The recording lane reaches a detector by calling the
 * {@code record*} API on its behalf. Eighteen detectors have no such call to make: their input is
 * a JDK call site the agent rewrites, and nothing in {@code AsyncTestContext} stands in for it.
 * They were therefore unpaired in every lane - present in the roster, exercised by nothing, with
 * "no false positive from detector X" and "X never ran" the same row all over again, which is the
 * exact failure the recording lane was built to end.
 *
 * <p><strong>How a pair works here.</strong> There is no instrumentation to write, so the two
 * halves are the bug and its fix, written the way they are written in real code. The MUST_FIRE
 * row puts one instance in a static field and lets every thread call it; the MUST_STAY_SILENT row
 * gives each thread its own. Everything else is the same: the same methods, on the same types,
 * through the same substituted call sites. What separates the rows is which object is on the
 * receiver end, which is precisely the question these detectors claim to answer.
 *
 * <p><strong>What holds it honest.</strong> {@link AgentRowPremise} fails the lane if any body
 * touches the recording API, because a row that fed its own detector would pass while measuring
 * nothing. {@link CorpusGates#checkPairLane} requires the agent to be attached with
 * {@code -javaagent} here, for the same reason the recording lane requires it to be absent: with
 * only one feed live, a finding has exactly one possible source.
 *
 * <p><strong>Why the bodies swallow exceptions.</strong> Sharing these instances is not merely
 * unsound, it throws - {@code SimpleDateFormat.format} from inside its own {@code Calendar},
 * {@code StringBuilder.append} from the array copy, {@code Matcher.group} with no match in
 * progress. That is the bug doing what the bug does, and it happens after the substituted call
 * site has already reported. Letting it out would fail the run for succeeding.
 */
@ExtendWith(SubjectTracking.class)
class CorpusAgentPairLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    /** The pattern every matcher row compiles against; Pattern itself is thread-safe. */
    private static final Pattern PATTERN = Pattern.compile("([a-z]+)-([0-9]+)");

    /** The input both matcher rows match, so the two differ only in the Matcher's scope. */
    private static final String MATCH_INPUT = "corpus-42";

    /** The bytes both digest rows feed, so the two differ only in the digest's scope. */
    private static final byte[] PAYLOAD = "corpus".getBytes(StandardCharsets.UTF_8);

    // --- The shared halves. One instance each, reached by every thread of every round, which is
    //     what a cached formatter in a static field is.

    private static final SimpleDateFormat SHARED_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private static final Matcher SHARED_MATCHER = PATTERN.matcher(MATCH_INPUT);

    private static MessageDigest sharedDigest;

    private static final Calendar SHARED_CALENDAR = Calendar.getInstance();

    private static final StringBuilder SHARED_BUILDER = new StringBuilder();

    private static final DecimalFormat SHARED_DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    private static final Formatter SHARED_FORMATTER = new Formatter(new StringBuilder());

    // --- The confined halves. A ThreadLocal where building the instance is the expense that made
    //     someone cache it in the first place, and a plain local where it is not.

    private static final ThreadLocal<SimpleDateFormat> CONFINED_DATE_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    private static final ThreadLocal<MessageDigest> CONFINED_DIGEST =
            ThreadLocal.withInitial(CorpusAgentPairLaneTest::newDigest);

    private static final ThreadLocal<DecimalFormat> CONFINED_DECIMAL_FORMAT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));

    @BeforeAll
    static void installRecorder() throws NoSuchAlgorithmException {
        CorpusRecorder.install();
        sharedDigest = MessageDigest.getInstance("SHA-256");
    }

    @AfterAll
    static void reportAndGate() throws IOException {
        CorpusRecorder.uninstall();
        SHARED_FORMATTER.close();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeRecording(
                CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus agent-pair-lane report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.recordingSummary(CorpusRecorder.findings(), lane));
        CorpusGates.checkPairLane(
                CorpusRecorder.findings(), lane, CorpusAgentPairLaneTest.class);
    }

    // --- SimpleDateFormat --------------------------------------------------------------------

    /**
     * Every thread formats through the one instance in the static field.
     *
     * <p>{@code format} runs the date into the instance's own {@code Calendar} and then reads the
     * fields back out of it, so a second thread's write lands between the first thread's write and
     * its read. The output is silently wrong when it is not an exception.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_simpleDateFormat_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_DATE_FORMAT.format(new Date()));
    }

    /** The same call, on the per-thread instance the class javadoc tells you to use. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_simpleDateFormat_oneInstancePerThread() {
        swallowingTheRace(() -> CONFINED_DATE_FORMAT.get().format(new Date()));
    }

    // --- Matcher -----------------------------------------------------------------------------

    /**
     * Every thread drives the one Matcher in the static field.
     *
     * <p>A Matcher holds the region, the append position and the group bounds of the last match.
     * Two threads in one instance means {@code group} reads bounds another thread has already
     * replaced, or none at all.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_matcher_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            SHARED_MATCHER.reset();
            if (SHARED_MATCHER.find()) {
                SHARED_MATCHER.group(1);
            }
        });
    }

    /** A Matcher per call from the shared Pattern, which is how the API is meant to be used. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_matcher_oneInstancePerThread() {
        swallowingTheRace(() -> {
            Matcher mine = PATTERN.matcher(MATCH_INPUT);
            mine.reset();
            if (mine.find()) {
                mine.group(1);
            }
        });
    }

    // --- MessageDigest -----------------------------------------------------------------------

    /**
     * Every thread accumulates into the one digest.
     *
     * <p>{@code update} appends to the instance's buffer and {@code digest} drains and resets it,
     * so what comes out is a hash of an interleaving of every thread's input. Nothing throws and
     * nothing looks wrong; the value is just not the hash of anything anyone asked for.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_messageDigest_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            sharedDigest.update(PAYLOAD);
            sharedDigest.digest();
        });
    }

    /** The same two calls against a digest the thread owns, which is the documented pattern. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_messageDigest_oneInstancePerThread() {
        swallowingTheRace(() -> {
            MessageDigest mine = CONFINED_DIGEST.get();
            mine.update(PAYLOAD);
            mine.digest();
        });
    }

    // --- Calendar ----------------------------------------------------------------------------

    /**
     * Every thread reads and writes the one Calendar.
     *
     * <p>{@code get} computes the whole field set from the instance's time on the first call after
     * a change and caches it. A {@code set} from another thread invalidates that cache under a
     * read already in flight.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_calendar_oneInstanceForEveryThread() {
        swallowingTheRace(() -> {
            SHARED_CALENDAR.set(Calendar.MILLISECOND, 0);
            SHARED_CALENDAR.get(Calendar.DAY_OF_YEAR);
        });
    }

    /** The same field traffic against a Calendar nothing else can see. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_calendar_oneInstancePerThread() {
        swallowingTheRace(() -> {
            Calendar mine = Calendar.getInstance();
            mine.set(Calendar.MILLISECOND, 0);
            mine.get(Calendar.DAY_OF_YEAR);
        });
    }

    // --- StringBuilder -----------------------------------------------------------------------

    /**
     * Every thread appends to the one builder.
     *
     * <p>{@code append} reads {@code count}, writes the backing array at that index and writes
     * {@code count} back, with no synchronization anywhere - that is the whole difference between
     * this class and {@code StringBuffer}. A shared one loses appends or throws out of the copy.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_stringBuilder_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_BUILDER.append("x"));
    }

    /** A builder local to the body, which is what the compiler emits for {@code "a" + b}. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_stringBuilder_oneInstancePerThread() {
        swallowingTheRace(() -> new StringBuilder().append("x").append("y"));
    }

    // --- DecimalFormat -----------------------------------------------------------------------

    /**
     * Every thread formats through the one DecimalFormat.
     *
     * <p>It formats through the mutable digit list it inherits from {@code NumberFormat}, whose
     * javadoc states that number formats are not synchronized, so two numbers interleave into one
     * buffer.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_decimalFormat_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_DECIMAL_FORMAT.format(1234.5));
    }

    /** The same call on the per-thread instance the javadoc's remedy produces. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_decimalFormat_oneInstancePerThread() {
        swallowingTheRace(() -> CONFINED_DECIMAL_FORMAT.get().format(1234.5));
    }

    // --- Formatter ---------------------------------------------------------------------------

    /**
     * Every thread formats into the one Formatter.
     *
     * <p>A Formatter writes through to the Appendable it was constructed over and keeps the last
     * {@code IOException} as instance state, so sharing one interleaves the output and the error
     * flag together.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_formatter_oneInstanceForEveryThread() {
        swallowingTheRace(() -> SHARED_FORMATTER.format("%d;", 1));
    }

    /** A Formatter over a local builder, which is what {@code String.format} builds per call. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void agent_formatter_oneInstancePerThread() {
        swallowingTheRace(() -> {
            try (Formatter mine = new Formatter(new StringBuilder())) {
                mine.format("%d;", 1);
            }
        });
    }

    /**
     * Runs {@code body}, discarding whatever the race throws out of it.
     *
     * <p>The shared rows exist to be raced, and these JDK types fail the race by throwing:
     * {@code ArrayIndexOutOfBoundsException} out of a builder's array copy, {@code
     * IllegalStateException} out of a matcher with no match in progress, {@code
     * NumberFormatException} out of a date format's own calendar. The substituted call site has
     * already reported by then. Only unchecked exceptions are caught, so a failure in the harness
     * itself still fails the run.
     *
     * @param body the JDK calls under measurement
     */
    private static void swallowingTheRace(Runnable body) {
        // The corpus's own bookkeeping, not a detector feed: without it the report would print
        // "Body executions: 0" for a lane that ran 3,360 of them. Counted here rather than in the
        // fourteen bodies so that it cannot drift between a pair's two halves.
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (RuntimeException expected) {
            // The bug, behaving like the bug. See the javadoc.
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JRE", e);
        }
    }
}
