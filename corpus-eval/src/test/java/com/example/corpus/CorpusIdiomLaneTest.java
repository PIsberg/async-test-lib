package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The idiom lane: correct user-code concurrency, with every detector switched on.
 *
 * <p><strong>Why it exists.</strong> The other lanes measure documented-safe library classes and
 * pairs written around one detector. Neither measures what a user's own test body looks like: an
 * object handed through a queue, a flag published through a volatile, a counter in an
 * {@code AtomicInteger}. Those are the bodies the false positives fixed for 1.12.3 were found in,
 * by a throwaway probe, and nothing would have noticed them coming back. This lane is that probe,
 * kept and gated.
 *
 * <p><strong>How a row works.</strong> Every body is written the way a user writes it, with
 * {@code detectAll = true} and the agent attached as {@code fields=true,collections=true}, and
 * records nothing, except where the idiom is only observable through the manual API. Those rows
 * are named in {@link Corpus#idiomManualApiRows()} with the reason, and {@link IdiomRowPremise}
 * fails the lane if any other body touches {@code AsyncTestContext}. Each correct row has a broken
 * twin, the same code with the synchronization removed or put in the wrong place, which must fire
 * its natural detector. {@link CorpusGates#checkIdiomLane} holds a correct row to nothing at
 * {@code FACT} tier or above from any detector, and to nothing at any tier from the detector the
 * row names.
 *
 * <p><strong>Why most rows share a per-round slot.</strong> A round runs the body once on each of
 * {@link #THREADS} threads, and the ordering an idiom provides is between threads of one round:
 * rounds are separated by the harness's own barrier. A row that needs one fresh shared object per
 * round, and one thread to play the writer, takes both from {@link Rounds}: a ticket counter, the
 * first ticket of each round as the writer, and an object built for that round before the run.
 * That is harness, not idiom, and it is the same in both halves of every pair.
 */
@ExtendWith(SubjectTracking.class)
class CorpusIdiomLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    /** How long a reader spins for a flag before giving up, so a broken twin cannot hang. */
    private static final int SPIN_LIMIT = 200_000;

    private static final byte[] PAYLOAD = "idiom".getBytes(StandardCharsets.UTF_8);

    @BeforeAll
    static void installRecorder() {
        CorpusRecorder.install();
    }

    @AfterAll
    static void reportAndGate() throws IOException {
        CorpusRecorder.uninstall();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeIdioms(CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus idiom-lane report written to " + report.toAbsolutePath());
        CorpusGates.checkIdiomLane(CorpusRecorder.findings(), lane, CorpusIdiomLaneTest.class);
    }

    // --- Harness -----------------------------------------------------------------------------

    /**
     * A ticket counter and one object per round, built before the run.
     *
     * <p>Rounds run one after another and each runs the body exactly {@link #THREADS} times, so
     * ticket {@code t} belongs to round {@code t / THREADS}, and the first ticket of a round is
     * the round's writer. The per-round objects are built on the class-initialising thread, before
     * any round starts. Every row owns its own instance, so each row's tickets start at zero.
     */
    private static final class Rounds<T> {
        private final AtomicInteger tickets = new AtomicInteger();
        private final Object[] perRound = new Object[INVOCATIONS];

        Rounds(Supplier<? extends T> fresh) {
            for (int round = 0; round < INVOCATIONS; round++) {
                perRound[round] = fresh.get();
            }
        }

        @SuppressWarnings("unchecked")
        Turn<T> next() {
            int ticket = tickets.getAndIncrement();
            return new Turn<>((T) perRound[(ticket / THREADS) % INVOCATIONS], ticket);
        }
    }

    /** One body execution's ticket and its round's shared object. */
    private record Turn<T>(T shared, int ticket) {

        boolean opensTheRound() {
            return ticket % THREADS == 0;
        }

        int round() {
            return ticket / THREADS;
        }
    }

    private interface InterruptibleBody {
        void run() throws InterruptedException;
    }

    /**
     * Runs a correct row's body. Nothing is swallowed: a correct idiom that throws is a failure
     * of the row, not noise.
     */
    private static void correct(InterruptibleBody body) {
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing in this lane interrupts a worker", e);
        }
    }

    /**
     * Runs a broken twin's body, discarding what the race throws: an ArrayDeque or an ArrayList
     * raced by six threads fails by throwing, after the woven call has already been observed.
     */
    private static void broken(InterruptibleBody body) {
        CorpusRecorder.countBodyExecution();
        try {
            body.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing in this lane interrupts a worker", e);
        } catch (RuntimeException expected) {
            // The bug, behaving like the bug.
        }
    }

    private static void getUnchecked(Future<?> task) throws InterruptedException {
        try {
            task.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("the task failed", e.getCause());
        }
    }

    /** Consumes a value so that no read in a body is dead code. */
    private static void use(long value) {
        if (value == Long.MIN_VALUE) {
            throw new AssertionError(value);
        }
    }

    private static BlockingQueue<MessageDigest> digestPool() {
        BlockingQueue<MessageDigest> pool = new LinkedBlockingQueue<>();
        for (int i = 0; i < THREADS; i++) {
            try {
                pool.add(MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every JRE", e);
            }
        }
        return pool;
    }
}