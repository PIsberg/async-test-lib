package se.deversity.asynctest.example.service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Banking transfer service that moves funds between accounts.
 *
 * BUG: {@link #transfer(Account, Account, BigDecimal)} acquires locks in argument order.
 * When two threads simultaneously transfer in opposite directions (A→B and B→A),
 * Thread 1 locks Account A then waits for Account B, while Thread 2 locks Account B
 * then waits for Account A — a classic circular-wait deadlock.
 *
 * DeadlockDetector uses {@code ThreadMXBean.findDeadlockedThreads()} to detect the
 * circular dependency in the JVM's lock graph. No manual instrumentation is required.
 *
 * FIX: Always acquire account locks in a consistent global order, such as by account ID
 * ({@link Account#id}). This breaks the circular-wait condition because both threads
 * always compete for the lower-ID lock first, so one of them will always make progress.
 */
public class BankTransferService {

    private final ConcurrentHashMap<String, Account> accounts = new ConcurrentHashMap<>();
    private final AtomicLong transferCount = new AtomicLong();

    public Account openAccount(String id, BigDecimal initialBalance) {
        Account account = new Account(id, initialBalance);
        accounts.put(id, account);
        return account;
    }

    /**
     * Transfers {@code amount} from {@code from} to {@code to}.
     *
     * BUG: Locks are acquired in argument order. Calling {@code transfer(A, B, x)}
     * concurrently with {@code transfer(B, A, y)} produces a circular wait:
     *
     * <pre>
     *   Thread 1: locks A, waits for B
     *   Thread 2: locks B, waits for A
     *   → Both threads are blocked forever.
     * </pre>
     *
     * @throws IllegalArgumentException if the source account has insufficient funds
     */
    public void transfer(Account from, Account to, BigDecimal amount) {
        // BUG: argument-order locking — A→B and B→A deadlock
        synchronized (from) {
            synchronized (to) {
                if (from.balance.compareTo(amount) < 0) {
                    throw new IllegalArgumentException(
                            "Insufficient funds: account " + from.id
                            + " has " + from.balance + ", requested " + amount);
                }
                from.balance = from.balance.subtract(amount);
                to.balance = to.balance.add(amount);
                transferCount.incrementAndGet();
            }
        }
    }

    /**
     * Fixed transfer: acquires locks in ascending account-ID order regardless of
     * argument order. Both threads always race for the same first lock, so one of
     * them always wins and makes progress — no circular wait is possible.
     */
    public void transferFixed(Account from, Account to, BigDecimal amount) {
        Account first  = from.id.compareTo(to.id) <= 0 ? from : to;
        Account second = first == from ? to : from;

        synchronized (first) {
            synchronized (second) {
                if (from.balance.compareTo(amount) < 0) {
                    throw new IllegalArgumentException(
                            "Insufficient funds: account " + from.id
                            + " has " + from.balance + ", requested " + amount);
                }
                from.balance = from.balance.subtract(amount);
                to.balance = to.balance.add(amount);
                transferCount.incrementAndGet();
            }
        }
    }

    public long getTransferCount() {
        return transferCount.get();
    }

    /**
     * A simple bank account with an ID and a mutable balance.
     *
     * The balance field is intentionally not volatile or atomic — all mutations
     * are guarded by the account's own monitor via {@code synchronized (account)}.
     */
    public static final class Account {
        public final String id;
        volatile BigDecimal balance;

        public Account(String id, BigDecimal initialBalance) {
            this.id = id;
            this.balance = initialBalance;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        @Override
        public String toString() {
            return "Account{id='" + id + "', balance=" + balance + "}";
        }
    }
}
