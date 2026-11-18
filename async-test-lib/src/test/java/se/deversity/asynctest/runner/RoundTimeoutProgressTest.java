package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.Preset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner tells a budget that ran out while rounds were completing from a round that hung
 * (#1386). {@link RoundTimeoutMessageTest} pins the texts; this pins which one a real run gets.
 */
@E2E
class RoundTimeoutProgressTest {

    @Test
    void aSlowCorrectBodyThatRunsOutOfBudgetIsNotCalledADeadlock() {
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(40).timeoutMs(1000).build(),
                        () -> Thread.sleep(40))));

        String message = timeout.getMessage();
        assertTrue(message.contains("rounds completed"), message);
        assertTrue(message.contains("raise it or lower invocations"), message);
        assertFalse(message.contains("Possible deadlock"), message);
        if (timeout.getCause() != null) {
            assertFalse(String.valueOf(timeout.getCause().getMessage()).contains("barrier"),
                    "the workers were in the body: " + timeout.getCause().getMessage());
        }
    }

    @Test
    void aHungRoundIsStillAPossibleDeadlock() {
        AssertionError timeout = assertThrows(AssertionError.class, () -> withUnstretchedBudget(() ->
                AsyncTestRunner.run(config().threads(2).invocations(1).timeoutMs(300).build(),
                        () -> Thread.sleep(30_000))));

        assertTrue(timeout.getMessage().contains("Possible deadlock"), timeout.getMessage());
        assertFalse(String.valueOf(timeout.getCause().getMessage()).contains("barrier"),
                "both workers were asleep in the body: " + timeout.getCause().getMessage());
    }

    private static AsyncTestConfig.Builder config() {
        return AsyncTestConfig.builder().licenseMockMode(true).preset(Preset.NONE);
    }

    private static final String MULTIPLIER_PROPERTY = "async-test.timeout.multiplier";

    private String previousMultiplier;

    @BeforeEach
    void rememberTheMultiplier() {
        previousMultiplier = System.getProperty(MULTIPLIER_PROPERTY);
    }

    @AfterEach
    void restoreTheMultiplier() {
        if (previousMultiplier == null) {
            System.clearProperty(MULTIPLIER_PROPERTY);
        } else {
            System.setProperty(MULTIPLIER_PROPERTY, previousMultiplier);
        }
    }

    /** CI stretches every budget; both runs depend on theirs expiring when stated. */
    private static void withUnstretchedBudget(AsyncTestRunner.Body run) throws Throwable {
        System.setProperty(MULTIPLIER_PROPERTY, "1.0");
        run.run();
    }
}
