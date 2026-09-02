package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the woven coordination hooks can report with no hand-written instrumentation at all.
 *
 * <p>These two detectors are classified {@code AGENT} in {@code DetectorFeeds}, which is the claim
 * that attaching the agent and writing nothing is enough to reach them. It was not: every record
 * path on both resolved through a registry that only the public {@code register*} methods
 * populated, and no hook called one, so both iterated an empty map and could never report in
 * either direction (#436). Each case below therefore goes through the hook exactly as woven code
 * would, records nothing by hand, and asks the detector for its report.
 *
 * <p>Both halves are here on purpose. A detector reachable without registration is worth nothing
 * if it reaches every latch and every queue alike: the counts it infers have to separate misuse
 * from ordinary use of the same type through the same call sites.
 */
class AgentConcurrencyUtilHooksTest {

    private static void installContext() {
        AsyncTestContext.install(
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
    }

    // --- CountDownLatch ---------------------------------------------------------------------

    @Test
    @DisplayName("a latch counted down past its count is reported with no registerLatch call")
    void extraCountDownsAreReachedThroughTheHookAlone() throws InterruptedException {
        installContext();
        try {
            LatchMisuseDetector detector = AsyncTestContext.latchMisuseDetector();
            CountDownLatch overCounted = new CountDownLatch(1);

            AgentConcurrencyUtilHooks.countDown(overCounted);
            AgentConcurrencyUtilHooks.countDown(overCounted);
            assertTrue(AgentConcurrencyUtilHooks.await(overCounted, 1, TimeUnit.SECONDS),
                    "the hook must return what the latch returned, and this one reached zero");

            LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "two countDown() calls on a latch of one is the extraCountDowns condition; "
                            + "the agent is the only feed here, so a silent report means the "
                            + "hooks never registered the latch. Report: " + report);
            assertEquals(1, report.extraCountDowns.size(),
                    "one latch, one finding; got " + report.extraCountDowns);
            assertTrue(report.missingCountDowns.isEmpty(),
                    "the latch did reach zero, so nothing is missing; got "
                            + report.missingCountDowns);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a latch awaited but never counted down to zero is reported as missing countdowns")
    void missingCountDownsAreReachedThroughTheHookAlone() throws InterruptedException {
        installContext();
        try {
            LatchMisuseDetector detector = AsyncTestContext.latchMisuseDetector();
            CountDownLatch neverReached = new CountDownLatch(2);

            AgentConcurrencyUtilHooks.countDown(neverReached);
            assertFalse(AgentConcurrencyUtilHooks.await(neverReached, 1, TimeUnit.MILLISECONDS),
                    "one countDown of two leaves the latch above zero, so the timed await fails");

            LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
            assertEquals(1, report.missingCountDowns.size(),
                    "the initial count has to be inferred as two for this to be a finding; got "
                            + report);
            assertTrue(report.extraCountDowns.isEmpty(),
                    "one countDown of two is not an extra one; got " + report.extraCountDowns);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a latch counted down exactly as often as it was created for stays silent")
    void aCorrectlyUsedLatchStaysSilent() throws InterruptedException {
        installContext();
        try {
            LatchMisuseDetector detector = AsyncTestContext.latchMisuseDetector();
            CountDownLatch correct = new CountDownLatch(2);

            AgentConcurrencyUtilHooks.countDown(correct);
            AgentConcurrencyUtilHooks.countDown(correct);
            AgentConcurrencyUtilHooks.await(correct);

            LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "two of two is neither missing nor extra. The hook path reaches every latch "
                            + "the woven code touches, so a finding here would fire on correct "
                            + "coordination everywhere. Report: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    // --- BlockingQueue ----------------------------------------------------------------------

    @Test
    @DisplayName("a queue filled to its capacity is reported with no registerQueue call")
    void saturationIsReachedThroughTheHookAlone() throws InterruptedException {
        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            BlockingQueue<Object> full = new ArrayBlockingQueue<>(2);

            AgentConcurrencyUtilHooks.put(full, "a");
            assertTrue(AgentConcurrencyUtilHooks.offer(full, "b"), "the second element still fits");
            assertFalse(AgentConcurrencyUtilHooks.offer(full, "c"),
                    "the third does not, and the hook must return the false the caller discards");

            BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "a queue observed at 2 of 2 is saturated, and the capacity has to be inferred "
                            + "from the queue for that comparison to exist at all. Report: "
                            + report);
            assertTrue(report.toString().contains("2/2"),
                    "the finding must name the peak against the inferred capacity; got " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a queue drained as fast as it fills stays silent")
    void aQueueThatKeepsUpStaysSilent() throws InterruptedException {
        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            BlockingQueue<Object> keepingUp = new ArrayBlockingQueue<>(4);

            for (int i = 0; i < 8; i++) {
                AgentConcurrencyUtilHooks.put(keepingUp, i);
                assertEquals(i, AgentConcurrencyUtilHooks.poll(keepingUp),
                        "poll must return what put stored");
            }

            BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "the peak never leaves one of four. Every substituted call site the saturated "
                            + "queue went through, this one went through too. Report: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("an unbounded queue is never read as saturated, however deep it gets")
    void anUnboundedQueueHasNoCapacityToSaturate() throws InterruptedException {
        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            BlockingQueue<Object> unbounded = new LinkedBlockingQueue<>();

            for (int i = 0; i < 64; i++) {
                AgentConcurrencyUtilHooks.put(unbounded, i);
            }

            BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "remainingCapacity() is Integer.MAX_VALUE here, which is the absence of a "
                            + "bound rather than a very large one. Saturation against a bound that "
                            + "does not exist is the false positive this inference could invent. "
                            + "Report: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    // --- The contract every hook owes its call site -------------------------------------------

    @Test
    @DisplayName("a rejected offer whose result was popped is reported through the hooks alone")
    void aDroppedOfferIsReachedThroughTheHooksAlone() {
        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            // No capacity to saturate and no consumer waiting: the offer is rejected, and the
            // only thing that can make this a finding is the fact that nobody read the false.
            BlockingQueue<Object> handoff = new SynchronousQueue<>();

            boolean added = AgentConcurrencyUtilHooks.offer(handoff, "dropped");
            // What the weaver substitutes for the POP after that offer, and for nothing else.
            AgentConcurrencyUtilHooks.offerResultDiscarded(added);

            assertFalse(added, "the handoff has no taker, so the offer is rejected");
            BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "a rejected offer whose boolean was popped is an element dropped, which is "
                            + "the one rejected offer that is a finding (#454). Report: " + report);
            assertTrue(report.toString().contains("discarded the result"),
                    "the finding must say the result was discarded; got " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the same rejected offer with its result read stays a count")
    void aCheckedRejectionStaysSilentThroughTheHooks() {
        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            BlockingQueue<Object> handoff = new SynchronousQueue<>();

            assertFalse(AgentConcurrencyUtilHooks.offer(handoff, "checked"),
                    "rejected here too; the difference is that this test read the false");

            BlockingQueueDetector.BlockingQueueReport report = detector.analyze();
            assertFalse(report.hasIssues(),
                    "a false the caller branched on is backpressure working, not a finding; "
                            + "got " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("every hook performs the operation it replaced with no context installed")
    void hooksDelegateOutsideAnAsyncTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AgentConcurrencyUtilHooks.countDown(latch);
        assertEquals(0L, latch.getCount(), "countDown must have counted down");
        assertTrue(AgentConcurrencyUtilHooks.await(latch, 1, TimeUnit.SECONDS),
                "the latch is at zero, so the timed await returns true");

        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        AgentConcurrencyUtilHooks.put(queue, "q");
        assertFalse(AgentConcurrencyUtilHooks.offer(queue, "r"), "the queue is full");
        // Stands in for a POP: with no context there is nothing to record, and nothing to throw.
        AgentConcurrencyUtilHooks.offerResultDiscarded(false);
        assertEquals("q", AgentConcurrencyUtilHooks.poll(queue), "poll returns the head");
        assertNull(AgentConcurrencyUtilHooks.poll(queue), "and null once it is empty");
    }

    @Test
    @DisplayName("every queue hook registers its receiver, including the overloads added later")
    void everyQueueHookRegistersItsReceiver() throws Exception {
        List<Method> queueHooks = Arrays.stream(AgentConcurrencyUtilHooks.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getParameterCount() > 0)
                .filter(m -> m.getParameterTypes()[0] == BlockingQueue.class)
                .sorted(Comparator.comparing(Method::toString))
                .toList();
        assertEquals(5, queueHooks.size(),
                "the weaver's BlockingQueue entries and this class's hooks are one list; if that "
                        + "count moved, the new hook belongs in this gate too. Found: " + queueHooks);

        installContext();
        try {
            BlockingQueueDetector detector = AsyncTestContext.blockingQueueDetector();
            for (Method hook : queueHooks) {
                BlockingQueue<Object> fresh = new ArrayBlockingQueue<>(4);
                hook.invoke(null, argumentsFor(hook, fresh));

                assertTrue(detector.analyze().toString().contains("queue@" + System.identityHashCode(fresh)),
                        hook.getName() + Arrays.toString(hook.getParameterTypes())
                                + " left its receiver unregistered, so this queue is invisible to "
                                + "every later call on it. An overload that records without "
                                + "registering is silent in exactly the way an unwoven one is");
            }
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /**
     * The arguments a queue hook needs after its receiver.
     *
     * <p>Deliberately shaped by parameter type rather than by method name, so that an overload
     * added tomorrow is exercised by this gate without being named in it.
     *
     * @param hook  the hook to call
     * @param queue the receiver
     */
    private static Object[] argumentsFor(Method hook, BlockingQueue<Object> queue) {
        Class<?>[] types = hook.getParameterTypes();
        Object[] arguments = new Object[types.length];
        arguments[0] = queue;
        for (int i = 1; i < types.length; i++) {
            if (types[i] == long.class) {
                arguments[i] = 1L;
            } else if (types[i] == TimeUnit.class) {
                arguments[i] = TimeUnit.MILLISECONDS;
            } else {
                arguments[i] = "element";
            }
        }
        return arguments;
    }
}
