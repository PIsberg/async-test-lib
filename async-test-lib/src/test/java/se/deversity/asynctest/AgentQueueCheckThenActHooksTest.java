package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Check-then-act on a thread-safe queue, reported through the woven queue hooks alone (#1249).
 *
 * <p>{@code if (!queue.isEmpty()) queue.remove()} on a {@code ConcurrentLinkedQueue} throws
 * {@code NoSuchElementException} when another consumer takes the last element between the check
 * and the remove, and with more than one element left it takes an element the check never saw.
 * The hooks see both halves and the head the check saw; a remove that takes a different element,
 * while the head it saw is gone from the queue, is another thread's take landing in between.
 *
 * <p>Every case forces its interleaving: the thread named {@code -1} acts first and lets the
 * thread named {@code -0} act after it, so a raced case holds the take-between in every run.
 */
class AgentQueueCheckThenActHooksTest {

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    /** The two halves of one thread's body, run so that both checks precede both acts, -1's act first. */
    private interface Halves {
        void check();

        void act();
    }

    /**
     * Runs {@code halves} on two threads with {@code context} installed: both check, then the
     * thread named {@code -1} acts, then the thread named {@code -0} does. {@code takeBetween}
     * false runs them one after the other instead, -0 entirely before -1.
     */
    private static void run(AsyncTestContext context, boolean takeBetween,
                            java.util.function.Supplier<Halves> body) throws InterruptedException {
        CountDownLatch bothChecked = new CountDownLatch(2);
        CountDownLatch firstActed = new CountDownLatch(1);
        CountDownLatch zeroDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                AsyncTestContext.install(context);
                try {
                    Halves halves = body.get();
                    if (!takeBetween && id == 1) {
                        await(zeroDone);
                    }
                    halves.check();
                    if (takeBetween) {
                        bothChecked.countDown();
                        await(bothChecked);
                        if (id == 0) {
                            await(firstActed);
                        }
                    }
                    halves.act();
                    if (id == 1) {
                        firstActed.countDown();
                    } else {
                        zeroDone.countDown();
                    }
                } catch (Throwable e) { // NOPMD AvoidCatchingThrowable - rethrown on the test thread
                    failure.set(e);
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "queue-check-then-act-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static BlockingQueueDetector.BlockingQueueReport reportOf(AsyncTestContext context) {
        AsyncTestContext.install(context);
        try {
            return AsyncTestContext.blockingQueueDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static Queue<Object> filled(Queue<Object> queue) {
        for (int i = 0; i < 4; i++) {
            queue.add("job-" + i);
        }
        return queue;
    }

    /** isEmpty, then remove when it said no. */
    private static Halves isEmptyThenRemove(Queue<Object> queue) {
        return new Halves() {
            boolean empty;

            @Override
            public void check() {
                empty = AgentCollectionHooks.queueIsEmpty(queue);
            }

            @Override
            public void act() {
                if (!empty) {
                    AgentCollectionHooks.queueRemove(queue);
                }
            }
        };
    }

    @Test
    @DisplayName("isEmpty then remove on a ConcurrentLinkedQueue, with another thread's take between, is reported")
    void isEmptyThenRemoveIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, true, () -> isEmptyThenRemove(queue));

        BlockingQueueDetector.BlockingQueueReport report = reportOf(context);
        assertTrue(report.hasIssues(), "-1 took the head -0 checked before -0 removed: " + report);
        assertTrue(report.toString().contains("isEmpty-then-remove"), report.toString());
        assertTrue(report.toString().contains("ConcurrentLinkedQueue"), report.toString());
    }

    @Test
    @DisplayName("isEmpty then remove on a LinkedBlockingQueue, with another thread's take between, is reported")
    void isEmptyThenRemoveOnABlockingQueueIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new LinkedBlockingQueue<>());

        run(context, true, () -> isEmptyThenRemove(queue));

        assertTrue(reportOf(context).hasIssues(), reportOf(context).toString());
    }

    @Test
    @DisplayName("peek then remove, with another thread's take between, is reported")
    void peekThenRemoveIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, true, () -> new Halves() {
            Object head;

            @Override
            public void check() {
                head = AgentCollectionHooks.queuePeek(queue);
            }

            @Override
            public void act() {
                if (head != null) {
                    AgentCollectionHooks.queueRemove(queue);
                }
            }
        });

        BlockingQueueDetector.BlockingQueueReport report = reportOf(context);
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("peek-then-remove"), report.toString());
    }

    @Test
    @DisplayName("isEmpty then element, with another thread's take between, is reported")
    void isEmptyThenElementIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, true, () -> new Halves() {
            boolean empty;

            @Override
            public void check() {
                empty = AgentCollectionHooks.queueIsEmpty(queue);
            }

            @Override
            public void act() {
                if (!empty) {
                    // -1 takes for real, so -0's element() reads a head -0 never checked
                    if (Thread.currentThread().getName().endsWith("-1")) {
                        AgentCollectionHooks.queuePoll(queue);
                    } else {
                        AgentCollectionHooks.queueElement(queue);
                    }
                }
            }
        });

        BlockingQueueDetector.BlockingQueueReport report = reportOf(context);
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.toString().contains("isEmpty-then-element"), report.toString());
    }

    @Test
    @DisplayName("an overtaken check is graded PROMPT on HEURISTIC evidence, HIGH severity")
    void overtakenCheckIsGradedPrompt() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, true, () -> isEmptyThenRemove(queue));

        List<GradedFindings.Grade> grades = reportOf(context).grades();
        assertEquals(1, grades.size(), grades.toString());
        assertEquals(TrustTier.PROMPT, grades.get(0).tier());
        assertEquals(DetectorTrust.Evidence.HEURISTIC, grades.get(0).evidence());
        assertEquals(IssueSeverity.HIGH, grades.get(0).severity());
    }

    @Test
    @DisplayName("isEmpty then remove with no other thread's take between stays silent")
    void noTakeBetweenStaysSilent() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, false, () -> isEmptyThenRemove(queue));

        assertEquals(2, queue.size(), "both threads removed one element each");
        assertFalse(reportOf(context).hasIssues(), reportOf(context).toString());
    }

    @Test
    @DisplayName("a poll after the check is the safe form: it consumes the check and stays silent")
    void pollConsumesTheCheck() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ConcurrentLinkedQueue<>());

        run(context, true, () -> new Halves() {
            @Override
            public void check() {
                AgentCollectionHooks.queueIsEmpty(queue);
                AgentCollectionHooks.queuePoll(queue);
            }

            @Override
            public void act() {
                AgentCollectionHooks.queueRemove(queue);
            }
        });

        assertFalse(reportOf(context).hasIssues(), reportOf(context).toString());
    }

    @Test
    @DisplayName("a head changed by an insertion ahead of it, not a take, stays silent")
    void insertionAheadOfTheHeadStaysSilent() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = new PriorityBlockingQueue<>();
        for (int i = 5; i < 9; i++) {
            queue.add(i);
        }

        run(context, true, () -> new Halves() {
            boolean empty;

            @Override
            public void check() {
                empty = AgentCollectionHooks.queueIsEmpty(queue);
            }

            @Override
            public void act() {
                if (Thread.currentThread().getName().endsWith("-1")) {
                    queue.offer(1); // a new smallest element: the head changes, nothing is taken
                } else if (!empty) {
                    AgentCollectionHooks.queueRemove(queue);
                }
            }
        });

        assertFalse(reportOf(context).hasIssues(), reportOf(context).toString());
    }

    @Test
    @DisplayName("a plain ArrayDeque is left to the shared-collection detectors")
    void aPlainDequeIsNotJudged() throws InterruptedException {
        AsyncTestContext context = newContext();
        Queue<Object> queue = filled(new ArrayDeque<>());
        Object lock = new Object();

        run(context, true, () -> new Halves() {
            boolean empty;

            @Override
            public void check() {
                synchronized (lock) {
                    empty = AgentCollectionHooks.queueIsEmpty(queue);
                }
            }

            @Override
            public void act() {
                if (!empty) {
                    synchronized (lock) {
                        AgentCollectionHooks.queueRemove(queue);
                    }
                }
            }
        });

        assertFalse(reportOf(context).hasIssues(), reportOf(context).toString());
    }

    @Test
    @DisplayName("the hooks still return what the queue returned")
    void hooksReturnTheQueuesAnswers() {
        Queue<Object> queue = new ConcurrentLinkedQueue<>();
        assertTrue(AgentCollectionHooks.queueIsEmpty(queue));
        queue.add("only");
        assertFalse(AgentCollectionHooks.queueIsEmpty(queue));
        assertEquals("only", AgentCollectionHooks.queueElement(queue));
        assertEquals("only", AgentCollectionHooks.queueRemove(queue));
        assertNull(AgentCollectionHooks.queuePeek(queue));
    }
}
