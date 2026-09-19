package se.deversity.asynctest.example.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BUGGY bounded buffer: put() signals the wrong condition.
 *
 * BUG: put() adds an item and calls notFull.signal(). Producers wait on notFull; consumers
 *      wait on notEmpty. A consumer parked on an empty buffer is never told the item arrived,
 *      so it stays parked (forever with take(), until its timeout with poll()) while the item
 *      sits in the buffer.
 *
 * WHY IT HIDES: a consumer that arrives after the item never waits, and a single thread doing
 *      put() then take() never blocks. Only a consumer already parked when put() runs is lost.
 *
 * FIX: put() signals notEmpty, the condition its consumers are parked on; take() signals
 *      notFull. (signal() rather than signalAll() is correct here: each condition has its own
 *      kind of waiter, and one item frees one consumer.)
 */
public class BoundedBufferService {

    /** Callbacks around every await and signal, so a test can observe the handshake. */
    public interface Probe {
        void awaiting(Condition condition);

        void awaitExited(Condition condition, boolean timedOut);

        void signalling(Condition condition, boolean all);
    }

    private static final Probe NO_PROBE = new Probe() {
        @Override public void awaiting(Condition condition) { }
        @Override public void awaitExited(Condition condition, boolean timedOut) { }
        @Override public void signalling(Condition condition, boolean all) { }
    };

    private static final int CAPACITY = 4;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Deque<String> buffer = new ArrayDeque<>(CAPACITY);
    private final Probe probe;

    public BoundedBufferService() {
        this(NO_PROBE);
    }

    public BoundedBufferService(Probe probe) {
        this.probe = probe;
    }

    /** Add an item. Blocks if the buffer is full. */
    public void put(String item) throws InterruptedException {
        lock.lock();
        try {
            while (buffer.size() == CAPACITY) {
                probe.awaiting(notFull);
                notFull.await();
                probe.awaitExited(notFull, false);
            }
            buffer.addLast(item);
            probe.signalling(notFull, false);
            notFull.signal();    // BUG: consumers wait on notEmpty
        } finally {
            lock.unlock();
        }
    }

    /** Remove and return an item. Blocks if the buffer is empty. */
    public String take() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                probe.awaiting(notEmpty);
                notEmpty.await();
                probe.awaitExited(notEmpty, false);
            }
            return removeAndSignal();
        } finally {
            lock.unlock();
        }
    }

    /** Remove and return an item, or {@code null} if none arrives within the timeout. */
    public String poll(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                if (remaining <= 0) {
                    return null;
                }
                probe.awaiting(notEmpty);
                remaining = notEmpty.awaitNanos(remaining);
                probe.awaitExited(notEmpty, remaining <= 0);
            }
            return removeAndSignal();
        } finally {
            lock.unlock();
        }
    }

    private String removeAndSignal() {
        String item = buffer.removeFirst();
        probe.signalling(notFull, false);
        notFull.signal();
        return item;
    }

    /** {@return true when at least one consumer is parked waiting for an item} */
    public boolean hasWaitingConsumers() {
        lock.lock();
        try { return lock.hasWaiters(notEmpty); } finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try { return buffer.size(); } finally { lock.unlock(); }
    }

    /** {@return the lock both conditions belong to, so a test can register it with a detector} */
    public ReentrantLock getLock() { return lock; }

    public Condition getNotEmpty() { return notEmpty; }
    public Condition getNotFull()  { return notFull; }
}
