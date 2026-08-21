package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * How much a finding from a given detector is worth.
 *
 * <p>{@link IssueSeverity} answers "how bad would this be if it were real". This enum answers the
 * question a reader asks first: "is it real". The two are independent. A CRITICAL finding from a
 * detector that cannot see your lock is still a prompt to go and look, and a LOW finding that
 * states an observed fact is still a fact.
 *
 * <p>The tier is a property of the detector, not of the individual report, and it is deliberately
 * the <em>weakest</em> tier that detector can produce. A detector that emits a verdict-grade
 * finding in one code path and a prompt-grade one in another is classified as a prompt, so that
 * gating on {@link #VERDICT} can never admit a finding the library cannot stand behind.
 *
 * <p>Tiers are assigned in {@link DetectorTrust} and are not free text: promotion to
 * {@link #VERDICT} requires a both-directions case in the detector-accuracy eval, and a gate
 * refuses the promotion without one. See {@code docs/analysis/detector-accuracy-eval.md}.
 *
 * @since 1.10.0
 */
@AIPublicAPI
@API(status = Status.EXPERIMENTAL)
public enum TrustTier {

    /**
     * A performance or hygiene note. Firing says nothing about correctness, and the code it names
     * may be entirely right. Never gate a build on this tier.
     */
    ADVISORY,

    /**
     * A prompt to go and verify. The detector saw a pattern it cannot fully model, most often
     * because synchronization it does not know about could make the code correct. A finding means
     * "this deserves a look", not "this is broken".
     *
     * <p>This is the tier a detector gets by default, because it is the honest description of a
     * detector for which nobody has yet measured the silent-on-correct-code direction.
     */
    PROMPT,

    /**
     * The report states something that was observed, not inferred. The claim in the text is true:
     * this executor really did run on platform threads, this collection really was touched by
     * three threads. Whether that is a bug in your design is the reader's call, so a finding is
     * evidence rather than a verdict.
     */
    FACT,

    /**
     * A finding means the code is wrong. Backed by a measured both-directions case: the detector
     * fires on the buggy subject and stays silent on its correctly synchronized twin, asserted in
     * {@code DetectorAccuracyEvalTest}.
     *
     * <p>This is the only tier safe to fail a merge on without a human reading the report first.
     */
    VERDICT;

    /**
     * {@return whether this tier is at least as trustworthy as {@code floor}}
     *
     * <p>Used by the failOn gate: a finding trips the gate only when its detector's tier clears
     * the configured floor.
     *
     * @param floor the minimum tier to accept; {@code null} means no floor
     */
    public boolean atLeast(TrustTier floor) {
        return floor == null || ordinal() >= floor.ordinal();
    }
}
