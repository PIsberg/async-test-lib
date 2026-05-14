package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.FundsTransferService;
import se.deversity.asynctest.example.service.FundsTransferService.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for FundsTransferService.
 *
 * ========================================================================
 * DETECTOR: LockOrderValidator (via AsyncTestContext.lockOrderValidator())
 * ========================================================================
 *
 * This test demonstrates how symmetric concurrent transfers between two
 * accounts create a circular lock dependency that deadlocks both threads.
 *
 * THE BUG:
 * FundsTransferService.transfer(from, to) always acquires the FROM account
 * lock first, then the TO account lock. Under concurrent load:
 *   - Thread A calls transfer(account1, account2, 500)
 *     → locks account1.lock(), then tries to lock account2.lock()
 *   - Thread B calls transfer(account2, account1, 300)
 *     → locks account2.lock(), then tries to lock account1.lock()
 *   - Thread A holds account1, waits for account2
 *   - Thread B holds account2, waits for account1
 *   - Deadlock — both threads wait forever
 *
 * WHY @Test PASSES:
 * Sequential transfers never produce the interleaving needed for deadlock.
 * Each transfer completes before the next begins.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * LockOrderValidator records lock acquisition sequences per thread and detects
 * when the same pair of locks is acquired in conflicting orders across threads.
 * It also performs cycle detection on the resulting lock graph.
 *
 * DETECTORS TRIGGERED:
 * LockOrderValidator — accessed via AsyncTestContext.lockOrderValidator()
 *                      (wired through DetectorRegistry, enabled via
 *                       validateLockOrder = true in @AsyncTest).
 *
 * FIX:
 * Impose a global total order on lock acquisition. Always lock the account
 * with the lexicographically smaller ID first, regardless of transfer direction.
 */
class FundsTransferServiceTest {

    private FundsTransferService service;
    private Account account1;
    private Account account2;

    @BeforeEach
    void setUp() {
        service  = new FundsTransferService();
        account1 = new Account("ACC-001");
        account2 = new Account("ACC-002");
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — sequential transfers always succeed without deadlock
    // -------------------------------------------------------------------------

    @Test
    void testTransfer_account1ToAccount2_succeeds() {
        assertDoesNotThrow(() -> service.transfer(account1, account2, 500));
    }

    @Test
    void testTransfer_account2ToAccount1_succeeds() {
        assertDoesNotThrow(() -> service.transfer(account2, account1, 300));
    }

    @Test
    void testTransfer_sameAccount_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> service.transfer(account1, account1, 100));
    }

    @Test
    void testTransfer_zeroAmount_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
            () -> service.transfer(account1, account2, 0));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes circular lock dependency via LockOrderValidator
    // -------------------------------------------------------------------------

    /**
     * The bug: half the threads transfer account1→account2 (locking in order
     * account1, account2) and the other half transfer account2→account1
     * (locking in order account2, account1). This creates a circular dependency
     * that LockOrderValidator detects as an inconsistent ordering on the same
     * account pair.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — LockOrderValidator will flag the inconsistent orderings
     * 3. Fix: always lock the lower-ID account first
     */
    @Disabled("Remove @Disabled to see lock order violation detected by LockOrderValidator")
    @AsyncTest(threads = 8, invocations = 50, validateLockOrder = true)
    void testTransfer_concurrent_detectsLockOrderViolation() {
        // Alternate the transfer direction across threads to produce both orderings
        boolean forward = Thread.currentThread().threadId() % 2 == 0;
        Account from = forward ? account1 : account2;
        Account to   = forward ? account2 : account1;

        // Record the lock acquisition order as the detector would see it
        AsyncTestContext.lockOrderValidator().recordLockAcquisition(from.lock());
        AsyncTestContext.lockOrderValidator().recordLockAcquisition(to.lock());

        // Perform the transfer (in a real deadlock test this would hang;
        // here we use Thread.yield() to increase interleaving likelihood)
        Thread.yield();

        AsyncTestContext.lockOrderValidator().recordLockRelease(to.lock());
        AsyncTestContext.lockOrderValidator().recordLockRelease(from.lock());
    }

    /**
     * Fixed version: always acquire locks in a canonical (sorted) order
     * regardless of transfer direction. No circular dependency possible.
     */
    @Test
    void testTransfer_fixedTotalLockOrder_noViolationDetected() throws InterruptedException {
        Account a = new Account("ACC-001");
        Account b = new Account("ACC-002");

        // Fixed transfer: always lock lower-ID first
        Account first  = a.id().compareTo(b.id()) < 0 ? a : b;
        Account second = a.id().compareTo(b.id()) < 0 ? b : a;

        var validator = new se.deversity.asynctest.diagnostics.LockOrderValidator();

        // Both threads use canonical order — each thread gets its own LockSequence
        // so the validator never sees consecutive pairs across thread boundaries.
        Runnable canonicalTransfer = () -> {
            validator.recordLockAcquisition(first.lock());
            validator.recordLockAcquisition(second.lock());
            validator.recordLockRelease(second.lock());
            validator.recordLockRelease(first.lock());
        };

        Thread t1 = new Thread(canonicalTransfer);
        Thread t2 = new Thread(canonicalTransfer);
        t1.start(); t1.join();
        t2.start(); t2.join();

        var report = validator.validateLockOrder();
        assertFalse(report.hasIssues(),
            "No lock order violation expected with canonical ordering.\n" + report);
    }
}
