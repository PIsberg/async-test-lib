package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a field's accesses are split by the object they belong to.
 *
 * <p>The same field name, touched by the same number of threads, is a race when they share one
 * instance and nothing at all when each has its own. Only the instance identity separates the two,
 * which is why these two tests differ by exactly that argument.
 */
class PerInstanceAnalysisTest {

    private static final int ONE_INSTANCE = 0x1234;

    @Test
    @DisplayName("threads each touching their own instance are not contending")
    void perThreadInstancesAreAnalysedApart() {
        AtomicityValidator validator = new AtomicityValidator();
        // Six threads, six hashers: exactly what BloomFilter.put does per call.
        for (int thread = 1; thread <= 6; thread++) {
            int ownInstance = 0xABC0 + thread;
            validator.recordFieldAccessUnderLocks("Hasher.h1", null, false, thread, 0L, false,
                    Integer.MIN_VALUE, ownInstance);
            validator.recordFieldAccessUnderLocks("Hasher.h1", null, true, thread, 0L, false,
                    Integer.MIN_VALUE, ownInstance);
        }

        assertTrue(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "each thread read and wrote its own object. Merging them by field name is what "
                        + "made a per-call hasher look like shared state.");
    }

    @Test
    @DisplayName("threads sharing one instance are still contending")
    void oneSharedInstanceIsStillReported() {
        AtomicityValidator validator = new AtomicityValidator();
        for (int thread = 1; thread <= 6; thread++) {
            validator.recordFieldAccessUnderLocks("Counter.count", null, false, thread, 0L, false,
                    Integer.MIN_VALUE, ONE_INSTANCE);
            validator.recordFieldAccessUnderLocks("Counter.count", null, true, thread, 0L, false,
                    Integer.MIN_VALUE, ONE_INSTANCE);
        }

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "one object, six threads, read and write, no lock. If the split silences this it "
                        + "has stopped distinguishing instances and is just suppressing findings.");
    }

    @Test
    @DisplayName("an unknown instance keeps the old behaviour of one group per field")
    void identityZeroBehavesAsBefore() {
        AtomicityValidator validator = new AtomicityValidator();
        for (int thread = 1; thread <= 3; thread++) {
            validator.recordFieldAccessUnderLocks("Legacy.field", null, false, thread, 0L);
            validator.recordFieldAccessUnderLocks("Legacy.field", null, true, thread, 0L);
        }

        assertFalse(validator.analyzeAtomicity().unsafeFieldAccesses.isEmpty(),
                "callers that supply no identity, which is every non-agent path, must keep "
                        + "reporting exactly what they reported before.");
    }
}
