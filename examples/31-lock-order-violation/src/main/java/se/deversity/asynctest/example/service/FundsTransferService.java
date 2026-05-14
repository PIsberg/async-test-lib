package se.deversity.asynctest.example.service;

/**
 * Transfers funds between bank accounts while holding both account locks.
 *
 * BUG: {@code transfer(from, to, amount)} always acquires the source
 * account lock first, then the destination account lock.
 *
 * When Thread A transfers from Account-1 → Account-2 at the same time as
 * Thread B transfers from Account-2 → Account-1, each thread holds the
 * other's destination lock and is waiting for the lock the other thread
 * holds. Neither can proceed — classic circular deadlock.
 *
 * <pre>
 * Thread A: lock(account1) ... waiting for lock(account2)
 * Thread B: lock(account2) ... waiting for lock(account1)
 *                    ↑ deadlock
 * </pre>
 *
 * FIX: Impose a total ordering on lock acquisition by always locking the
 * account with the lower account ID first:
 *
 * <pre>
 * Account first  = from.id() < to.id() ? from : to;
 * Account second = from.id() < to.id() ? to   : from;
 * synchronized (first) {
 *     synchronized (second) { ... }
 * }
 * </pre>
 */
public class FundsTransferService {

    public record Account(String id, Object lock) {
        public Account(String id) {
            this(id, new Object());
        }
    }

    /**
     * Transfer {@code amount} from {@code from} to {@code to}.
     *
     * DEADLOCK RISK: locks {@code from} first, then {@code to}.
     * Reversed concurrent transfer (to → from) creates a circular dependency.
     */
    public void transfer(Account from, Account to, long amount) {
        if (from == to || amount <= 0) {
            throw new IllegalArgumentException("Invalid transfer parameters");
        }

        synchronized (from.lock()) {          // acquire source lock first
            // Simulated processing time — increases deadlock probability
            Thread.yield();
            synchronized (to.lock()) {        // acquire destination lock second
                // Simulate the actual ledger update
                performLedgerUpdate(from.id(), to.id(), amount);
            }
        }
    }

    private void performLedgerUpdate(String fromId, String toId, long amount) {
        // In production: update database rows, publish domain event, etc.
        // Here we just validate the operation completes without error.
        if (amount > 1_000_000) {
            throw new IllegalArgumentException("Transfer limit exceeded");
        }
    }
}
