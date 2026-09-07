package com.example.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.TrustTier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refuses a corpus pair that already meets the VERDICT bar and was never promoted.
 *
 * <p>This closes the gap that let 116 detectors sit at PROMPT holding evidence nobody had read.
 * The promotion channel and its bar have existed since 2026-08-29, but registering a pair is a
 * manual step, so the eleven detectors promoted when the lane held twelve pairs stayed eleven
 * while the lane grew to 129. Nothing anywhere noticed, because the library's gate only asks
 * whether a VERDICT tier has evidence and never the reverse: whether evidence has a tier.
 *
 * <p>{@link PairEvidence} derives eligibility from the rows rather than listing it, so this cannot
 * drift again. A pair is eligible when both halves name the same class and reach the detector
 * through the same {@code record*}/{@code register*} methods, which is the shape the eleven
 * promoted pairs have: what separates the halves is the state the calls carry and nothing else.
 *
 * <p>Both held-back rules are conservative, and deliberately so, because VERDICT is the one tier
 * safe to fail somebody's merge on. The cost is a known false negative - {@code RESOURCE_LEAKS}
 * pairs opened-never-closed against opened-and-closed, so the missing call is the defect and the
 * pair is sound - and the answer to that is a named entry in
 * {@code PairEvidence.REVIEWED_DESPITE_SHAPE} rather than a looser rule.
 */
class EveryEligiblePairIsPromotedOrExplainedTest {

    @Test
    @DisplayName("every corpus pair that meets the VERDICT bar is promoted")
    void everyEligiblePairIsPromoted() {
        Set<DetectorType> eligible = PairEvidence.eligible();

        assertTrue(eligible.isEmpty(),
                "these detectors have a corpus pair that meets the bar the promotion wave used - "
                        + "same class, and both halves reaching the detector through the same "
                        + "calls - and are not named in META-INF/async-test/verdict-evidence-corpus. "
                        + "A pair that is never registered is evidence nobody reads: the tier stays "
                        + "PROMPT, a build gated on minTrust=VERDICT ignores the detector, and the "
                        + "measurement that would have justified it runs green every night. Add a "
                        + "line naming the fire row and the silent row, raise the tier, or record "
                        + "the pair in PairEvidence.REVIEWED_DESPITE_SHAPE with what is wrong with "
                        + "it: " + names(eligible));
    }

    @Test
    @DisplayName("every promoted pair is classified VERDICT in the library's trust table")
    void everyPromotedPairCarriesTheTier() {
        List<String> mismatched = new ArrayList<>();
        for (DetectorType detector : PairEvidence.promoted()) {
            TrustTier tier = DetectorTrust.tierOf(detector);
            if (tier != TrustTier.VERDICT) {
                mismatched.add(detector + " is registered as VERDICT evidence but carries " + tier);
            }
        }

        assertTrue(mismatched.isEmpty(),
                "the evidence file and the trust table disagree. The library's own gate catches a "
                        + "VERDICT with no evidence; this catches evidence with no VERDICT, which "
                        + "is the direction that leaves a measured detector quietly ungated: "
                        + mismatched);
    }

    private static String names(Set<DetectorType> types) {
        Set<String> sorted = new TreeSet<>();
        for (DetectorType type : types) {
            sorted.add(type.name());
        }
        return sorted.toString();
    }
}
