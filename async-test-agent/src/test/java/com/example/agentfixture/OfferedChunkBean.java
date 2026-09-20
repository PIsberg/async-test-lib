package com.example.agentfixture;

import com.example.agentfixture.jctools.queues.LockedMessagePassingQueue;
import com.example.agentfixture.jctools.queues.MessagePassingQueue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
    private static final AtomicReferenceFieldUpdater<OfferedChunkBean, Chunk> SIBLING_UPDATER_SLOT =
            AtomicReferenceFieldUpdater.newUpdater(OfferedChunkBean.class, Chunk.class,
                    "siblingUpdaterSlot");
    private static final VarHandle SIBLING_HANDLE_SLOT;

    static {
        try {
            HANDLE_SLOT = MethodHandles.lookup().findVarHandle(OfferedChunkBean.class, "handleSlot",
                    Chunk.class);
            SIBLING_HANDLE_SLOT = MethodHandles.lookup().findVarHandle(OfferedChunkBean.class,
                    "siblingHandleSlot", Chunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicReference<Chunk> slot = new AtomicReference<>();
    private final AtomicReferenceArray<Chunk> slots = new AtomicReferenceArray<>(2);
    private final MessagePassingQueue<Chunk> queue = new LockedMessagePassingQueue<>();
    private final BlockingQueue<Chunk> blocking = new LinkedBlockingQueue<>();
    private final Deque<Chunk> deque = new ConcurrentLinkedDeque<>();
    private final BlockingDeque<Chunk> blockingDeque = new LinkedBlockingDeque<>();
    private final Queue<Chunk> plain = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    @SuppressWarnings("unused") // written through HANDLE_SLOT
    private volatile Chunk handleSlot;
    @SuppressWarnings("unused") // written through SIBLING_HANDLE_SLOT
    private volatile Chunk siblingHandleSlot;
    @SuppressWarnings("unused") // written through SIBLING_UPDATER_SLOT
    private volatile Chunk siblingUpdaterSlot;
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

    /** {@return the deque the end-specific shapes use (#692)} */
    public Deque<Chunk> deque() {
        return deque;
    }

    /** {@return the blocking deque the blocking and timed end-specific shapes use (#692)} */
    public BlockingDeque<Chunk> blockingDeque() {
        return blockingDeque;
    }

    /** {@return the plain queue the bulk-offer and removal shapes use (#692)} */
    public Queue<Chunk> plain() {
        return plain;
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

    public boolean offerFirstToDeque(Chunk chunk) {
        return deque.offerFirst(chunk);
    }

    public boolean offerLastToDeque(Chunk chunk) {
        return deque.offerLast(chunk);
    }

    public void addFirstToDeque(Chunk chunk) {
        deque.addFirst(chunk);
    }

    public void addLastToDeque(Chunk chunk) {
        deque.addLast(chunk);
    }

    public void pushToDeque(Chunk chunk) {
        deque.push(chunk);
    }

    public boolean addAllToPlain(Chunk chunk) {
        return plain.addAll(List.of(chunk));
    }

    public boolean offerToPlain(Chunk chunk) {
        return plain.offer(chunk);
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

    public Chunk pollFirstFromDeque() {
        return deque.pollFirst();
    }

    public Chunk pollLastFromDeque() {
        return deque.pollLast();
    }

    public Chunk removeFirstFromDeque() {
        return deque.removeFirst();
    }

    public Chunk removeLastFromDeque() {
        return deque.removeLast();
    }

    public Chunk popFromDeque() {
        return deque.pop();
    }

    public Chunk removeHeadOfPlain() {
        return plain.remove();
    }

    /** Removes the head by naming it, the way a cancellation path does. */
    public Chunk removeFromPlainByName() {
        Chunk head = plain.peek();
        return plain.remove(head) ? head : null;
    }

    /** Empties the plain queue through a predicate, which names no element. */
    public boolean removeEveryChunkFromPlain() {
        return plain.removeIf(chunk -> true);
    }

    // ---- BlockingDeque: the blocking and timed forms at each end (#692) ------------------------

    public boolean putFirstToBlockingDeque(Chunk chunk) throws InterruptedException {
        blockingDeque.putFirst(chunk);
        return true;
    }

    public boolean putLastToBlockingDeque(Chunk chunk) throws InterruptedException {
        blockingDeque.putLast(chunk);
        return true;
    }

    public boolean timedOfferFirstToBlockingDeque(Chunk chunk) throws InterruptedException {
        return blockingDeque.offerFirst(chunk, 1, TimeUnit.SECONDS);
    }

    public boolean timedOfferLastToBlockingDeque(Chunk chunk) throws InterruptedException {
        return blockingDeque.offerLast(chunk, 1, TimeUnit.SECONDS);
    }

    public Chunk takeFirstFromBlockingDeque() throws InterruptedException {
        return blockingDeque.takeFirst();
    }

    public Chunk takeLastFromBlockingDeque() throws InterruptedException {
        return blockingDeque.takeLast();
    }

    public Chunk timedPollFirstFromBlockingDeque() throws InterruptedException {
        return blockingDeque.pollFirst(1, TimeUnit.SECONDS);
    }

    public Chunk timedPollLastFromBlockingDeque() throws InterruptedException {
        return blockingDeque.pollLast(1, TimeUnit.SECONDS);
    }

    // ---- The sibling slots: a second field of this object, a second element of the array (#692)

    public void offerThroughSiblingUpdater(Chunk chunk) {
        SIBLING_UPDATER_SLOT.set(this, chunk);
    }

    public Chunk takeThroughSiblingUpdater() {
        return SIBLING_UPDATER_SLOT.getAndSet(this, null);
    }

    public void offerThroughSiblingHandle(Chunk chunk) {
        SIBLING_HANDLE_SLOT.setRelease(this, chunk);
    }

    public Chunk takeThroughSiblingHandle() {
        return (Chunk) SIBLING_HANDLE_SLOT.getAndSet(this, (Chunk) null);
    }

    public void offerToSiblingArrayElement(Chunk chunk) {
        slots.set(1, chunk);
    }

    public Chunk takeFromSiblingArrayElement() {
        return slots.getAndSet(1, null);
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
