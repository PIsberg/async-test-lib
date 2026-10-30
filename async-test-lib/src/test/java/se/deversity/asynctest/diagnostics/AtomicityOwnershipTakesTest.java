package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AtomicityValidator#ownershipTakes()} counts the takes that reached the validator, so a
 * silent hand-off row can say whether its events arrived (#1133).
 *
 * <p>The corpus row {@code agent_jctoolsHandOff_offererWritesAfterTheOffer} went silent once in
 * about 100 legs with nothing kept to tell a feed that lost the takes from a validator that
 * excused them. The count is the feed's half of that answer: zero means no take arrived, anything
 * else means the takes arrived and the validator decided.
 */
class AtomicityOwnershipTakesTest {

    private static final int OBJECT = 101;
    private static final int OTHER_OBJECT = 202;
    private static final int QUEUE = 7;

    @Test
    @DisplayName("each take is counted once, and an offer alone is not a take")
    void countsTakesNotOffers() {
        AtomicityValidator validator = new AtomicityValidator();
        assertEquals(0, validator.ownershipTakes(), "a fresh validator has seen no take");

        validator.recordOwnershipOffered(OBJECT, QUEUE, 1L);
        assertEquals(0, validator.ownershipTakes(), "an offer is not a take");

        validator.recordOwnershipTaken(OBJECT, QUEUE, 2L);
        validator.recordOwnershipTaken(OBJECT, QUEUE, 3L);
        validator.recordOwnershipTaken(OTHER_OBJECT, 4L);
        assertEquals(3, validator.ownershipTakes(),
                "two takes of one object and one of another; the offerer named as generation 0's"
                        + " owner is not a take");
    }

    @Test
    @DisplayName("a disabled or reset validator reports no takes")
    void resetAndDisabledCountNothing() {
        AtomicityValidator validator = new AtomicityValidator();
        validator.recordOwnershipTaken(OBJECT, 2L);
        validator.reset();
        assertEquals(0, validator.ownershipTakes(), "reset drops the run's takes");

        validator.disable();
        validator.recordOwnershipTaken(OBJECT, 2L);
        assertEquals(0, validator.ownershipTakes(), "a disabled validator records no take");
    }
}
