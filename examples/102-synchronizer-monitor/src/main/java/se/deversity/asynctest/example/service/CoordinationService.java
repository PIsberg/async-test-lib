package se.deversity.asynctest.example.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Coordinates a group of workers with three synchronization primitives where one would do.
 *
 * <p>The intent is a start gate: {@code expectedParties} workers each call
 * {@link #execute(Runnable)}, and the gate releases once they have all arrived.
 *
 * <p><strong>Bug 1:</strong> the gate is built with a count of 1, not {@code expectedParties}.
 * It opens as soon as the first worker arrives, so anybody waiting on it proceeds while the
 * other seven are still coming.
 *
 * <p><strong>Bug 2:</strong> the gate is then replaced with a fresh one. A thread that captured
 * the old reference is now waiting on an object nobody will ever count down, and the new gate
 * has never heard of the workers that already arrived. No gate ever gathers more than one party.
 *
 * <p><strong>Bug 3, the one that gives this example its name:</strong> three primitives for one
 * operation. A semaphore for mutual exclusion, a lock inside it for the same thing, and a latch
 * that nobody awaits. An exception between any two of them leaves the rest inconsistent.
 *
 * <p><strong>Fix:</strong> one {@link ReentrantLock} and a
 * {@link java.util.concurrent.locks.Condition} on it, or a single
 * {@code CountDownLatch(expectedParties)} that is created once and never replaced.
 *
 * <p><strong>INSTRUMENTATION:</strong> SynchronizerMonitor counts arrivals per synchronizer
 * instance and reports one that fewer parties reached than it was registered for. The hooks
 * below report each arrival at whichever gate the worker actually found; they default to no-ops,
 * so the production path never touches the test library.
 */
public class CoordinationService {

    private final int expectedParties;
    private final Semaphore semaphore = new Semaphore(1);
    private final ReentrantLock lock = new ReentrantLock();

    /** BUG: count 1 rather than expectedParties, and replaced after every execute(). */
    private volatile CountDownLatch startGate = new CountDownLatch(1);

    private volatile Consumer<CountDownLatch> onArrival = gate -> { };

    private volatile Consumer<CountDownLatch> onAdvance = gate -> { };

    /** Coordinates a single worker. */
    public CoordinationService() {
        this(1);
    }

    /**
     * Coordinates {@code expectedParties} workers.
     *
     * @param expectedParties how many workers the start gate is meant to gather
     */
    public CoordinationService(int expectedParties) {
        this.expectedParties = expectedParties;
    }

    /**
     * Runs {@code task} and signals this worker's arrival at the start gate.
     *
     * @param task the work
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void execute(Runnable task) throws InterruptedException {
        semaphore.acquire();
        CountDownLatch gate = startGate;
        try {
            lock.lock();
            try {
                task.run();
            } finally {
                lock.unlock();
            }
        } finally {
            onArrival.accept(gate);
            gate.countDown();
            onAdvance.accept(gate);
            semaphore.release();
        }
        // BUG: the gate the other workers are holding is discarded here, and the replacement
        // starts from zero arrivals.
        startGate = new CountDownLatch(1);
    }

    /**
     * {@return how many workers the start gate is meant to gather}
     */
    public int getExpectedParties() {
        return expectedParties;
    }

    /**
     * Installs the hooks SynchronizerMonitor needs. No-ops by default.
     *
     * @param arrival called with the gate this worker found, before counting it down
     * @param advance called with the same gate afterwards
     */
    public void observeGate(Consumer<CountDownLatch> arrival, Consumer<CountDownLatch> advance) {
        this.onArrival = arrival;
        this.onAdvance = advance;
    }

    /** {@return the semaphore, for test instrumentation} */
    public Semaphore getSemaphore() {
        return semaphore;
    }

    /** {@return the lock, for test instrumentation} */
    public ReentrantLock getLock() {
        return lock;
    }

    /** {@return the gate as it stands right now, which will not be the gate for long} */
    public CountDownLatch getLatch() {
        return startGate;
    }
}
