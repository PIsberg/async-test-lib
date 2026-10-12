package se.deversity.asynctest.example.service;

import reactor.core.scheduler.NonBlocking;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Answers price quotes on one event-loop thread, the way a reactive service does, plus a pool for
 * the slow lookups.
 */
public class PriceFeed implements AutoCloseable {

    /** A thread of the event loop: it must not block, or every quote queued behind it waits. */
    static final class EventLoopThread extends Thread implements NonBlocking {
        EventLoopThread(Runnable task) {
            super(task, "price-event-loop");
        }
    }

    private final ExecutorService eventLoop = Executors.newSingleThreadExecutor(EventLoopThread::new);
    private final ExecutorService lookups = Executors.newFixedThreadPool(2);

    /**
     * BUG: the slow lookup runs on the event loop itself.
     *
     * @param symbol what to quote
     * @return the price
     * @throws Exception if the quote fails
     */
    public long quoteOnTheLoop(String symbol) throws Exception {
        return eventLoop.submit(() -> slowLookup(symbol)).get();
    }

    /**
     * FIX: the event loop hands the slow lookup to the lookup pool and only formats the answer.
     *
     * @param symbol what to quote
     * @return the price
     * @throws Exception if the quote fails
     */
    public long quote(String symbol) throws Exception {
        long price = lookups.submit(() -> slowLookup(symbol)).get();
        return eventLoop.submit(() -> price).get();
    }

    /** A remote call: blocks for a few milliseconds. */
    static long slowLookup(String symbol) throws InterruptedException {
        Thread.sleep(5);
        return symbol.length() * 100L;
    }

    @Override
    public void close() throws InterruptedException, ExecutionException {
        eventLoop.shutdown();
        lookups.shutdown();
        eventLoop.awaitTermination(5, TimeUnit.SECONDS);
        lookups.awaitTermination(5, TimeUnit.SECONDS);
    }
}
