package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unsafe publication — another thread touching an object before its constructor has finished —
 * is the entire reason this detector exists. It could not report one.
 *
 * <p>The check read:
 *
 * <pre>{@code
 * long threadId = Thread.currentThread().threadId();
 * ...
 * if (threadId != Thread.currentThread().threadId()) {   // always false
 *     state.threadsThatAccessedDuringConstruction.incrementAndGet();
 * }
 * }</pre>
 *
 * <p>The value was compared against itself twelve lines after being assigned from the very same
 * expression, so the counter never incremented and {@code unsafeObjects} was always empty. The
 * state did not even record which thread was constructing the object — the field needed to make
 * the comparison meaningful did not exist.
 *
 * <p>The only other contributor to {@code hasIssues()} needs {@code !constructionComplete}, so
 * an object that is published mid-construction and whose constructor then finishes — the normal
 * case, and what the API's own usage example shows — produced no finding at all.
 */
class ConstructorSafetyUnsafePublicationTest {

    @Test
    void anObjectTouchedByAnotherThreadDuringConstructionIsReported() throws InterruptedException {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object underConstruction = new Object();

        // The constructing thread starts building the object...
        validator.recordConstructionStart(underConstruction);

        // ...and leaks `this` to another thread before the constructor returns.
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            validator.recordFieldAccess(underConstruction, "value", System.nanoTime());
            done.countDown();
        });
        other.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "the publishing thread must finish");
        other.join();

        // The constructor then completes normally, as it does in real code.
        validator.recordConstructionEnd(underConstruction);

        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();

        assertFalse(report.unsafeObjects.isEmpty(),
            "an object accessed by another thread mid-construction must be reported as unsafe");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }

    /** The constructing thread touching its own fields is normal — it must not be flagged. */
    @Test
    void theConstructingThreadTouchingItsOwnFieldsIsNotUnsafe() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object obj = new Object();

        validator.recordConstructionStart(obj);
        validator.recordFieldAccess(obj, "value", System.nanoTime());
        validator.recordConstructionEnd(obj);

        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();

        assertTrue(report.unsafeObjects.isEmpty(),
            "a constructor writing its own fields on its own thread is not unsafe publication: "
                + report.unsafeObjects);
    }
}
