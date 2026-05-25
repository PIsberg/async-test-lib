package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes messages through three async stages: parse, enrich, persist.
 *
 * <p><strong>Bug:</strong> Stages run at different speeds with no back-pressure
 * coordination. The enrich stage is sometimes slow (up to 10 ms) and the persist
 * stage is always slow (20 ms). Under concurrent load the pipeline cannot signal
 * upstream stages to slow down, causing events to pile up or fail under thread
 * interruption and timeout pressure.
 *
 * <p><strong>Fix:</strong> Introduce bounded {@link java.util.concurrent.BlockingQueue}
 * hand-offs between stages so that fast producers are naturally slowed by slow consumers.
 */
public class DataPipeline {

    private final AtomicInteger parseCount   = new AtomicInteger();
    private final AtomicInteger enrichCount  = new AtomicInteger();
    private final AtomicInteger persistCount = new AtomicInteger();
    private final AtomicInteger failCount    = new AtomicInteger();

    /**
     * Processes a message through parse → enrich → persist stages.
     * No back-pressure between stages — stages run at different speeds.
     *
     * @throws RuntimeException if the persist stage fails under load
     */
    public void processMessage(String msg) {
        String parsed  = parse(msg);
        String enriched = enrich(parsed);
        persist(enriched);
    }

    private String parse(String msg) {
        parseCount.incrementAndGet();
        // Fast: parse is always quick
        return "parsed:" + msg;
    }

    private String enrich(String parsed) {
        enrichCount.incrementAndGet();
        // Bug: sometimes slow, no back-pressure to slow down upstream
        if (parsed.hashCode() % 5 == 0) {
            try { Thread.sleep(10); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return "enriched:" + parsed;
    }

    private void persist(String enriched) {
        // Bug: always slow; no back-pressure coordination
        try {
            Thread.sleep(20);
            persistCount.incrementAndGet();
        } catch (InterruptedException e) {
            failCount.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Persist interrupted — event lost: " + enriched);
        }
    }

    public int getParseCount()   { return parseCount.get(); }
    public int getEnrichCount()  { return enrichCount.get(); }
    public int getPersistCount() { return persistCount.get(); }
    public int getFailCount()    { return failCount.get(); }
}
