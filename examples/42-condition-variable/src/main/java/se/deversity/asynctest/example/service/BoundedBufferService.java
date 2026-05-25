package se.deversity.asynctest.example.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BUGGY bounded buffer that demonstrates signal() vs signalAll() misuse.
 *
 * BUG: put() calls notFull.signal() and take() calls notEmpty.signal().
 *      With multiple waiting producers and consumers, signal() wakes only one
 *      thread. The woken thread may be of the wrong type (e.g., another
 *      consumer woken when a producer should be), leaving producers or
 *      consumers stuck despite the buffer state allowing progress.
 *
 * FIX: Replace signal() with signalAll() on both conditions so all waiting
 *      threads re-evaluate the condition predicate after each state change.
 */
public class BoundedBufferService {

    private static final int CAPACITY = 4;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();
    private final Deque<String> buffer = new ArrayDeque<>(CAPACITY);

    /** Add an item. Blocks if the buffer is full. */
    public void put(String item) throws InterruptedException {
        lock.lock();
        try {
            while (buffer.size() == CAPACITY) {
                notFull.await();
            }
            buffer.addLast(item);
            notEmpty.signal();   // BUG: should be signalAll()
        } finally {
            lock.unlock();
        }
    }

    /** Remove and return an item. Blocks if the buffer is empty. */
    public String take() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                notEmpty.await();
            }
            String item = buffer.removeFirst();
            notFull.signal();    // BUG: should be signalAll()
            return item;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try { return buffer.size(); } finally { lock.unlock(); }
    }

    public Condition getNotEmpty() { return notEmpty; }
    public Condition getNotFull()  { return notFull; }
}
