package se.deversity.asynctest.example.service;

import java.util.concurrent.locks.ReentrantLock;

/**
 * A transaction service that splits lock acquire/release across separate methods
 * without a try/finally guard.
 *
 * BUG: If the work passed to {@link #execute(Runnable)} throws an exception,
 * {@code commitTransaction()} is never called and the lock is permanently held.
 * All subsequent threads calling {@code execute()} will block forever (deadlock).
 */
public class TransactionService {

    // Exposed so the test can register it with the detector
    public final ReentrantLock lock = new ReentrantLock();

    /**
     * Begins a transaction by acquiring the lock.
     */
    public void beginTransaction() {
        lock.lock();
    }

    /**
     * Commits the transaction by releasing the lock.
     */
    public void commitTransaction() {
        lock.unlock();
    }

    /**
     * Executes work inside a transaction.
     *
     * BUG: no try/finally — an exception from {@code work.run()} skips
     * {@code commitTransaction()} and leaves the lock permanently held.
     */
    public void execute(Runnable work) {
        beginTransaction();
        work.run();          // if this throws, the lock leaks
        commitTransaction();
    }
}
