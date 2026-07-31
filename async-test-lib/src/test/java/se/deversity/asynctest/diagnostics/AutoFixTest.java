package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AutoFix}.
 *
 * <p>{@link AutoFix} is a pure text-template utility: every public method is a
 * no-argument static getter that returns a fixed, human-readable fix suggestion.
 * There is no detector-name-based dispatch in this class (no method accepts a
 * detector name), so these tests pin the shape/content of each returned
 * suggestion with loose contains-checks rather than exact-string equality —
 * wording tweaks should not break the suite.
 */
class AutoFixTest {

    private static final String HEADER = "💡 AUTO-FIX:";

    // ============= Deadlock =============

    @Test
    void deadlockFix_isNonBlankAndDescribesLockOrdering() {
        String fix = AutoFix.getDeadlockFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Deadlock"));
        assertTrue(fix.contains("consistent lock ordering"));
        assertTrue(fix.contains("tryLock"));
        assertTrue(fix.contains("Option 1"));
        assertTrue(fix.contains("Option 2"));
        assertTrue(fix.contains("Option 3"));
    }

    // ============= Race Condition =============

    @Test
    void raceConditionFix_isNonBlankAndDescribesAtomicAlternatives() {
        String fix = AutoFix.getRaceConditionFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Race Condition"));
        assertTrue(fix.contains("AtomicLong"));
        assertTrue(fix.contains("volatile"));
        assertTrue(fix.contains("synchronized"));
        assertTrue(fix.contains("ReentrantLock"));
    }

    // ============= Visibility =============

    @Test
    void visibilityFix_isNonBlankAndMentionsVolatileAndAtomic() {
        String fix = AutoFix.getVisibilityFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Visibility"));
        assertTrue(fix.contains("volatile"));
        assertTrue(fix.contains("AtomicBoolean"));
    }

    // ============= False Sharing =============

    @Test
    void falseSharingFix_isNonBlankAndMentionsContendedAndPadding() {
        String fix = AutoFix.getFalseSharingFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("False Sharing"));
        assertTrue(fix.contains("@Contended"));
        assertTrue(fix.contains("Padding"));
    }

    // ============= CompletableFuture Leak =============

    @Test
    void completableFutureLeakFix_isNonBlankAndMentionsSupplyAsyncAndTimeout() {
        String fix = AutoFix.getCompletableFutureLeakFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("CompletableFuture"));
        assertTrue(fix.contains("supplyAsync"));
        assertTrue(fix.contains("orTimeout"));
    }

    // ============= Virtual Thread Pinning =============

    @Test
    void virtualThreadPinningFix_isNonBlankAndMentionsReentrantLockAndConcurrentHashMap() {
        String fix = AutoFix.getVirtualThreadPinningFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Virtual Thread Pinning"));
        assertTrue(fix.contains("ReentrantLock"));
        assertTrue(fix.contains("ConcurrentHashMap"));
    }

    // ============= Thread Pool Deadlock =============

    @Test
    void threadPoolDeadlockFix_isNonBlankAndMentionsSeparateExecutor() {
        String fix = AutoFix.getThreadPoolDeadlockFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Thread Pool Deadlock"));
        assertTrue(fix.contains("ExecutorService"));
        assertTrue(fix.contains("nestedPool"));
    }

    // ============= Busy Waiting =============

    @Test
    void busyWaitingFix_isNonBlankAndMentionsWaitNotifyAndCountDownLatch() {
        String fix = AutoFix.getBusyWaitingFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Busy Waiting"));
        assertTrue(fix.contains("wait()"));
        assertTrue(fix.contains("CountDownLatch"));
        assertTrue(fix.contains("LockSupport"));
    }

    // ============= Atomicity Violation =============

    @Test
    void atomicityViolationFix_isNonBlankAndMentionsLongAdderAndSynchronized() {
        String fix = AutoFix.getAtomicityViolationFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Atomicity Violation"));
        assertTrue(fix.contains("AtomicLong"));
        assertTrue(fix.contains("LongAdder"));
    }

    // ============= Lock Leak =============

    @Test
    void lockLeakFix_isNonBlankAndMentionsTryFinally() {
        String fix = AutoFix.getLockLeakFix();

        assertNotNull(fix);
        assertFalse(fix.isBlank());
        assertTrue(fix.contains(HEADER));
        assertTrue(fix.contains("Lock Leak"));
        assertTrue(fix.contains("try-finally") || fix.contains("try {"));
        assertTrue(fix.contains("AutoCloseable"));
    }

    // ============= Cross-cutting =============

    @Test
    void allFixes_shareTheSameAutoFixHeaderConvention() {
        String[] fixes = {
            AutoFix.getDeadlockFix(),
            AutoFix.getRaceConditionFix(),
            AutoFix.getVisibilityFix(),
            AutoFix.getFalseSharingFix(),
            AutoFix.getCompletableFutureLeakFix(),
            AutoFix.getVirtualThreadPinningFix(),
            AutoFix.getThreadPoolDeadlockFix(),
            AutoFix.getBusyWaitingFix(),
            AutoFix.getAtomicityViolationFix(),
            AutoFix.getLockLeakFix()
        };

        for (String fix : fixes) {
            assertTrue(fix.startsWith("\n" + HEADER) || fix.trim().startsWith(HEADER),
                "Every fix suggestion should open with the standard AUTO-FIX header: " + fix);
        }
    }

    @Test
    void repeatedCalls_returnEqualContent() {
        // AutoFix methods return fixed constant text — no per-call state, no randomness.
        assertEquals(AutoFix.getDeadlockFix(), AutoFix.getDeadlockFix());
        assertEquals(AutoFix.getLockLeakFix(), AutoFix.getLockLeakFix());
    }

    @Test
    void autoFix_isANonInstantiableUtilityClass() throws Exception {
        // Pin the utility-class shape: package-private/no-arg-only, private constructor.
        assertTrue(Modifier.isFinal(AutoFix.class.getModifiers()));

        Constructor<?>[] constructors = AutoFix.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        Constructor<?> ctor = constructors[0];
        assertTrue(Modifier.isPrivate(ctor.getModifiers()),
            "AutoFix's sole constructor must be private to prevent instantiation");

        // Invoking it reflectively is harmless and exercises the constructor line for coverage.
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();
        assertNotNull(instance);
    }
}
