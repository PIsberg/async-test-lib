package com.example.agentfixture;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * An object pool over a plain {@code ArrayDeque}, in the shapes #751 has to tell apart: the deque
 * guarded by {@code synchronized} methods, by {@code synchronized} blocks on the pool, by a mix of
 * the two, by two different locks, and not guarded at all, and a class-wide pool guarded by
 * {@code static synchronized} methods (#796), and a pool whose {@code synchronized} methods reach
 * the deque through a bucket object, a static helper or a lambda (#822).
 *
 * <p>The item is used with no lock between a borrow and the next give-back, which is correct in
 * the first three shapes: the pool's monitor serialises the deque, so each item leaves it to one
 * thread at a time.
 */
public final class PlainDequePoolBean {

    /** The pooled state, used unlocked by whoever borrowed it. */
    public static final class Item {
        int uses;
    }

    private final Queue<Item> free = new ArrayDeque<>();

    private final Object otherLock = new Object();

    /** {@return an item nothing has touched} */
    public static Item newItem() {
        return new Item();
    }

    /** Uses the item, with no lock held. */
    public static void use(Item item) {
        item.uses = item.uses + 1;
    }

    /** Returns an item through a {@code synchronized} method, whose monitor no instruction takes. */
    public synchronized void giveBack(Item item) {
        free.offer(item);
    }

    /** {@return an item, borrowed through a {@code synchronized} method} */
    public synchronized Item borrow() {
        return free.poll();
    }

    /** Returns an item under a {@code synchronized} block on the pool. */
    public void giveBackInBlock(Item item) {
        synchronized (this) {
            free.offer(item);
        }
    }

    /** {@return an item, borrowed under a {@code synchronized} block on the pool} */
    public Item borrowInBlock() {
        synchronized (this) {
            return free.poll();
        }
    }

    /** {@return an item borrowed under a lock the give-backs never take, the broken pool} */
    public Item borrowUnderAnotherLock() {
        synchronized (otherLock) {
            return free.poll();
        }
    }

    /** Returns an item with no lock held, the broken pool. */
    public void giveBackUnguarded(Item item) {
        free.offer(item);
    }

    /** {@return an item borrowed with no lock held, the broken pool} */
    public Item borrowUnguarded() {
        return free.poll();
    }

    /** Returns an item through a {@code synchronized} method that hands the offer to a helper. */
    public synchronized void giveBackThroughAHelper(Item item) {
        putBack(item);
    }

    /** {@return an item, borrowed through a {@code synchronized} method that polls in a helper} */
    public synchronized Item borrowThroughAHelper() {
        return takeOne();
    }

    /** The offer, in a method that is not itself {@code synchronized}: its callers are. */
    private void putBack(Item item) {
        free.offer(item);
    }

    /** The poll, in a method that is not itself {@code synchronized}: its callers are. */
    private Item takeOne() {
        return free.poll();
    }

    /** One pool for the whole class, guarded by {@code static synchronized} methods. */
    private static final Queue<Item> SHARED_FREE = new ArrayDeque<>();

    /** Returns an item through a {@code static synchronized} method, which holds the class. */
    public static synchronized void giveBackToTheSharedPool(Item item) {
        SHARED_FREE.offer(item);
    }

    /** {@return an item from the shared pool, through a {@code static synchronized} method} */
    public static synchronized Item borrowFromTheSharedPool() {
        return SHARED_FREE.poll();
    }

    /** {@return an item from the shared pool under a lock its give-backs never take, the broken pool} */
    public Item borrowFromTheSharedPoolUnderAnotherLock() {
        synchronized (otherLock) {
            return SHARED_FREE.poll();
        }
    }

    // ---- The pool's monitor held further up the call stack than the queue call (#822). Each
    //      queue call sits in code whose own monitor is not the pool's: another object's instance
    //      method, a static helper, a lambda body. Only the synchronized method that called it
    //      holds the lock.

    /** The deque behind a pool that delegates to it; its own methods take no lock. */
    static final class Bucket {

        private final Queue<Item> free = new ArrayDeque<>();

        void put(Item item) {
            free.offer(item);
        }

        Item take() {
            return free.poll();
        }
    }

    private final Bucket bucket = new Bucket();

    private static final BiConsumer<Queue<Item>, Item> OFFER = (queue, item) -> queue.offer(item);

    private static final Function<Queue<Item>, Item> POLL = queue -> queue.poll();

    /** Returns an item through a {@code synchronized} method that hands it to a bucket. */
    public synchronized void giveBackToABucket(Item item) {
        bucket.put(item);
    }

    /** {@return an item, borrowed through a {@code synchronized} method from a bucket} */
    public synchronized Item borrowFromABucket() {
        return bucket.take();
    }

    /** {@return an item borrowed from the bucket with no lock held, the broken pool} */
    public Item borrowFromABucketUnguarded() {
        return bucket.take();
    }

    /** Returns an item through a {@code synchronized} method that offers it in a static helper. */
    public synchronized void giveBackThroughAStaticHelper(Item item) {
        offerTo(free, item);
    }

    /** {@return an item, borrowed through a {@code synchronized} method polling in a static helper} */
    public synchronized Item borrowThroughAStaticHelper() {
        return pollFrom(free);
    }

    /** {@return an item polled in the static helper with no lock held, the broken pool} */
    public Item borrowThroughAStaticHelperUnguarded() {
        return pollFrom(free);
    }

    private static void offerTo(Queue<Item> queue, Item item) {
        queue.offer(item);
    }

    private static Item pollFrom(Queue<Item> queue) {
        return queue.poll();
    }

    /** Returns an item through a {@code synchronized} method that offers it in a lambda. */
    public synchronized void giveBackThroughALambda(Item item) {
        OFFER.accept(free, item);
    }

    /** {@return an item, borrowed through a {@code synchronized} method polling in a lambda} */
    public synchronized Item borrowThroughALambda() {
        return POLL.apply(free);
    }

    /** {@return an item polled in the lambda with no lock held, the broken pool} */
    public Item borrowThroughALambdaUnguarded() {
        return POLL.apply(free);
    }

    /** Takes the pool's monitor through a {@code synchronized} method and leaves by an exception. */
    public synchronized void failInsideTheMonitor() {
        throw new IllegalStateException("left the monitor by an exception");
    }

    /**
     * Returns an item with no lock held, right after a {@code synchronized} method on the pool
     * failed, the broken pool: no return released that method's monitor.
     */
    public void giveBackUnguardedAfterAFailure(Item item) {
        try {
            failInsideTheMonitor();
        } catch (IllegalStateException expected) {
            // The monitor is released; nothing woven said so.
        }
        free.offer(item);
    }

    /**
     * As {@link #giveBackUnguardedAfterAFailure}, with the failure inside a {@code synchronized}
     * block on the pool, so the block's own release comes after the method's lost one.
     */
    public void giveBackUnguardedAfterAFailureInsideABlock(Item item) {
        synchronized (this) {
            try {
                failInsideTheMonitor();
            } catch (IllegalStateException expected) {
                // Still inside the block, which holds the monitor.
            }
        }
        free.offer(item);
    }
}
