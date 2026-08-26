package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.example.service.BankTransferService;
import se.deversity.asynctest.example.service.BankTransferService.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for BankTransferService.
 *
 * ========================================================================
 * DETECTOR: DeadlockDetector
 * ========================================================================
 *
 * This test demonstrates a classic A→B / B→A deadlock in a banking transfer
 * service:
 * - A sequential @Test PASSES — transfers complete correctly
 * - The @AsyncTest with 8 threads doing opposite-direction transfers creates
 *   a circular lock dependency that DeadlockDetector catches via ThreadMXBean
 *
 * THE BUG:
 * {@code transfer(from, to, amount)} acquires locks in argument order. When
 * two threads concurrently execute:
 *
 *   Thread 1: transfer(accountA, accountB, 100) → locks A, waits for B
 *   Thread 2: transfer(accountB, accountA, 100) → locks B, waits for A
 *
 * Neither thread can proceed. Both are BLOCKED forever — a deadlock.
 *
 * WHY @Test PASSES:
 * Sequential transfers never overlap. Thread 1 completes A→B before Thread 2
 * starts B→A, so the circular wait never forms.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * 8 threads doing alternate A→B and B→A transfers at full concurrency quickly
 * produce the circular wait. DeadlockDetector calls
 * {@code ThreadMXBean.findDeadlockedThreads()} on timeout and prints a full
 * lock-chain diagnosis.
 *
 * DETECTORS TRIGGERED:
 * DeadlockDetector — ThreadMXBean detects circular lock dependency (JVM deadlock)
 *
 * FIX:
 * Always acquire locks in a consistent global order — e.g. ascending account ID:
 * {@code Account first = a.id.compareTo(b.id) <= 0 ? a : b;}
 * Both threads then compete for the same first lock, one always wins, and the
 * circular-wait condition can never form.
 */
class BankTransferServiceTest {

    private BankTransferService service;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        service  = new BankTransferService();
        accountA = service.openAccount("ACC-001", new BigDecimal("10000.00"));
        accountB = service.openAccount("ACC-002", new BigDecimal("10000.00"));
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no concurrency issues visible
    // -------------------------------------------------------------------------

    @Test
    void testTransfer_singleThread_debitsCreditCorrectly() {
        service.transfer(accountA, accountB, new BigDecimal("500.00"));

        assertEquals(new BigDecimal("9500.00"), accountA.getBalance());
        assertEquals(new BigDecimal("10500.00"), accountB.getBalance());
        assertEquals(1L, service.getTransferCount());
    }

    @Test
    void testTransfer_reverseDirection_singleThread() {
        service.transfer(accountA, accountB, new BigDecimal("200.00"));
        service.transfer(accountB, accountA, new BigDecimal("200.00"));

        // Net effect is zero — both balances should be unchanged
        assertEquals(new BigDecimal("10000.00"), accountA.getBalance());
        assertEquals(new BigDecimal("10000.00"), accountB.getBalance());
        assertEquals(2L, service.getTransferCount());
    }

    @Test
    void testTransfer_insufficientFunds_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(accountA, accountB, new BigDecimal("99999.00")));
    }

    @Test
    void testTransferFixed_singleThread_debitsCreditCorrectly() {
        service.transferFixed(accountA, accountB, new BigDecimal("300.00"));

        assertEquals(new BigDecimal("9700.00"), accountA.getBalance());
        assertEquals(new BigDecimal("10300.00"), accountB.getBalance());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the A→B / B→A deadlock
    // -------------------------------------------------------------------------

    /**
     * The bug: 8 threads alternate between A→B and B→A transfers. Threads with even
     * IDs transfer A→B; odd IDs transfer B→A. This reliably creates the opposite-order
     * lock acquisition that causes a circular wait.
     *
     * DeadlockDetector fires when the test times out and ThreadMXBean finds deadlocked
     * threads. The report prints the full lock chain: which thread holds which lock and
     * which lock it is waiting for.
     *
     * useVirtualThreads = false is not decoration. ThreadMXBean.findDeadlockedThreads()
     * reports platform threads, so with the default virtual-thread workers the cycle here
     * runs through threads JMX cannot put in it, and the detector returns a clean report
     * while the round times out anyway. Measured both ways on this exact subject: silent
     * with the default, "CIRCULAR DEADLOCK DETECTED" with the line below. See issue #363.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it times out, and the timeout names DeadlockDetector, whose report
     *    above it carries the circular lock dependency and a full thread dump
     * 3. Fix: replace transfer() with transferFixed() in the test body
     */
    @Disabled("Remove @Disabled: the round times out on the deadlock, and the failure names "
            + "DeadlockDetector's finding")
    @AsyncTest(threads = 8, invocations = 50, useVirtualThreads = false,
            detectDeadlocks = true, timeoutMs = 5000, failOn = FailOn.LOW)
    void testTransfer_concurrent_detectsDeadlock() {
        // Alternate transfer direction based on thread ID — reliably creates A→B and
        // B→A transfers in parallel, forming a circular-wait deadlock.
        if (Thread.currentThread().threadId() % 2 == 0) {
            service.transfer(accountA, accountB, new BigDecimal("100.00"));
            service.transfer(accountB, accountA, new BigDecimal("100.00")); // restore
        } else {
            service.transfer(accountB, accountA, new BigDecimal("100.00"));
            service.transfer(accountA, accountB, new BigDecimal("100.00")); // restore
        }
    }

    /**
     * Fixed version: {@code transferFixed()} always acquires the lower-ID account lock
     * first, regardless of argument order. The circular-wait condition cannot form
     * because all threads compete for ACC-001 before ACC-002.
     */
    @Test
    void testTransferFixed_concurrent_noDeadlock() {
        // Single-thread sanity: fixed version produces consistent results
        service.transferFixed(accountA, accountB, new BigDecimal("100.00"));
        service.transferFixed(accountB, accountA, new BigDecimal("100.00"));

        assertEquals(new BigDecimal("10000.00"), accountA.getBalance());
        assertEquals(new BigDecimal("10000.00"), accountB.getBalance());
    }
}
