package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings.Grade;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The banner printed above a detector's report must not claim more than the weakest finding
 * under it supports.
 *
 * <p><strong>Why this exists.</strong> The banner used to print the best tier among a graded
 * detector's findings. A block holding one observed mutation and one structural note therefore
 * opened with {@code trust=VERDICT (a finding means the code is wrong ...)}, and the prompt-grade
 * note underneath inherited that sentence. The reader had no way to tell which line was the
 * verdict.
 */
class TrustBannerTest {

    private static final String DETECTOR = "RecordMutableComponentLeakDetector";
    private static final String VERDICT_HINT = "a finding means the code is wrong";

    private static final Grade VERDICT = new Grade(IssueSeverity.HIGH, TrustTier.VERDICT,
            "observed mutation of a shared record's component: order");
    private static final Grade PROMPT = new Grade(IssueSeverity.MEDIUM, TrustTier.PROMPT,
            "structural risk in a shared record: order");

    @Test
    @DisplayName("a block that mixes grades is headed by its weakest one, and says it spans tiers")
    void mixedGradesAreHeadedByTheWeakestTier() {
        String banner = trustBanner(List.of(VERDICT, PROMPT));
        String head = banner.lines().findFirst().orElse("");

        assertTrue(head.contains("trust=PROMPT..VERDICT"),
                "the head line must show the span of tiers, weakest first: " + banner);
        assertFalse(head.contains(VERDICT_HINT),
                "a block holding a prompt-grade finding must not be headed by the verdict hint: " + banner);
    }

    @Test
    @DisplayName("every graded finding is printed with its own tier, so the verdict line is identifiable")
    void eachGradedFindingCarriesItsTier() {
        List<String> lines = trustBanner(List.of(VERDICT, PROMPT)).lines().toList();

        assertTrue(lines.stream().anyMatch(l -> l.contains("trust=VERDICT") && l.contains(VERDICT.summary())),
                "the verdict finding must be printed with its tier: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("trust=PROMPT") && l.contains(PROMPT.summary())),
                "the prompt finding must be printed with its tier: " + lines);
    }

    @Test
    @DisplayName("a block whose findings are all verdict grade keeps the verdict banner")
    void uniformGradesKeepTheirTier() {
        String head = trustBanner(List.of(VERDICT)).lines().findFirst().orElse("");

        assertTrue(head.contains("trust=VERDICT ") && head.contains(VERDICT_HINT), head);
        assertFalse(head.contains(".."), head);
    }

    @Test
    @DisplayName("an ungraded detector is headed by the detector's own tier, on one line")
    void ungradedDetectorKeepsTheDetectorTier() {
        String banner = trustBanner(List.of());

        assertEquals(1, banner.lines().count(), banner);
        assertTrue(banner.contains("trust=" + DetectorTrust.tierOfDetector(DETECTOR) + " "), banner);
    }

    private static String trustBanner(List<Grade> grades) {
        return ConcurrencyRunner.trustBanner(DETECTOR, grades);
    }
}
