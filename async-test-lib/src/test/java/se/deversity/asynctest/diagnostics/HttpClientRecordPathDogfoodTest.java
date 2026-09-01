package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.AsyncTest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dogfoods {@link HttpClientConcurrencyDetector}'s record path with {@code @AsyncTest}.
 *
 * <p>Why this exists: {@code recordClientCreated} is optional in this detector's documented usage,
 * so the ordinary path is that the first {@code recordRequestSent} auto-creates the client state.
 * That auto-creation used to be a {@code findFirst()} over an empty map followed by a plain
 * {@code put}, which is the check-then-act shape this library exists to find. Racing threads all
 * saw the map empty, each built its own {@code ClientState}, and each {@code put} discarded the
 * one before it. Every thread but the last then counted its requests into an object no longer
 * reachable from the map, so {@code analyze()} read a request count of one where four requests
 * had been recorded and a thread count of one where four threads had been at work. Both numbers
 * are what the report is made of: a low request count stops {@code requests > responses} tripping,
 * and the detector goes quiet on exactly the contention it is there to observe.
 *
 * <p>The window is one round wide. After the first collision the entry exists and every later
 * lookup is a hit, which is why a single-shot test cannot see this and why the detector needs a
 * fresh instance per round: {@link #ROUNDS} first-ever registrations, each raced by
 * {@link #THREADS} workers released together by the runner's barrier.
 *
 * <p>The assertion is a sum rather than a per-round check so the failure message can say how much
 * was lost overall, not merely that some round was short.
 */
class HttpClientRecordPathDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 100;
    private static final int EXPECTED_REQUESTS = THREADS * ROUNDS;

    /** One detector per round, so every round races a first-ever client-state registration. */
    private static final Map<Integer, HttpClientConcurrencyDetector> PER_ROUND =
            new ConcurrentHashMap<>();

    /** The runner joins every worker before the next round, so this divides into a round index. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final Pattern REQUESTS_SENT = Pattern.compile("(\\d+) requests sent");
    private static final Pattern THREADS_ACTIVE = Pattern.compile("(\\d+) threads made");

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void everyWorkerRequestLandsOnTheClientStateThatSurvives() {
        int round = SEQUENCE.getAndIncrement() / THREADS;
        HttpClientConcurrencyDetector detector =
                PER_ROUND.computeIfAbsent(round, ignored -> new HttpClientConcurrencyDetector());

        // Deliberately no recordClientCreated: this is the auto-creating path.
        detector.recordRequestSent(new Object(), "dogfood-request");
    }

    @AfterAll
    static void noRequestWasCountedIntoADiscardedClientState() {
        assertEquals(ROUNDS, PER_ROUND.size(),
                "rounds shared a detector, so fewer registrations were raced than rounds");

        int requests = 0;
        int threads = 0;
        int shortRounds = 0;
        for (HttpClientConcurrencyDetector detector : PER_ROUND.values()) {
            HttpClientConcurrencyDetector.HttpClientConcurrencyReport report = detector.analyze();
            int seen = sum(report.uncompletedRequests, REQUESTS_SENT);
            if (seen != THREADS) {
                shortRounds++;
            }
            requests += seen;
            threads += sum(report.threadActivity.values(), THREADS_ACTIVE);
        }

        assertEquals(EXPECTED_REQUESTS, requests,
                shortRounds + " of " + ROUNDS + " rounds reported fewer requests than the "
                        + THREADS + " that were recorded: requests were counted into a client "
                        + "state that a racing registration discarded, so the report undercounts "
                        + "and the request/response mismatch can stop tripping");

        assertEquals(EXPECTED_REQUESTS, threads,
                "the detector saw fewer threads than were at work, for the same reason");
    }

    private static int sum(Iterable<String> lines, Pattern pattern) {
        int total = 0;
        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                total += Integer.parseInt(matcher.group(1));
            }
        }
        return total;
    }
}
