package com.example.agentfixture;

import com.example.agentfixture.jctools.queues.LockedMessagePassingQueue;
import com.example.agentfixture.jctools.queues.MessagePassingQueue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Hands a chunk from one thread to the next through every container shape #664 is about: a
 * reference slot ({@code AtomicReference}, an updater, an array slot, a {@code VarHandle}), a
 * JCTools-shaped queue, and a {@code BlockingQueue} emptied by {@code take} or {@code drainTo}.
 *
 * <p>Each step is its own method so a test can run it on the thread it chooses, in the order it
 * chooses. The container is reachable too, so a test can put an element back the way unwoven code
 * would, and compare the identities the weaver reports against it.
 */
public final class OfferedChunkBean {

    /** The state that changes hands. */
    public static final class Chunk {
        int allocated;
    }

    private static final VarHandle HANDLE_SLOT;
    private static final AtomicReferenceFieldUpdater<OfferedChunkBean, Chunk> UPDATER_SLOT =
            AtomicReferenceFieldUpdater.newUpdater(OfferedChunkBean.class, Chunk.class, "updaterSlot");

    static {
        try {
            HANDLE_SLOT = MethodHandles.lookup().findVarHandle(OfferedChunkBean.class, "handleSlot",
                    Chunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicReference<Chunk> slot = new AtomicReference<>();
    private final AtomicReferenceArray<Chunk> slots = new AtomicReferenceArray<>(1);
    private final MessagePassingQueue<Chunk> queue = new LockedMessagePassingQueue<>();
    private final BlockingQueue<Chunk> blocking = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    @SuppressWarnings("unused") // written through HANDLE_SLOT
    private volatile Chunk handleSlot;
    @SuppressWarnings("unused") // written through UPDATER_SLOT
    private volatile Chunk updaterSlot;

    /** {@return a chunk nothing has touched} */
    public static Chunk newChunk() {
        return new Chunk();
    }

    /** {@return the {@code AtomicReference} slot} */
    public AtomicReference<Chunk> slot() {
        return slot;
    }

    /** {@return the array the array-slot shape uses} */
    public AtomicReferenceArray<Chunk> slots() {
        return slots;
    }

    /** {@return the JCTools-shaped queue} */
    public MessagePassingQueue<Chunk> queue() {
        return queue;
    }

    /** {@return the blocking queue} */
    public BlockingQueue<Chunk> blocking() {
        return blocking;
    }

    // ---- Offers --------------------------------------------------------------------------------

    public void offerBySet(Chunk chunk) {
        slot.set(chunk);
    }

    public void offerByLazySet(Chunk chunk) {
        slot.lazySet(chunk);
    }

    public boolean offerByCompareAndSet(Chunk chunk) {
        return slot.compareAndSet(null, chunk);
    }

    public void offerThroughUpdater(Chunk chunk) {
        UPDATER_SLOT.set(this, chunk);
    }

    public boolean offerThroughUpdaterCompareAndSet(Chunk chunk) {
        return UPDATER_SLOT.compareAndSet(this, null, chunk);
    }

    public void offerToArray(Chunk chunk) {
        slots.set(0, chunk);
    }

    public void offerThroughHandle(Chunk chunk) {
        HANDLE_SLOT.setRelease(this, chunk);
    }

    public boolean offerThroughHandleCompareAndSet(Chunk chunk) {
        return HANDLE_SLOT.compareAndSet(this, (Chunk) null, chunk);
    }

    public boolean relaxedOfferToQueue(Chunk chunk) {
        return queue.relaxedOffer(chunk);
    }

    public boolean offerToQueue(Chunk chunk) {
        return queue.offer(chunk);
    }

    public boolean offerToBlocking(Chunk chunk) {
        return blocking.offer(chunk);
    }

    // ---- Takes ---------------------------------------------------------------------------------

    public Chunk takeFromSlot() {
        return slot.getAndSet(null);
    }

    public Chunk takeThroughUpdater() {
        return UPDATER_SLOT.getAndSet(this, null);
    }

    public Chunk takeFromArray() {
        return slots.getAndSet(0, null);
    }

    public Chunk takeThroughHandle() {
        return (Chunk) HANDLE_SLOT.getAndSet(this, (Chunk) null);
    }

    public Chunk relaxedPollQueue() {
        return queue.relaxedPoll();
    }

    public Chunk pollQueue() {
        return queue.poll();
    }

    public Chunk pollBlocking() {
        return blocking.poll();
    }

    public Chunk takeFromBlocking() throws InterruptedException {
        return blocking.take();
    }

    public List<Chunk> drainBlocking() {
        List<Chunk> drained = new ArrayList<>();
        blocking.drainTo(drained);
        return drained;
    }

    public List<Chunk> drainBlockingAtMost(int max) {
        List<Chunk> drained = new ArrayList<>();
        blocking.drainTo(drained, max);
        return drained;
    }

    // ---- Uses ----------------------------------------------------------------------------------

    /** Writes the chunk with no lock, the way its current owner does. */
    public static void write(Chunk chunk) {
        chunk.allocated = chunk.allocated + 1;
    }

    /** Writes the chunk under this bean's lock. */
    public void writeLocked(Chunk chunk) {
        synchronized (lock) {
            chunk.allocated = chunk.allocated + 1;
        }
    }
}
