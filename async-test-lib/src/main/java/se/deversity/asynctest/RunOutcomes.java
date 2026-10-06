package se.deversity.asynctest;

import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * Counts what the workers of an {@code @AsyncTest} run did, and asserts on the totals once the run
 * is over: that a gate ran exactly once, that a lock was won at most once, that every worker
 * handed out a different id.
 *
 * <p>Hold one in a static field (or an instance field: the runner drives every round against one
 * test instance), record from the body, and assert in {@code @AfterAll} or {@code @AfterEach}:
 *
 * <pre>{@code
 * private static final RunOutcomes OUTCOMES = new RunOutcomes();
 *
 * @AsyncTest(threads = 8, invocations = 100)
 * void initialise() {
 *     if (service.initialiseIfNeeded()) OUTCOMES.record("initialised");
 *     OUTCOMES.recordValue(idGenerator.next());
 * }
 *
 * @AfterAll
 * static void check() {
 *     OUTCOMES.assertExactlyOnce("initialised");
 *     OUTCOMES.assertDistinct();
 * }
 * }</pre>
 *
 * <p>A failure names the event, the count and the first threads that recorded it. Totals are per
 * run, not per round. Recording costs one counter increment, plus one name for each of the first
 * few recordings of an event; nothing here is visible to the detectors.
 *
 * @since 1.13.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.LOCK_FREE, note = "Counts are LongAdders and values"
        + " sit in a ConcurrentHashMap merge, so concurrent recorders never lose an update; the"
        + " assertions read totals after the run, when no worker is still recording.")
@API(status = Status.EXPERIMENTAL, since = "1.13.0")
public final class RunOutcomes {

    /** How many recorders of one event a failure message names. */
    private static final int THREADS_NAMED = 5;

    /** How many duplicated values a failure message lists. */
    private static final int DUPLICATES_LISTED = 10;

    private final ConcurrentMap<String, Event> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, Integer> values = new ConcurrentHashMap<>();

    /** One event's count and the first threads that recorded it. */
    private static final class Event {
        final LongAdder count = new LongAdder();
        final AtomicInteger named = new AtomicInteger();
        final Queue<String> threads = new ConcurrentLinkedQueue<>();
    }

    /**
     * Records that {@code event} happened once, on the calling thread.
     *
     * @param event a name for what happened, such as {@code "initialised"}
     */
    public void record(String event) {
        Objects.requireNonNull(event, "event");
        Event e = events.computeIfAbsent(event, k -> new Event());
        e.count.increment();
        if (e.named.getAndIncrement() < THREADS_NAMED) {
            e.threads.add(Thread.currentThread().getName());
        }
    }

    /**
     * Records a value a worker produced, for {@link #assertDistinct()}.
     *
     * @param value the value, compared with {@code equals}
     */
    public void recordValue(Object value) {
        Objects.requireNonNull(value, "value");
        values.merge(value, 1, Integer::sum);
    }

    /**
     * {@return how many times {@code event} was recorded}
     *
     * @param event the event's name
     */
    public long count(String event) {
        Event e = events.get(Objects.requireNonNull(event, "event"));
        return e == null ? 0L : e.count.sum();
    }

    /**
     * Fails unless {@code event} was recorded exactly once.
     *
     * @param event the event's name
     * @throws AssertionError naming the count and the threads that recorded it
     */
    public void assertExactlyOnce(String event) {
        long count = count(event);
        if (count != 1) {
            throw new AssertionError(describe(event, count) + ", expected exactly 1" + recorders(event));
        }
    }

    /**
     * Fails if {@code event} was recorded more than once; never recording it passes.
     *
     * @param event the event's name
     * @throws AssertionError naming the count and the threads that recorded it
     */
    public void assertAtMostOnce(String event) {
        long count = count(event);
        if (count > 1) {
            throw new AssertionError(describe(event, count) + ", expected at most 1" + recorders(event));
        }
    }

    /**
     * Fails unless {@code event} was recorded exactly {@code expected} times.
     *
     * @param event    the event's name
     * @param expected the total the run should have produced
     * @throws AssertionError naming the count and the threads that recorded it
     */
    public void assertCount(String event, long expected) {
        long count = count(event);
        if (count != expected) {
            throw new AssertionError(describe(event, count) + ", expected " + expected + recorders(event));
        }
    }

    /**
     * Fails if any value passed to {@link #recordValue(Object)} was recorded more than once.
     *
     * @throws AssertionError listing the duplicated values and how often each was recorded
     */
    public void assertDistinct() {
        Map<String, Integer> duplicates = new TreeMap<>();
        values.forEach((value, times) -> {
            if (times > 1) {
                duplicates.put(String.valueOf(value), times);
            }
        });
        if (!duplicates.isEmpty()) {
            StringBuilder message = new StringBuilder()
                    .append(duplicates.size()).append(" of ").append(values.size())
                    .append(" recorded values were recorded more than once: ");
            int listed = 0;
            for (Map.Entry<String, Integer> d : duplicates.entrySet()) {
                if (listed == DUPLICATES_LISTED) {
                    message.append(", ...");
                    break;
                }
                message.append(listed > 0 ? ", " : "").append(d.getKey())
                        .append(" (").append(d.getValue()).append(" times)");
                listed++;
            }
            throw new AssertionError(message.toString());
        }
    }

    private static String describe(String event, long count) {
        return "'" + event + "' happened " + count + (count == 1 ? " time" : " times");
    }

    private String recorders(String event) {
        Event e = events.get(event);
        return e == null || e.threads.isEmpty() ? "" : "; first recorded by " + String.join(", ", e.threads);
    }
}
