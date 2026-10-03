package com.example.agentfixture;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixture for the hand-offs the happens-before model learned in #741: a {@code CompletableFuture}
 * completion, an executor submission and its get, an {@code Exchanger} swap, and an
 * {@code AtomicReference} set and get.
 *
 * <p>Every call site is in this class, so the weaver substitutes it. {@link Parcel#contents} is the
 * plain field a detector judges; each method either hands a parcel over through the call it is
 * named for, or does the same thing with the order broken.
 */
public class HandOffPublicationBean {

    /** What is handed over. */
    public static final class Parcel {

        int contents;
    }

    /** The unpublished twin: a parcel reached through a plain field. */
    private Parcel lastWritten;

    // ---- CompletableFuture -------------------------------------------------------------------

    /** Fills a fresh parcel and completes {@code future} with it. */
    public void complete(CompletableFuture<Parcel> future, int contents) {
        Parcel parcel = new Parcel();
        parcel.contents = contents;
        lastWritten = parcel;
        future.complete(parcel);
    }

    /** Joins {@code future} and updates the parcel it completed with. */
    public int updateJoined(CompletableFuture<Parcel> future) {
        Parcel parcel = future.join();
        return ++parcel.contents;
    }

    /** The same update with the future skipped, on the parcel the writer last filled. */
    public int updateUnjoined() {
        Parcel parcel = lastWritten;
        return ++parcel.contents;
    }

    // ---- Dependent stages (#741) -------------------------------------------------------------

    /**
     * Registers a function on {@code source} that updates {@code parcel}, and fills the parcel
     * before the registration, or after it with {@code fillAfter}. The function runs on whichever
     * thread completes {@code source}.
     */
    public CompletableFuture<Object> fillAndChain(CompletableFuture<Object> source, Parcel parcel,
                                                  boolean fillAfter) {
        if (!fillAfter) {
            parcel.contents = 1;
        }
        CompletableFuture<Object> stage = source.thenApply(ignored -> ++parcel.contents);
        if (fillAfter) {
            parcel.contents = 1;
        }
        return stage;
    }

    /**
     * As {@link #fillAndChain}, through a call site typed against {@code CompletionStage}, the
     * type a library that returns a stage hands its callers.
     */
    public java.util.concurrent.CompletionStage<Object> fillAndChainStage(
            java.util.concurrent.CompletionStage<Object> source, Parcel parcel) {
        parcel.contents = 1;
        return source.thenApply(ignored -> ++parcel.contents);
    }

    /**
     * Fills a fresh parcel and executes a task that completes {@code future} with it (#741, #834).
     * The task touches no field of the parcel, so the parcel reaches the joiner through the
     * execute and the completion, or through nothing.
     */
    public void fillAndExecute(java.util.concurrent.Executor executor,
                               CompletableFuture<Parcel> future, int contents) {
        Parcel parcel = new Parcel();
        parcel.contents = contents;
        lastWritten = parcel;
        executor.execute(() -> future.complete(parcel));
    }

    /** Completes {@code source}, which runs the functions registered on it on this thread. */
    public void completeSource(CompletableFuture<Object> source) {
        source.complete(Boolean.TRUE);
    }

    // ---- AtomicReference ---------------------------------------------------------------------

    /** Fills a fresh parcel and publishes it with {@code set}. */
    public void publish(AtomicReference<Parcel> slot, int contents) {
        Parcel parcel = new Parcel();
        parcel.contents = contents;
        slot.set(parcel);
    }

    /** Publishes a fresh parcel with {@code set} and fills it afterwards. */
    public void publishThenFill(AtomicReference<Parcel> slot, int contents) {
        Parcel parcel = new Parcel();
        slot.set(parcel);
        parcel.contents = contents;
    }

    /** Gets the published parcel and updates it; {@code -1} when there was none. */
    public int updatePublished(AtomicReference<Parcel> slot) {
        Parcel parcel = slot.get();
        return parcel == null ? -1 : ++parcel.contents;
    }

    // ---- Exchanger ---------------------------------------------------------------------------

    /**
     * Fills a parcel, swaps it for the partner's and updates the one received; with
     * {@code fillAgain}, also writes its own parcel again after the swap.
     */
    public int swap(Exchanger<Parcel> exchanger, int contents, boolean fillAgain)
            throws InterruptedException, TimeoutException {
        Parcel mine = new Parcel();
        mine.contents = contents;
        Parcel theirs = exchanger.exchange(mine, 10, TimeUnit.SECONDS);
        if (fillAgain) {
            mine.contents = contents + 1;
        }
        return ++theirs.contents;
    }

    // ---- Executor submission and get --------------------------------------------------------

    /**
     * Fills a parcel and submits a task that completes {@code future} with it; with
     * {@code fillAfter} the parcel is filled after the submission instead. The task runs on a
     * pool thread and touches no field of the parcel, so the parcel reaches the joiner through
     * the pool thread's complete, which publishes what that thread received. A future
     * rather than a queue, because a take out of a queue is an ownership hand-off the validator
     * judges on its own, edge or no edge.
     */
    public Future<?> fillAndSubmit(ExecutorService executor, CompletableFuture<Parcel> future,
                                   int contents, boolean fillAfter) {
        Parcel parcel = new Parcel();
        if (!fillAfter) {
            parcel.contents = contents;
        }
        Future<?> handed = executor.submit(() -> future.complete(parcel));
        if (fillAfter) {
            parcel.contents = contents;
        }
        return handed;
    }

    /** Fills a fresh parcel and puts it on {@code queue}. */
    public void fillAndPut(BlockingQueue<Parcel> queue, int contents) throws InterruptedException {
        Parcel parcel = new Parcel();
        parcel.contents = contents;
        queue.put(parcel);
    }

    /**
     * Submits a task that takes a parcel off {@code queue} and returns it, gets the task's result
     * and updates it. With a {@code leak}, the task also hands the parcel to it, and the caller
     * updates what {@code leaked} returns before the get: the caller's code, unwoven, so that
     * detour orders nothing.
     */
    public int updateThroughATask(ExecutorService executor, BlockingQueue<Parcel> queue,
                                  java.util.function.Consumer<Parcel> leak,
                                  java.util.function.Supplier<Parcel> leaked)
            throws InterruptedException, ExecutionException {
        Future<Parcel> task = executor.submit(() -> {
            Parcel parcel = queue.take();
            if (leak != null) {
                leak.accept(parcel);
            }
            return parcel;
        });
        if (leaked != null) {
            int updated = ++leaked.get().contents;
            task.get();
            return updated;
        }
        return ++task.get().contents;
    }
}
