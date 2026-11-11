package se.deversity.asynctest.agent;

import com.example.agentfixture.OfferShapesSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentConcurrencyUtilHooks;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-instruction lookahead: a popped offer result reaches the discard hook, nothing else does.
 *
 * <p>Woven against a stub so the assertion is about which instruction was rewritten rather than
 * what a detector made of it. Loading the woven class is itself the strongest check here: the
 * verifier runs on it, so a stale flag that rewrote the wrong {@code POP} fails the load rather
 * than an assertion (#454).
 */
class DiscardedOfferWeavingTest {

    /** Every result handed to the discard hook, in order. */
    static final ConcurrentLinkedQueue<Boolean> DISCARDED = new ConcurrentLinkedQueue<>();

    /** How many offers, of either form, went through the stub. */
    static final AtomicInteger OFFERS = new AtomicInteger();

    /** Every root future handed to the static-call discard hook. */
    static final ConcurrentLinkedQueue<Object> DISCARDED_ROOTS = new ConcurrentLinkedQueue<>();

    /**
     * Stands in for {@link AgentConcurrencyUtilHooks}. One method per concurrency-table entry,
     * because the weaver resolves the whole table against this class and throws for a missing one.
     */
    public static class StubConcurrencyHooks {
        public static void acquire(Semaphore s) throws InterruptedException {
            s.acquire();
        }

        public static boolean tryAcquire(Semaphore s) {
            return s.tryAcquire();
        }

        public static void release(Semaphore s) {
            s.release();
        }

        public static void acquire(Semaphore s, int permits) throws InterruptedException {
            s.acquire(permits);
        }

        public static boolean tryAcquire(Semaphore s, int permits) {
            return s.tryAcquire(permits);
        }

        public static boolean tryAcquire(Semaphore s, long timeout, TimeUnit unit)
                throws InterruptedException {
            return s.tryAcquire(timeout, unit);
        }

        public static boolean tryAcquire(Semaphore s, int permits, long timeout, TimeUnit unit)
                throws InterruptedException {
            return s.tryAcquire(permits, timeout, unit);
        }

        public static void release(Semaphore s, int permits) {
            s.release(permits);
        }

        public static void countDown(CountDownLatch l) {
            l.countDown();
        }

        public static void await(CountDownLatch l) throws InterruptedException {
            l.await();
        }

        public static boolean await(CountDownLatch l, long timeout, TimeUnit unit)
                throws InterruptedException {
            return l.await(timeout, unit);
        }

        public static int await(java.util.concurrent.CyclicBarrier b)
                throws InterruptedException, java.util.concurrent.BrokenBarrierException {
            return b.await();
        }

        public static int await(java.util.concurrent.CyclicBarrier b, long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.BrokenBarrierException,
                java.util.concurrent.TimeoutException {
            return b.await(timeout, unit);
        }

        public static int arriveAndAwaitAdvance(java.util.concurrent.Phaser p) {
            return p.arriveAndAwaitAdvance();
        }

        public static int arrive(java.util.concurrent.Phaser p) {
            return p.arrive();
        }

        public static int arriveAndDeregister(java.util.concurrent.Phaser p) {
            return p.arriveAndDeregister();
        }

        public static int awaitAdvance(java.util.concurrent.Phaser p, int phase) {
            return p.awaitAdvance(phase);
        }

        public static int awaitAdvanceInterruptibly(java.util.concurrent.Phaser p, int phase)
                throws InterruptedException {
            return p.awaitAdvanceInterruptibly(phase);
        }

        public static int awaitAdvanceInterruptibly(java.util.concurrent.Phaser p, int phase,
                long timeout, TimeUnit unit)
                throws InterruptedException, java.util.concurrent.TimeoutException {
            return p.awaitAdvanceInterruptibly(phase, timeout, unit);
        }

        public static boolean offer(BlockingQueue<Object> q, Object e) {
            OFFERS.incrementAndGet();
            return q.offer(e);
        }

        public static Object poll(BlockingQueue<Object> q) {
            return q.poll();
        }

        public static void put(BlockingQueue<Object> q, Object e) throws InterruptedException {
            q.put(e);
        }

        public static boolean offer(BlockingQueue<Object> q, Object e, long timeout, TimeUnit unit)
                throws InterruptedException {
            OFFERS.incrementAndGet();
            return q.offer(e, timeout, unit);
        }

        public static Object poll(BlockingQueue<Object> q, long timeout, TimeUnit unit)
                throws InterruptedException {
            return q.poll(timeout, unit);
        }

        public static void offerResultDiscarded(boolean added) {
            DISCARDED.add(added);
        }

        public static Object take(BlockingQueue<Object> q) throws InterruptedException {
            return q.take();
        }

        public static int drainTo(BlockingQueue<Object> q, Collection<Object> target) {
            return q.drainTo(target);
        }

        public static int drainTo(BlockingQueue<Object> q, Collection<Object> target,
                                  int maxElements) {
            return q.drainTo(target, maxElements);
        }

        // The hand-off rows (#741), which this test never reaches but the table resolves.

        public static boolean complete(java.util.concurrent.CompletableFuture<Object> f, Object v) {
            return f.complete(v);
        }

        public static boolean completeExceptionally(java.util.concurrent.CompletableFuture<Object> f,
                                                    Throwable t) {
            return f.completeExceptionally(t);
        }

        public static void obtrudeValue(java.util.concurrent.CompletableFuture<Object> f, Object v) {
            f.obtrudeValue(v);
        }

        public static void obtrudeException(java.util.concurrent.CompletableFuture<Object> f,
                                            Throwable t) {
            f.obtrudeException(t);
        }

        public static Object join(java.util.concurrent.CompletableFuture<Object> f) {
            return f.join();
        }

        public static Object get(java.util.concurrent.Future<Object> f) throws Exception {
            return f.get();
        }

        public static Object get(java.util.concurrent.Future<Object> f, long timeout, TimeUnit unit)
                throws Exception {
            return f.get(timeout, unit);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenApply(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenApply(a);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenApplyAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenApplyAsync(a);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenApplyAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenApplyAsync(a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAccept(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Consumer<Object> a) {
            return r.thenAccept(a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAcceptAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Consumer<Object> a) {
            return r.thenAcceptAsync(a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAcceptAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Consumer<Object> a, java.util.concurrent.Executor e) {
            return r.thenAcceptAsync(a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenRun(
                java.util.concurrent.CompletableFuture<Object> r, Runnable a) {
            return r.thenRun(a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenRunAsync(
                java.util.concurrent.CompletableFuture<Object> r, Runnable a) {
            return r.thenRunAsync(a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenRunAsync(
                java.util.concurrent.CompletableFuture<Object> r, Runnable a, java.util.concurrent.Executor e) {
            return r.thenRunAsync(a, e);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenCombine(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.thenCombine(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenCombineAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.thenCombineAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Object> thenCombineAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenCombineAsync(o, a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAcceptBoth(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a) {
            return r.thenAcceptBoth(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAcceptBothAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a) {
            return r.thenAcceptBothAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> thenAcceptBothAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenAcceptBothAsync(o, a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterBoth(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterBoth(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterBothAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterBothAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterBothAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a, java.util.concurrent.Executor e) {
            return r.runAfterBothAsync(o, a, e);
        }

        public static java.util.concurrent.CompletableFuture<Object> applyToEither(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a) {
            return r.applyToEither(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Object> applyToEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a) {
            return r.applyToEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Object> applyToEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.applyToEitherAsync(o, a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> acceptEither(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a) {
            return r.acceptEither(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> acceptEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a) {
            return r.acceptEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> acceptEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a, java.util.concurrent.Executor e) {
            return r.acceptEitherAsync(o, a, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterEither(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterEither(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAfterEitherAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a, java.util.concurrent.Executor e) {
            return r.runAfterEitherAsync(o, a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> thenCompose(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenCompose((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> thenComposeAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenComposeAsync((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> thenComposeAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenComposeAsync((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a, e);
        }

        public static java.util.concurrent.CompletableFuture<Object> whenComplete(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiConsumer<Object, Object> a) {
            return r.whenComplete(a);
        }

        public static java.util.concurrent.CompletableFuture<Object> whenCompleteAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiConsumer<Object, Object> a) {
            return r.whenCompleteAsync(a);
        }

        public static java.util.concurrent.CompletableFuture<Object> whenCompleteAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiConsumer<Object, Object> a, java.util.concurrent.Executor e) {
            return r.whenCompleteAsync(a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> handle(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.handle((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> handleAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.handleAsync((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> handleAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.BiFunction<Object, Object, Object> a, java.util.concurrent.Executor e) {
            return r.handleAsync((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionally(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionally((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionallyAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyAsync((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionallyAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.exceptionallyAsync((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionallyCompose(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyCompose((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionallyComposeAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyComposeAsync((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletableFuture<Object> exceptionallyComposeAsync(
                java.util.concurrent.CompletableFuture<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.exceptionallyComposeAsync((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a, e);
        }

        public static java.util.concurrent.CompletionStage<Object> thenApply(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenApply(a);
        }

        public static java.util.concurrent.CompletionStage<Object> thenApplyAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenApplyAsync(a);
        }

        public static java.util.concurrent.CompletionStage<Object> thenApplyAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenApplyAsync(a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAccept(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Consumer<Object> a) {
            return r.thenAccept(a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAcceptAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Consumer<Object> a) {
            return r.thenAcceptAsync(a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAcceptAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Consumer<Object> a, java.util.concurrent.Executor e) {
            return r.thenAcceptAsync(a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> thenRun(
                java.util.concurrent.CompletionStage<Object> r, Runnable a) {
            return r.thenRun(a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenRunAsync(
                java.util.concurrent.CompletionStage<Object> r, Runnable a) {
            return r.thenRunAsync(a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenRunAsync(
                java.util.concurrent.CompletionStage<Object> r, Runnable a, java.util.concurrent.Executor e) {
            return r.thenRunAsync(a, e);
        }

        public static java.util.concurrent.CompletionStage<Object> thenCombine(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.thenCombine(o, a);
        }

        public static java.util.concurrent.CompletionStage<Object> thenCombineAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.thenCombineAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Object> thenCombineAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiFunction<Object, Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenCombineAsync(o, a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAcceptBoth(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a) {
            return r.thenAcceptBoth(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAcceptBothAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a) {
            return r.thenAcceptBothAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> thenAcceptBothAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.BiConsumer<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenAcceptBothAsync(o, a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterBoth(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterBoth(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterBothAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterBothAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterBothAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a, java.util.concurrent.Executor e) {
            return r.runAfterBothAsync(o, a, e);
        }

        public static java.util.concurrent.CompletionStage<Object> applyToEither(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a) {
            return r.applyToEither(o, a);
        }

        public static java.util.concurrent.CompletionStage<Object> applyToEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a) {
            return r.applyToEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Object> applyToEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.applyToEitherAsync(o, a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> acceptEither(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a) {
            return r.acceptEither(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> acceptEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a) {
            return r.acceptEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> acceptEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, java.util.function.Consumer<Object> a, java.util.concurrent.Executor e) {
            return r.acceptEitherAsync(o, a, e);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterEither(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterEither(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a) {
            return r.runAfterEitherAsync(o, a);
        }

        public static java.util.concurrent.CompletionStage<Void> runAfterEitherAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.concurrent.CompletionStage<Object> o, Runnable a, java.util.concurrent.Executor e) {
            return r.runAfterEitherAsync(o, a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> thenCompose(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenCompose((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> thenComposeAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.thenComposeAsync((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> thenComposeAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.thenComposeAsync((java.util.function.Function<Object, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a, e);
        }

        public static java.util.concurrent.CompletionStage<Object> whenComplete(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiConsumer<Object, Object> a) {
            return r.whenComplete(a);
        }

        public static java.util.concurrent.CompletionStage<Object> whenCompleteAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiConsumer<Object, Object> a) {
            return r.whenCompleteAsync(a);
        }

        public static java.util.concurrent.CompletionStage<Object> whenCompleteAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiConsumer<Object, Object> a, java.util.concurrent.Executor e) {
            return r.whenCompleteAsync(a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> handle(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.handle((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> handleAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiFunction<Object, Object, Object> a) {
            return r.handleAsync((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> handleAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.BiFunction<Object, Object, Object> a, java.util.concurrent.Executor e) {
            return r.handleAsync((java.util.function.BiFunction<Object, Throwable, Object>) (java.util.function.BiFunction<?, ?, ?>) a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionally(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionally((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionallyAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyAsync((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionallyAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.exceptionallyAsync((java.util.function.Function<Throwable, Object>) (java.util.function.Function<?, ?>) a, e);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionallyCompose(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyCompose((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionallyComposeAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a) {
            return r.exceptionallyComposeAsync((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a);
        }

        @SuppressWarnings("unchecked")
        public static java.util.concurrent.CompletionStage<Object> exceptionallyComposeAsync(
                java.util.concurrent.CompletionStage<Object> r, java.util.function.Function<Object, Object> a, java.util.concurrent.Executor e) {
            return r.exceptionallyComposeAsync((java.util.function.Function<Throwable, java.util.concurrent.CompletionStage<Object>>) (java.util.function.Function<?, ?>) a, e);
        }

        public static void execute(java.util.concurrent.Executor x, Runnable r) {
            x.execute(r);
        }

        public static java.util.concurrent.CompletableFuture<Object> supplyAsync(
                java.util.function.Supplier<Object> s) {
            return java.util.concurrent.CompletableFuture.supplyAsync(s);
        }

        public static java.util.concurrent.CompletableFuture<Object> supplyAsync(
                java.util.function.Supplier<Object> s, java.util.concurrent.Executor e) {
            return java.util.concurrent.CompletableFuture.supplyAsync(s, e);
        }

        public static java.util.concurrent.CompletableFuture<Void> allOf(
                java.util.concurrent.CompletableFuture<?>[] children) {
            return java.util.concurrent.CompletableFuture.allOf(children);
        }

        public static java.util.List<java.util.concurrent.Future<Object>> invokeAll(
                java.util.concurrent.ExecutorService receiver,
                java.util.Collection<? extends java.util.concurrent.Callable<Object>> tasks)
                throws InterruptedException {
            return receiver.invokeAll(tasks);
        }

        public static java.util.List<java.util.concurrent.Future<Object>> invokeAll(
                java.util.concurrent.ExecutorService receiver,
                java.util.Collection<? extends java.util.concurrent.Callable<Object>> tasks,
                long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
            return receiver.invokeAll(tasks, timeout, unit);
        }

        public static Object invokeAny(java.util.concurrent.ExecutorService receiver,
                                       java.util.Collection<? extends java.util.concurrent.Callable<Object>> tasks)
                throws InterruptedException, java.util.concurrent.ExecutionException {
            return receiver.invokeAny(tasks);
        }

        public static Object invokeAny(java.util.concurrent.ExecutorService receiver,
                                       java.util.Collection<? extends java.util.concurrent.Callable<Object>> tasks,
                                       long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException, java.util.concurrent.ExecutionException,
                java.util.concurrent.TimeoutException {
            return receiver.invokeAny(tasks, timeout, unit);
        }

        public static java.util.List<Runnable> shutdownNow(java.util.concurrent.ExecutorService receiver) {
            return receiver.shutdownNow();
        }

        public static boolean awaitTermination(java.util.concurrent.ExecutorService receiver,
                                               long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return receiver.awaitTermination(timeout, unit);
        }

        public static void closeExecutor(java.util.concurrent.ExecutorService receiver) {
            receiver.close();
        }

        public static boolean removeFromPool(java.util.concurrent.ThreadPoolExecutor pool, Runnable task) {
            return pool.remove(task);
        }

        public static java.util.concurrent.BlockingQueue<Runnable> poolQueue(
                java.util.concurrent.ThreadPoolExecutor pool) {
            return pool.getQueue();
        }

        public static java.util.stream.Stream<Object> streamMapMulti(java.util.stream.Stream<Object> receiver,
                java.util.function.BiConsumer<Object, java.util.function.Consumer<Object>> mapper) {
            return receiver.mapMulti(mapper);
        }

        public static java.util.stream.IntStream streamMapMultiToInt(java.util.stream.Stream<Object> receiver,
                java.util.function.BiConsumer<Object, java.util.function.IntConsumer> mapper) {
            return receiver.mapMultiToInt(mapper);
        }

        public static java.util.stream.LongStream streamMapMultiToLong(java.util.stream.Stream<Object> receiver,
                java.util.function.BiConsumer<Object, java.util.function.LongConsumer> mapper) {
            return receiver.mapMultiToLong(mapper);
        }

        public static java.util.stream.DoubleStream streamMapMultiToDouble(java.util.stream.Stream<Object> receiver,
                java.util.function.BiConsumer<Object, java.util.function.DoubleConsumer> mapper) {
            return receiver.mapMultiToDouble(mapper);
        }

        public static java.util.stream.IntStream intStreamMapMulti(java.util.stream.IntStream receiver,
                java.util.stream.IntStream.IntMapMultiConsumer mapper) {
            return receiver.mapMulti(mapper);
        }

        public static java.util.stream.LongStream longStreamMapMulti(java.util.stream.LongStream receiver,
                java.util.stream.LongStream.LongMapMultiConsumer mapper) {
            return receiver.mapMulti(mapper);
        }

        public static java.util.stream.DoubleStream doubleStreamMapMulti(java.util.stream.DoubleStream receiver,
                java.util.stream.DoubleStream.DoubleMapMultiConsumer mapper) {
            return receiver.mapMulti(mapper);
        }

        public static java.util.stream.BaseStream<?, ?> intStreamUnordered(java.util.stream.IntStream receiver) {
            return receiver.unordered();
        }

        public static java.util.stream.BaseStream<?, ?> longStreamUnordered(java.util.stream.LongStream receiver) {
            return receiver.unordered();
        }

        public static java.util.stream.BaseStream<?, ?> doubleStreamUnordered(java.util.stream.DoubleStream receiver) {
            return receiver.unordered();
        }

        public static java.util.stream.BaseStream<?, ?> streamOnClose(java.util.stream.Stream<Object> receiver,
                Runnable closeHandler) {
            return receiver.onClose(closeHandler);
        }

        public static java.util.stream.BaseStream<?, ?> intStreamOnClose(java.util.stream.IntStream receiver,
                Runnable closeHandler) {
            return receiver.onClose(closeHandler);
        }

        public static java.util.stream.BaseStream<?, ?> longStreamOnClose(java.util.stream.LongStream receiver,
                Runnable closeHandler) {
            return receiver.onClose(closeHandler);
        }

        public static java.util.stream.BaseStream<?, ?> doubleStreamOnClose(java.util.stream.DoubleStream receiver,
                Runnable closeHandler) {
            return receiver.onClose(closeHandler);
        }

        public static java.util.Iterator<Object> streamIterator(java.util.stream.Stream<Object> receiver) {
            return receiver.iterator();
        }

        public static java.util.PrimitiveIterator.OfInt intStreamIterator(java.util.stream.IntStream receiver) {
            return receiver.iterator();
        }

        public static java.util.PrimitiveIterator.OfLong longStreamIterator(java.util.stream.LongStream receiver) {
            return receiver.iterator();
        }

        public static java.util.PrimitiveIterator.OfDouble doubleStreamIterator(java.util.stream.DoubleStream receiver) {
            return receiver.iterator();
        }

        public static void streamForEach(java.util.stream.Stream<Object> receiver,
                                         java.util.function.Consumer<Object> action) {
            receiver.forEach(action);
        }

        public static void intStreamForEach(java.util.stream.IntStream receiver,
                                            java.util.function.IntConsumer action) {
            receiver.forEach(action);
        }

        public static void longStreamForEach(java.util.stream.LongStream receiver,
                                             java.util.function.LongConsumer action) {
            receiver.forEach(action);
        }

        public static void doubleStreamForEach(java.util.stream.DoubleStream receiver,
                                               java.util.function.DoubleConsumer action) {
            receiver.forEach(action);
        }

        public static java.util.stream.Stream<Object> streamMap(java.util.stream.Stream<Object> receiver, java.util.function.Function<Object, Object> mapper) {
            return receiver.map(mapper);
        }

        public static java.util.stream.Stream<Object> streamFilter(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.filter(predicate);
        }

        public static java.util.stream.Stream<Object> streamPeek(java.util.stream.Stream<Object> receiver, java.util.function.Consumer<Object> action) {
            return receiver.peek(action);
        }

        public static void streamForEachOrdered(java.util.stream.Stream<Object> receiver, java.util.function.Consumer<Object> action) {
            receiver.forEachOrdered(action);
        }

        public static Object streamCollect(java.util.stream.Stream<Object> receiver,
                                           java.util.stream.Collector<? super Object, ?, ?> collector) {
            return receiver.collect(collector);
        }

        public static Object streamCollect(java.util.stream.Stream<Object> receiver, java.util.function.Supplier<Object> supplier,
                                           java.util.function.BiConsumer<Object, Object> accumulator,
                                           java.util.function.BiConsumer<Object, Object> combiner) {
            return receiver.collect(supplier, accumulator, combiner);
        }

        public static java.util.Optional<Object> streamReduce(java.util.stream.Stream<Object> receiver,
                                                              java.util.function.BinaryOperator<Object> accumulator) {
            return receiver.reduce(accumulator);
        }

        public static Object streamReduce(java.util.stream.Stream<Object> receiver, Object identity,
                                          java.util.function.BinaryOperator<Object> accumulator) {
            return receiver.reduce(identity, accumulator);
        }

        public static Object streamReduce(java.util.stream.Stream<Object> receiver, Object identity,
                                          java.util.function.BiFunction<Object, Object, Object> accumulator,
                                          java.util.function.BinaryOperator<Object> combiner) {
            return receiver.reduce(identity, accumulator, combiner);
        }

        public static java.util.Optional<Object> streamMin(java.util.stream.Stream<Object> receiver,
                                                           java.util.Comparator<Object> comparator) {
            return receiver.min(comparator);
        }

        public static java.util.Optional<Object> streamMax(java.util.stream.Stream<Object> receiver,
                                                           java.util.Comparator<Object> comparator) {
            return receiver.max(comparator);
        }

        public static boolean streamAnyMatch(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.anyMatch(predicate);
        }

        public static boolean streamAllMatch(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.allMatch(predicate);
        }

        public static boolean streamNoneMatch(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.noneMatch(predicate);
        }

        public static java.util.List<Object> streamToList(java.util.stream.Stream<Object> receiver) {
            return receiver.toList();
        }

        public static Object[] streamToArray(java.util.stream.Stream<Object> receiver) {
            return receiver.toArray();
        }

        public static Object[] streamToArray(java.util.stream.Stream<Object> receiver, java.util.function.IntFunction<Object[]> generator) {
            return receiver.toArray(generator);
        }

        public static long streamCount(java.util.stream.Stream<Object> receiver) {
            return receiver.count();
        }

        public static java.util.Optional<Object> streamFindFirst(java.util.stream.Stream<Object> receiver) {
            return receiver.findFirst();
        }

        public static java.util.Optional<Object> streamFindAny(java.util.stream.Stream<Object> receiver) {
            return receiver.findAny();
        }

        public static java.util.stream.Stream<Object> streamSorted(java.util.stream.Stream<Object> receiver) {
            return receiver.sorted();
        }

        public static java.util.stream.Stream<Object> streamDistinct(java.util.stream.Stream<Object> receiver) {
            return receiver.distinct();
        }

        public static java.util.stream.Stream<Object> streamLimit(java.util.stream.Stream<Object> receiver, long maxSize) {
            return receiver.limit(maxSize);
        }

        public static java.util.stream.Stream<Object> streamSkip(java.util.stream.Stream<Object> receiver, long n) {
            return receiver.skip(n);
        }

        public static java.util.stream.BaseStream<?, ?> streamParallel(java.util.stream.Stream<Object> receiver) {
            return receiver.parallel();
        }

        public static java.util.stream.BaseStream<?, ?> streamSequential(java.util.stream.Stream<Object> receiver) {
            return receiver.sequential();
        }

        public static java.util.stream.BaseStream<?, ?> streamUnordered(java.util.stream.Stream<Object> receiver) {
            return receiver.unordered();
        }

        public static java.util.stream.Stream<Object> streamSorted(java.util.stream.Stream<Object> receiver, java.util.Comparator<Object> comparator) {
            return receiver.sorted(comparator);
        }

        public static java.util.stream.Stream<Object> streamFlatMap(java.util.stream.Stream<Object> receiver, java.util.function.Function<Object, java.util.stream.Stream<Object>> mapper) {
            return receiver.flatMap(mapper);
        }

        public static java.util.stream.Stream<Object> streamTakeWhile(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.takeWhile(predicate);
        }

        public static java.util.stream.Stream<Object> streamDropWhile(java.util.stream.Stream<Object> receiver, java.util.function.Predicate<Object> predicate) {
            return receiver.dropWhile(predicate);
        }

        public static java.util.stream.IntStream intStreamSorted(java.util.stream.IntStream receiver) {
            return receiver.sorted();
        }

        public static java.util.stream.IntStream intStreamDistinct(java.util.stream.IntStream receiver) {
            return receiver.distinct();
        }

        public static java.util.stream.IntStream intStreamLimit(java.util.stream.IntStream receiver, long maxSize) {
            return receiver.limit(maxSize);
        }

        public static java.util.stream.IntStream intStreamSkip(java.util.stream.IntStream receiver, long n) {
            return receiver.skip(n);
        }

        public static java.util.stream.IntStream intStreamParallel(java.util.stream.IntStream receiver) {
            return receiver.parallel();
        }

        public static java.util.stream.IntStream intStreamSequential(java.util.stream.IntStream receiver) {
            return receiver.sequential();
        }

        public static java.util.stream.IntStream intStreamFlatMap(java.util.stream.IntStream receiver, java.util.function.IntFunction<java.util.stream.IntStream> mapper) {
            return receiver.flatMap(mapper);
        }

        public static java.util.stream.IntStream intStreamTakeWhile(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.takeWhile(predicate);
        }

        public static java.util.stream.IntStream intStreamDropWhile(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.dropWhile(predicate);
        }

        public static java.util.stream.LongStream intStreamMapToLong(java.util.stream.IntStream receiver, java.util.function.IntToLongFunction mapper) {
            return receiver.mapToLong(mapper);
        }

        public static java.util.stream.DoubleStream intStreamMapToDouble(java.util.stream.IntStream receiver, java.util.function.IntToDoubleFunction mapper) {
            return receiver.mapToDouble(mapper);
        }

        public static java.util.stream.LongStream intStreamAsLongStream(java.util.stream.IntStream receiver) {
            return receiver.asLongStream();
        }

        public static java.util.stream.DoubleStream intStreamAsDoubleStream(java.util.stream.IntStream receiver) {
            return receiver.asDoubleStream();
        }

        public static java.util.stream.LongStream longStreamSorted(java.util.stream.LongStream receiver) {
            return receiver.sorted();
        }

        public static java.util.stream.LongStream longStreamDistinct(java.util.stream.LongStream receiver) {
            return receiver.distinct();
        }

        public static java.util.stream.LongStream longStreamLimit(java.util.stream.LongStream receiver, long maxSize) {
            return receiver.limit(maxSize);
        }

        public static java.util.stream.LongStream longStreamSkip(java.util.stream.LongStream receiver, long n) {
            return receiver.skip(n);
        }

        public static java.util.stream.LongStream longStreamParallel(java.util.stream.LongStream receiver) {
            return receiver.parallel();
        }

        public static java.util.stream.LongStream longStreamSequential(java.util.stream.LongStream receiver) {
            return receiver.sequential();
        }

        public static java.util.stream.LongStream longStreamFlatMap(java.util.stream.LongStream receiver, java.util.function.LongFunction<java.util.stream.LongStream> mapper) {
            return receiver.flatMap(mapper);
        }

        public static java.util.stream.LongStream longStreamTakeWhile(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.takeWhile(predicate);
        }

        public static java.util.stream.LongStream longStreamDropWhile(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.dropWhile(predicate);
        }

        public static java.util.stream.IntStream longStreamMapToInt(java.util.stream.LongStream receiver, java.util.function.LongToIntFunction mapper) {
            return receiver.mapToInt(mapper);
        }

        public static java.util.stream.DoubleStream longStreamMapToDouble(java.util.stream.LongStream receiver, java.util.function.LongToDoubleFunction mapper) {
            return receiver.mapToDouble(mapper);
        }

        public static java.util.stream.DoubleStream longStreamAsDoubleStream(java.util.stream.LongStream receiver) {
            return receiver.asDoubleStream();
        }

        public static java.util.stream.DoubleStream doubleStreamSorted(java.util.stream.DoubleStream receiver) {
            return receiver.sorted();
        }

        public static java.util.stream.DoubleStream doubleStreamDistinct(java.util.stream.DoubleStream receiver) {
            return receiver.distinct();
        }

        public static java.util.stream.DoubleStream doubleStreamLimit(java.util.stream.DoubleStream receiver, long maxSize) {
            return receiver.limit(maxSize);
        }

        public static java.util.stream.DoubleStream doubleStreamSkip(java.util.stream.DoubleStream receiver, long n) {
            return receiver.skip(n);
        }

        public static java.util.stream.DoubleStream doubleStreamParallel(java.util.stream.DoubleStream receiver) {
            return receiver.parallel();
        }

        public static java.util.stream.DoubleStream doubleStreamSequential(java.util.stream.DoubleStream receiver) {
            return receiver.sequential();
        }

        public static java.util.stream.DoubleStream doubleStreamFlatMap(java.util.stream.DoubleStream receiver, java.util.function.DoubleFunction<java.util.stream.DoubleStream> mapper) {
            return receiver.flatMap(mapper);
        }

        public static java.util.stream.DoubleStream doubleStreamTakeWhile(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.takeWhile(predicate);
        }

        public static java.util.stream.DoubleStream doubleStreamDropWhile(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.dropWhile(predicate);
        }

        public static java.util.stream.IntStream doubleStreamMapToInt(java.util.stream.DoubleStream receiver, java.util.function.DoubleToIntFunction mapper) {
            return receiver.mapToInt(mapper);
        }

        public static java.util.stream.LongStream doubleStreamMapToLong(java.util.stream.DoubleStream receiver, java.util.function.DoubleToLongFunction mapper) {
            return receiver.mapToLong(mapper);
        }

        public static java.util.stream.IntStream streamMapToInt(java.util.stream.Stream<Object> receiver, java.util.function.ToIntFunction<Object> mapper) {
            return receiver.mapToInt(mapper);
        }

        public static java.util.stream.LongStream streamMapToLong(java.util.stream.Stream<Object> receiver, java.util.function.ToLongFunction<Object> mapper) {
            return receiver.mapToLong(mapper);
        }

        public static java.util.stream.DoubleStream streamMapToDouble(java.util.stream.Stream<Object> receiver, java.util.function.ToDoubleFunction<Object> mapper) {
            return receiver.mapToDouble(mapper);
        }

        public static java.util.stream.IntStream intStreamMap(java.util.stream.IntStream receiver, java.util.function.IntUnaryOperator mapper) {
            return receiver.map(mapper);
        }

        public static java.util.stream.IntStream intStreamFilter(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.filter(predicate);
        }

        public static java.util.stream.IntStream intStreamPeek(java.util.stream.IntStream receiver, java.util.function.IntConsumer action) {
            return receiver.peek(action);
        }

        public static java.util.stream.Stream<Object> intStreamMapToObj(java.util.stream.IntStream receiver, java.util.function.IntFunction<Object> mapper) {
            return receiver.mapToObj(mapper);
        }

        @SuppressWarnings("unchecked")
        public static java.util.stream.Stream<Object> intStreamBoxed(java.util.stream.IntStream receiver) {
            return (java.util.stream.Stream<Object>) (java.util.stream.Stream<?>) receiver.boxed();
        }

        public static void intStreamForEachOrdered(java.util.stream.IntStream receiver, java.util.function.IntConsumer action) {
            receiver.forEachOrdered(action);
        }

        public static int intStreamReduce(java.util.stream.IntStream receiver, int identity, java.util.function.IntBinaryOperator operator) {
            return receiver.reduce(identity, operator);
        }

        public static java.util.OptionalInt intStreamReduce(java.util.stream.IntStream receiver, java.util.function.IntBinaryOperator operator) {
            return receiver.reduce(operator);
        }

        public static Object intStreamCollect(java.util.stream.IntStream receiver, java.util.function.Supplier<Object> supplier,
                                       java.util.function.ObjIntConsumer<Object> accumulator, java.util.function.BiConsumer<Object, Object> combiner) {
            return receiver.collect(supplier, accumulator, combiner);
        }

        public static boolean intStreamAnyMatch(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.anyMatch(predicate);
        }

        public static boolean intStreamAllMatch(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.allMatch(predicate);
        }

        public static boolean intStreamNoneMatch(java.util.stream.IntStream receiver, java.util.function.IntPredicate predicate) {
            return receiver.noneMatch(predicate);
        }

        public static int intStreamSum(java.util.stream.IntStream receiver) {
            return receiver.sum();
        }

        public static long intStreamCount(java.util.stream.IntStream receiver) {
            return receiver.count();
        }

        public static java.util.OptionalDouble intStreamAverage(java.util.stream.IntStream receiver) {
            return receiver.average();
        }

        public static java.util.OptionalInt intStreamMin(java.util.stream.IntStream receiver) {
            return receiver.min();
        }

        public static java.util.OptionalInt intStreamMax(java.util.stream.IntStream receiver) {
            return receiver.max();
        }

        public static java.util.IntSummaryStatistics intStreamSummaryStatistics(java.util.stream.IntStream receiver) {
            return receiver.summaryStatistics();
        }

        public static int[] intStreamToArray(java.util.stream.IntStream receiver) {
            return receiver.toArray();
        }

        public static java.util.OptionalInt intStreamFindFirst(java.util.stream.IntStream receiver) {
            return receiver.findFirst();
        }

        public static java.util.OptionalInt intStreamFindAny(java.util.stream.IntStream receiver) {
            return receiver.findAny();
        }

        public static java.util.stream.LongStream longStreamMap(java.util.stream.LongStream receiver, java.util.function.LongUnaryOperator mapper) {
            return receiver.map(mapper);
        }

        public static java.util.stream.LongStream longStreamFilter(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.filter(predicate);
        }

        public static java.util.stream.LongStream longStreamPeek(java.util.stream.LongStream receiver, java.util.function.LongConsumer action) {
            return receiver.peek(action);
        }

        public static java.util.stream.Stream<Object> longStreamMapToObj(java.util.stream.LongStream receiver, java.util.function.LongFunction<Object> mapper) {
            return receiver.mapToObj(mapper);
        }

        @SuppressWarnings("unchecked")
        public static java.util.stream.Stream<Object> longStreamBoxed(java.util.stream.LongStream receiver) {
            return (java.util.stream.Stream<Object>) (java.util.stream.Stream<?>) receiver.boxed();
        }

        public static void longStreamForEachOrdered(java.util.stream.LongStream receiver, java.util.function.LongConsumer action) {
            receiver.forEachOrdered(action);
        }

        public static long longStreamReduce(java.util.stream.LongStream receiver, long identity, java.util.function.LongBinaryOperator operator) {
            return receiver.reduce(identity, operator);
        }

        public static java.util.OptionalLong longStreamReduce(java.util.stream.LongStream receiver, java.util.function.LongBinaryOperator operator) {
            return receiver.reduce(operator);
        }

        public static Object longStreamCollect(java.util.stream.LongStream receiver, java.util.function.Supplier<Object> supplier,
                                       java.util.function.ObjLongConsumer<Object> accumulator, java.util.function.BiConsumer<Object, Object> combiner) {
            return receiver.collect(supplier, accumulator, combiner);
        }

        public static boolean longStreamAnyMatch(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.anyMatch(predicate);
        }

        public static boolean longStreamAllMatch(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.allMatch(predicate);
        }

        public static boolean longStreamNoneMatch(java.util.stream.LongStream receiver, java.util.function.LongPredicate predicate) {
            return receiver.noneMatch(predicate);
        }

        public static long longStreamSum(java.util.stream.LongStream receiver) {
            return receiver.sum();
        }

        public static long longStreamCount(java.util.stream.LongStream receiver) {
            return receiver.count();
        }

        public static java.util.OptionalDouble longStreamAverage(java.util.stream.LongStream receiver) {
            return receiver.average();
        }

        public static java.util.OptionalLong longStreamMin(java.util.stream.LongStream receiver) {
            return receiver.min();
        }

        public static java.util.OptionalLong longStreamMax(java.util.stream.LongStream receiver) {
            return receiver.max();
        }

        public static java.util.LongSummaryStatistics longStreamSummaryStatistics(java.util.stream.LongStream receiver) {
            return receiver.summaryStatistics();
        }

        public static long[] longStreamToArray(java.util.stream.LongStream receiver) {
            return receiver.toArray();
        }

        public static java.util.OptionalLong longStreamFindFirst(java.util.stream.LongStream receiver) {
            return receiver.findFirst();
        }

        public static java.util.OptionalLong longStreamFindAny(java.util.stream.LongStream receiver) {
            return receiver.findAny();
        }

        public static java.util.stream.DoubleStream doubleStreamMap(java.util.stream.DoubleStream receiver, java.util.function.DoubleUnaryOperator mapper) {
            return receiver.map(mapper);
        }

        public static java.util.stream.DoubleStream doubleStreamFilter(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.filter(predicate);
        }

        public static java.util.stream.DoubleStream doubleStreamPeek(java.util.stream.DoubleStream receiver, java.util.function.DoubleConsumer action) {
            return receiver.peek(action);
        }

        public static java.util.stream.Stream<Object> doubleStreamMapToObj(java.util.stream.DoubleStream receiver, java.util.function.DoubleFunction<Object> mapper) {
            return receiver.mapToObj(mapper);
        }

        @SuppressWarnings("unchecked")
        public static java.util.stream.Stream<Object> doubleStreamBoxed(java.util.stream.DoubleStream receiver) {
            return (java.util.stream.Stream<Object>) (java.util.stream.Stream<?>) receiver.boxed();
        }

        public static void doubleStreamForEachOrdered(java.util.stream.DoubleStream receiver, java.util.function.DoubleConsumer action) {
            receiver.forEachOrdered(action);
        }

        public static double doubleStreamReduce(java.util.stream.DoubleStream receiver, double identity, java.util.function.DoubleBinaryOperator operator) {
            return receiver.reduce(identity, operator);
        }

        public static java.util.OptionalDouble doubleStreamReduce(java.util.stream.DoubleStream receiver, java.util.function.DoubleBinaryOperator operator) {
            return receiver.reduce(operator);
        }

        public static Object doubleStreamCollect(java.util.stream.DoubleStream receiver, java.util.function.Supplier<Object> supplier,
                                       java.util.function.ObjDoubleConsumer<Object> accumulator, java.util.function.BiConsumer<Object, Object> combiner) {
            return receiver.collect(supplier, accumulator, combiner);
        }

        public static boolean doubleStreamAnyMatch(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.anyMatch(predicate);
        }

        public static boolean doubleStreamAllMatch(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.allMatch(predicate);
        }

        public static boolean doubleStreamNoneMatch(java.util.stream.DoubleStream receiver, java.util.function.DoublePredicate predicate) {
            return receiver.noneMatch(predicate);
        }

        public static double doubleStreamSum(java.util.stream.DoubleStream receiver) {
            return receiver.sum();
        }

        public static long doubleStreamCount(java.util.stream.DoubleStream receiver) {
            return receiver.count();
        }

        public static java.util.OptionalDouble doubleStreamAverage(java.util.stream.DoubleStream receiver) {
            return receiver.average();
        }

        public static java.util.OptionalDouble doubleStreamMin(java.util.stream.DoubleStream receiver) {
            return receiver.min();
        }

        public static java.util.OptionalDouble doubleStreamMax(java.util.stream.DoubleStream receiver) {
            return receiver.max();
        }

        public static java.util.DoubleSummaryStatistics doubleStreamSummaryStatistics(java.util.stream.DoubleStream receiver) {
            return receiver.summaryStatistics();
        }

        public static double[] doubleStreamToArray(java.util.stream.DoubleStream receiver) {
            return receiver.toArray();
        }

        public static java.util.OptionalDouble doubleStreamFindFirst(java.util.stream.DoubleStream receiver) {
            return receiver.findFirst();
        }

        public static java.util.OptionalDouble doubleStreamFindAny(java.util.stream.DoubleStream receiver) {
            return receiver.findAny();
        }

        public static java.util.concurrent.CompletableFuture<Void> runAsync(Runnable r) {
            return java.util.concurrent.CompletableFuture.runAsync(r);
        }

        public static java.util.concurrent.CompletableFuture<Void> runAsync(Runnable r,
                                                                            java.util.concurrent.Executor e) {
            return java.util.concurrent.CompletableFuture.runAsync(r, e);
        }

        public static java.util.concurrent.Future<Object> submit(
                java.util.concurrent.ExecutorService x, java.util.concurrent.Callable<Object> c) {
            return x.submit(c);
        }

        public static java.util.concurrent.Future<?> submit(java.util.concurrent.ExecutorService x,
                                                            Runnable r) {
            return x.submit(r);
        }

        public static java.util.concurrent.Future<Object> submit(
                java.util.concurrent.ExecutorService x, Runnable r, Object result) {
            return x.submit(r, result);
        }

        public static void submitResultDiscarded(java.util.concurrent.Future<?> f) {
            // the discard rows this test never reaches but the table resolves
        }

        public static void asyncResultDiscarded(java.util.concurrent.CompletableFuture<?> f) {
            DISCARDED_ROOTS.add(f);
        }

        public static java.util.concurrent.ExecutorService newFixedThreadPool(int n) {
            return java.util.concurrent.Executors.newFixedThreadPool(n);
        }

        public static java.util.concurrent.ExecutorService newFixedThreadPool(int n,
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newFixedThreadPool(n, f);
        }

        public static java.util.concurrent.ExecutorService newCachedThreadPool() {
            return java.util.concurrent.Executors.newCachedThreadPool();
        }

        public static java.util.concurrent.ExecutorService newCachedThreadPool(
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newCachedThreadPool(f);
        }

        public static java.util.concurrent.ExecutorService newSingleThreadExecutor() {
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        }

        public static java.util.concurrent.ExecutorService newSingleThreadExecutor(
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newSingleThreadExecutor(f);
        }

        public static java.util.concurrent.ScheduledExecutorService newScheduledThreadPool(int n) {
            return java.util.concurrent.Executors.newScheduledThreadPool(n);
        }

        public static java.util.concurrent.ScheduledExecutorService newScheduledThreadPool(int n,
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newScheduledThreadPool(n, f);
        }

        public static java.util.concurrent.ScheduledExecutorService newSingleThreadScheduledExecutor() {
            return java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        }

        public static java.util.concurrent.ScheduledExecutorService newSingleThreadScheduledExecutor(
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(f);
        }

        public static java.util.concurrent.ExecutorService newWorkStealingPool() {
            return java.util.concurrent.Executors.newWorkStealingPool();
        }

        public static java.util.concurrent.ExecutorService newWorkStealingPool(int n) {
            return java.util.concurrent.Executors.newWorkStealingPool(n);
        }

        public static java.util.concurrent.ExecutorService newVirtualThreadPerTaskExecutor() {
            return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        }

        public static java.util.concurrent.ExecutorService newThreadPerTaskExecutor(
                java.util.concurrent.ThreadFactory f) {
            return java.util.concurrent.Executors.newThreadPerTaskExecutor(f);
        }

        public static java.util.concurrent.ExecutorService unconfigurableExecutorService(
                java.util.concurrent.ExecutorService e) {
            return java.util.concurrent.Executors.unconfigurableExecutorService(e);
        }

        public static java.util.concurrent.ScheduledExecutorService unconfigurableScheduledExecutorService(
                java.util.concurrent.ScheduledExecutorService e) {
            return java.util.concurrent.Executors.unconfigurableScheduledExecutorService(e);
        }

        public static Object exchange(java.util.concurrent.Exchanger<Object> x, Object item)
                throws InterruptedException {
            return x.exchange(item);
        }

        public static Object exchange(java.util.concurrent.Exchanger<Object> x, Object item,
                                      long timeout, TimeUnit unit) throws Exception {
            return x.exchange(item, timeout, unit);
        }

        public static Process processBuilderStart(ProcessBuilder b) throws java.io.IOException {
            return b.start();
        }

        public static java.util.List<Process> processBuilderStartPipeline(
                java.util.List<ProcessBuilder> builders) throws java.io.IOException {
            return ProcessBuilder.startPipeline(builders);
        }

        public static int processWaitFor(Process p) throws InterruptedException {
            return p.waitFor();
        }

        public static boolean processWaitFor(Process p, long timeout, TimeUnit unit)
                throws InterruptedException {
            return p.waitFor(timeout, unit);
        }

        public static boolean processWaitFor(Process p, java.time.Duration d)
                throws InterruptedException {
            return p.waitFor(d.toMillis(), TimeUnit.MILLISECONDS);
        }

        public static java.io.InputStream processInputStream(Process p) {
            return p.getInputStream();
        }

        public static java.io.InputStream processErrorStream(Process p) {
            return p.getErrorStream();
        }

        public static java.io.BufferedReader processInputReader(Process p) {
            return p.inputReader();
        }

        public static java.io.BufferedReader processInputReader(Process p, java.nio.charset.Charset cs) {
            return p.inputReader(cs);
        }

        public static java.io.BufferedReader processErrorReader(Process p) {
            return p.errorReader();
        }

        public static java.io.BufferedReader processErrorReader(Process p, java.nio.charset.Charset cs) {
            return p.errorReader(cs);
        }

        public static void pipedWrite(java.io.PipedOutputStream out, int b) throws java.io.IOException {
            out.write(b);
        }

        public static void pipedWrite(java.io.PipedOutputStream out, byte[] b, int off, int len)
                throws java.io.IOException {
            out.write(b, off, len);
        }

        public static void pipedWrite(java.io.PipedOutputStream out, byte[] b) throws java.io.IOException {
            out.write(b);
        }

        public static int pipedRead(java.io.PipedInputStream in) throws java.io.IOException {
            return in.read();
        }

        public static int pipedRead(java.io.PipedInputStream in, byte[] b, int off, int len)
                throws java.io.IOException {
            return in.read(b, off, len);
        }

        public static int pipedRead(java.io.PipedInputStream in, byte[] b) throws java.io.IOException {
            return in.read(b);
        }

        public static Object threadLocalGet(ThreadLocal<?> local) {
            return local.get();
        }

        @SuppressWarnings("unchecked")
        public static void threadLocalSet(ThreadLocal<?> local, Object value) {
            ((ThreadLocal<Object>) local).set(value);
        }

        public static void threadLocalRemove(ThreadLocal<?> local) {
            local.remove();
        }
    }

    @Test
    @DisplayName("the concurrency table resolves its discard hook against the real hooks class")
    void tableAndHooksAgreeOnTheDiscardHook() {
        // targets() throws for an entry whose hook is missing or mis-shaped, and the discard
        // hook is resolved and checked the same way. Reaching the assertion is the check.
        assertEquals(1, CollectionAccessWeaver.concurrencySubstitutions(
                        AgentConcurrencyUtilHooks.class).size(),
                "one visitor carries the whole concurrency table, discard hook included");
    }

    @Test
    @DisplayName("a static call's popped result reaches its discard hook, and no other POP does")
    void aDroppedRootFutureReachesTheDiscardHook() throws Exception {
        DISCARDED_ROOTS.clear();
        Class<?> woven = new ByteBuddy()
                .redefine(com.example.agentfixture.AsyncRootShapesSample.class)
                .visit(CollectionAccessWeaver.concurrencySubstitutions(StubConcurrencyHooks.class)
                        .get(0))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();
        Object sample = woven.getConstructor().newInstance();

        woven.getMethod("discarded", Runnable.class).invoke(sample, (Runnable) () -> { });
        assertEquals(1, DISCARDED_ROOTS.size(),
                "the POP after a woven CompletableFuture.runAsync is the dropped root; the static "
                        + "path must arm the lookahead the virtual one does");

        Object kept = woven.getMethod("kept", Runnable.class).invoke(sample, (Runnable) () -> { });
        woven.getMethod("unrelatedStaticPopped").invoke(sample);
        assertEquals(1, DISCARDED_ROOTS.size(),
                "a returned root is kept, and a POP after a static call the table does not list "
                        + "must stay a POP. Got " + DISCARDED_ROOTS);
        assertTrue(kept instanceof java.util.concurrent.CompletableFuture,
                "the kept call still returns the future");
    }

    @Test
    @DisplayName("only the popped result reaches the discard hook; every other shape is untouched")
    void onlyThePoppedResultReachesTheDiscardHook() throws Exception {
        DISCARDED.clear();
        OFFERS.set(0);
        Class<?> woven = new ByteBuddy()
                .redefine(OfferShapesSample.class)
                .visit(CollectionAccessWeaver.concurrencySubstitutions(StubConcurrencyHooks.class)
                        .get(0))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
                .getLoaded();

        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        Object sample = woven.getConstructor(BlockingQueue.class).newInstance(queue);

        woven.getMethod("discarded", Object.class).invoke(sample, "fits");
        assertEquals(List.of(true), List.copyOf(DISCARDED),
                "a discarded offer that succeeded still reaches the hook, with the true it popped");

        woven.getMethod("discarded", Object.class).invoke(sample, "rejected");
        woven.getMethod("discardedTimed", Object.class).invoke(sample, "rejected too");
        assertEquals(List.of(true, false, false), List.copyOf(DISCARDED),
                "both offer forms hand the popped false to the hook when the queue is full");

        assertFalse((boolean) woven.getMethod("branched", Object.class).invoke(sample, "b"),
                "the branched shape still performs the offer and still sees the rejection");
        assertFalse((boolean) woven.getMethod("returned", Object.class).invoke(sample, "r"),
                "so does the returned shape");
        woven.getMethod("storedInALocal", Object.class).invoke(sample, "s");
        assertEquals(3, DISCARDED.size(),
                "IFNE, IRETURN and ISTORE are the caller reading the result: none of them is a "
                        + "discard, and none may reach the hook. Got " + DISCARDED);
        assertEquals(6, OFFERS.get(), "all six offers went through the ordinary hook");

        woven.getMethod("unrelatedBooleanPopped", Object.class).invoke(sample, "list");
        woven.getMethod("unrelatedReferencePopped", Object.class).invoke(sample, "map");
        assertEquals(3, DISCARDED.size(),
                "a POP after a call that is not an offer must stay a POP. The reference case "
                        + "would not have loaded at all had the flag been stale; the boolean "
                        + "case would have reached the hook. Got " + DISCARDED);
        assertEquals(1, ((List<?>) woven.getField("list").get(sample)).size(),
                "the unrelated add still happened");
        assertEquals(1, ((java.util.Map<?, ?>) woven.getField("map").get(sample)).size(),
                "the unrelated put still happened");
        assertTrue(queue.contains("fits"), "the one accepted element is really in the queue");
    }
}
