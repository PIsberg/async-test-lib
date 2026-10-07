package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A history verified against a spec reports through the run's own pipeline (#934): a round with no
 * linearization is a finding named {@code Linearizability}, so {@code failOn}, {@code minTrust},
 * the console report and every listener see it, without an {@code @AfterAll} assertion.
 */
class LinearizabilityReportingTest {

    private static final SequentialSpec<int[]> COUNTER =
            SequentialSpec.of(() -> new int[1], int[]::clone, (state, op, arg) -> ++state[0]);

    /** Reads, waits for every worker of the round to have read, then writes: a lost update. */
    static final class ReadThenWriteCounter {
        private int value;

        int increment() {
            int read = value;
            AsyncTestContext.rendezvous();
            value = read + 1;
            return read + 1;
        }
    }

    private static AsyncTestConfig.Builder rounds() {
        return AsyncTestConfig.builder().threads(2).invocations(3).timeoutMs(60_000).licenseMockMode(true);
    }

    @Test
    void aNonLinearizableRoundIsAFindingTheRunReports() throws Throwable {
        OperationHistory<ReadThenWriteCounter> history =
                OperationHistory.of(ReadThenWriteCounter::new).verifiedAgainst(COUNTER);
        AsyncFindings findings = AsyncTestRunner.run(rounds().build(), () -> {
            ReadThenWriteCounter counter = history.subject();
            history.call("increment", null, counter::increment);
        });

        findings.assertReported("Linearizability");
        assertTrue(findings.violationsFrom("Linearizability").get(0).message().contains("Round 1 is not linearizable"),
                findings.violationsFrom("Linearizability").toString());
    }

    @Test
    void failOnFailsTheRun_evenWhenOnlyFactGradeFindingsCount() {
        OperationHistory<ReadThenWriteCounter> history =
                OperationHistory.of(ReadThenWriteCounter::new).verifiedAgainst(COUNTER);
        AssertionError e = assertThrows(AssertionError.class, () -> AsyncTestRunner.run(
                rounds().failOn(FailOn.HIGH).minTrust(TrustTier.FACT).build(), () -> {
                    ReadThenWriteCounter counter = history.subject();
                    history.call("increment", null, counter::increment);
                }));
        assertTrue(e.getMessage().contains("Linearizability"), e.getMessage());
    }

    @Test
    void aLinearizableObjectStaysSilent_evenUnderFailOn() throws Throwable {
        OperationHistory<AtomicInteger> history = OperationHistory.of(AtomicInteger::new).verifiedAgainst(COUNTER);
        AsyncFindings findings = AsyncTestRunner.run(rounds().invocations(10).failOn(FailOn.HIGH).build(), () -> {
            AtomicInteger counter = history.subject();
            history.call("increment", null, counter::incrementAndGet);
            history.call("increment", null, counter::incrementAndGet);
        });
        findings.assertNotReported("Linearizability");
    }

    @Test
    void anUnverifiedHistoryReportsNothing_theAssertionStaysOptIn() throws Throwable {
        OperationHistory<ReadThenWriteCounter> history = OperationHistory.of(ReadThenWriteCounter::new);
        AsyncFindings findings = AsyncTestRunner.run(rounds().build(), () -> {
            ReadThenWriteCounter counter = history.subject();
            history.call("increment", null, counter::increment);
        });
        findings.assertNotReported("Linearizability");
    }

    @Test
    void aFailedCheckIsAFact() {
        assertEquals(TrustTier.FACT, DetectorTrust.tierOfDetector("Linearizability"),
                "a history no order can explain is a proof, not a pattern match");
    }
}
