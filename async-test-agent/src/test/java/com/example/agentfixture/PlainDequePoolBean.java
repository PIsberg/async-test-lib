package com.example.agentfixture;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * An object pool over a plain {@code ArrayDeque}, in the shapes #751 has to tell apart: the deque
 * guarded by {@code synchronized} methods, by {@code synchronized} blocks on the pool, by a mix of
 * the two, by two different locks, and not guarded at all, and a class-wide pool guarded by
 * {@code static synchronized} methods (#796).
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
}
