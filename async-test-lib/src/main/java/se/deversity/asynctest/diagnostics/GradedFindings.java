package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;

/**
 * Implemented by a detector's report when its findings are not all worth the same.
 *
 * <p><strong>Why this exists.</strong> A trust tier is a property of the detector, and it carries
 * the weakest grade that detector can produce, so that gating on {@link TrustTier#VERDICT} cannot
 * admit a finding the library cannot stand behind. That rule is right for the gate and wrong for
 * the detectors that produce a verdict-grade finding on one path and a prompt-grade one on
 * another: a record whose component was <em>observed</em> being mutated is a bug, while the same
 * detector's note that a record merely <em>holds</em> a mutable component is a prompt to look. Rated
 * as one detector, the observation inherits the note's tier and a verdict-only gate misses it.
 *
 * <p>A report that implements this interface grades each finding it contains, and the
 * {@code failOn} gate then asks whether <em>any</em> finding clears both thresholds rather than
 * judging the detector as a whole. Reports that do not implement it keep the per-detector grade
 * from {@link DetectorTrust} and {@link DetectorDefaultSeverity}, which is still the right answer
 * for a detector whose findings really are all the same kind.
 *
 * <p>The grades describe the findings the report actually contains, so a report with no issues
 * returns an empty list. Nothing reads the grades of a report that
 * {@code hasIssues()} says is empty.
 *
 * @since 1.9.7
 */
@AIPublicAPI
@API(status = Status.EXPERIMENTAL)
public interface GradedFindings {

    /** {@return one grade per finding in this report, in the order the report presents them} */
    List<Grade> grades();

    /**
     * What one finding is worth, on both axes the gate cares about.
     *
     * @param severity how bad this finding would be if it is real
     * @param tier     how far the library can stand behind it being real
     * @param summary  one line naming the finding, for the report line that explains the grade
     */
    record Grade(IssueSeverity severity, TrustTier tier, String summary) {

        /** Validates the inputs. */
        public Grade {
            if (severity == null) throw new IllegalArgumentException("severity must not be null");
            if (tier == null) throw new IllegalArgumentException("tier must not be null");
            summary = (summary == null) ? "" : summary;
        }
    }
}
