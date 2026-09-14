package com.example.agentfixture;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.example.agentfixture.jctools.queues.LockedMessagePassingQueue;
import com.example.agentfixture.jctools.queues.MessagePassingQueue;

/**
 * One mutable chunk that threads pass between them the way netty's adaptive allocator passes a
 * chunk between magazines (#555): out of a queue, or swapped out of an atomic slot, used with no
 * lock, then handed back.
 *
 * <p>Each hand-off shape has a twin that uses the same chunk the same way without taking it,
 * which is the plain race the detector must keep reporting.
 */
public final class HandOffChunkBean {

    /** The state that changes hands. */
    public static final class Chunk {
        int allocated;
    }

    private static final VarHandle NEXT_IN_LINE;

    static {
        try {
            NEXT_IN_LINE = MethodHandles.lookup().findVarHandle(HandOffChunkBean.class,
                    "nextInLine", Chunk.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Queue<Chunk> cache = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Chunk> slot = new AtomicReference<>();
    private final MessagePassingQueue<Chunk> pool = new LockedMessagePassingQueue<>();
    @SuppressWarnings("unused") // updated through NEXT_IN_LINE
    private volatile Chunk nextInLine;
    private final Chunk shared = new Chunk();

    public HandOffChunkBean() {
        cache.offer(new Chunk());
        pool.relaxedOffer(new Chunk());
        slot.set(new Chunk());
        NEXT_IN_LINE.setVolatile(this, new Chunk());
    }

    /** Polls the chunk, uses it, offers it back. */
    public void useThroughQueue() {
        Chunk chunk = cache.poll();
        if (chunk != null) {
            chunk.allocated = chunk.allocated + 1;
            cache.offer(chunk);
        }
    }

    /** Polls the chunk from a JCTools-shaped queue, uses it, offers it back. */
    public void useThroughMessagePassingQueue() {
        Chunk chunk = pool.relaxedPoll();
        if (chunk != null) {
            chunk.allocated = chunk.allocated + 1;
            pool.relaxedOffer(chunk);
        }
    }

    /** Swaps the chunk out of an {@code AtomicReference}, uses it, puts it back. */
    public void useThroughAtomicReference() {
        Chunk chunk = slot.getAndSet(null);
        if (chunk != null) {
            chunk.allocated = chunk.allocated + 1;
            slot.compareAndSet(null, chunk);
        }
    }

    /** Swaps the chunk out of a {@code VarHandle} slot, uses it, puts it back. */
    public void useThroughVarHandle() {
        Chunk chunk = (Chunk) NEXT_IN_LINE.getAndSet(this, (Chunk) null);
        if (chunk != null) {
            chunk.allocated = chunk.allocated + 1;
            NEXT_IN_LINE.compareAndSet(this, (Chunk) null, chunk);
        }
    }

    /** The twin: every thread uses the one chunk and nothing takes it. */
    public void useWithoutTaking() {
        shared.allocated = shared.allocated + 1;
    }
}
