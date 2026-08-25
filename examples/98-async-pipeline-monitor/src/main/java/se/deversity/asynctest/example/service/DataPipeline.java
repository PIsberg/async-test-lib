package se.deversity.asynctest.example.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes messages through three stages: parse, enrich, persist.
 *
 * <p><strong>Bug:</strong> the hand-off to the persist stage is a bounded queue written with
 * {@code offer()}, whose {@code false} return nobody acts on. Parse and enrich are fast, persist
 * is slow, and when the queue between them fills the extra messages are dropped on the floor.
 * Not failed - dropped. Nothing throws, nothing is logged, and the counters upstream still say
 * the message was handled.
 *
 * <p>That is what "no back-pressure" costs. With back-pressure the fast stage waits; without it,
 * the fast stage is fast and the messages are gone.
 *
 * <p><strong>Fix:</strong> {@code put()} instead of {@code offer()}, so a full queue slows the
 * producer down, or act on the {@code false}: retry, spill to disk, or at minimum count it
 * somewhere a human will look.
 */
public class DataPipeline {

    /** Small on purpose: the point is what happens when the hand-off is full. */
    public static final int PERSIST_QUEUE_CAPACITY = 4;

    private final BlockingQueue<String> persistQueue =
            new ArrayBlockingQueue<>(PERSIST_QUEUE_CAPACITY);

    private final AtomicInteger parseCount   = new AtomicInteger();
    private final AtomicInteger enrichCount  = new AtomicInteger();
    private final AtomicInteger persistCount = new AtomicInteger();
    private final AtomicInteger droppedCount = new AtomicInteger();

    /**
     * Runs a message through parse and enrich, then hands it to the persist stage.
     *
     * <p>BUG: when the hand-off queue is full, {@code offer} returns false and this method
     * returns as if nothing were wrong.
     *
     * @param msg the message
     * @return true if the message was accepted for persistence, false if it was dropped
     */
    public boolean processMessage(String msg) {
        String parsed = parse(msg);
        String enriched = enrich(parsed);

        boolean accepted = persistQueue.offer(enriched);   // BUG: no back-pressure
        if (!accepted) {
            droppedCount.incrementAndGet();
        }
        return accepted;
    }

    /**
     * Drains whatever the persist stage has queued, slowly, the way persisting is slow.
     *
     * @return how many messages were persisted
     */
    public int drainPersist() {
        int persisted = 0;
        String enriched;
        while ((enriched = persistQueue.poll()) != null) {
            try {
                Thread.sleep(1);        // persisting is slower than parsing, which is the problem
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            persistCount.incrementAndGet();
            persisted++;
        }
        return persisted;
    }

    private String parse(String msg) {
        parseCount.incrementAndGet();
        return "parsed:" + msg;
    }

    private String enrich(String parsed) {
        enrichCount.incrementAndGet();
        return "enriched:" + parsed;
    }

    /** {@return how many messages have been parsed} */
    public int getParseCount() {
        return parseCount.get();
    }

    /** {@return how many messages have been enriched} */
    public int getEnrichCount() {
        return enrichCount.get();
    }

    /** {@return how many messages have actually been persisted} */
    public int getPersistCount() {
        return persistCount.get();
    }

    /** {@return how many messages were dropped because the hand-off queue was full} */
    public int getDroppedCount() {
        return droppedCount.get();
    }
}
