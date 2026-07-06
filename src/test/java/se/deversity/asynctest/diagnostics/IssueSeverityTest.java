package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins {@link IssueSeverity#fromReport(String)}'s parsing behavior.
 *
 * <p>The original substring-only implementation ({@code report.contains("CRITICAL")},
 * {@code report.contains("LOW")}, ...) produced false positives whenever a detector's
 * explanatory prose (fix suggestions, learning content) happened to contain one of the
 * severity words in ordinary text — e.g. "reduce the critical section" or "this design
 * allows concurrent access" — even though no real severity marker was present. These
 * tests pin both the false-positive fix and that the real marker conventions this
 * codebase's detectors actually use (see {@code IssueSeverity.XXX.format()} call sites
 * across {@code diagnostics}) still resolve correctly.
 */
class IssueSeverityTest {

    // ---- null / empty defaults ----

    @Test
    void fromReport_null_defaultsToHigh() {
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(null));
    }

    @Test
    void fromReport_empty_defaultsToHigh() {
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(""));
    }

    @Test
    void fromReport_noMarkerAtAll_defaultsToHigh() {
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport("Nothing interesting happened here."));
    }

    // ---- false positives the old substring-only matcher produced ----

    @Test
    void fromReport_criticalSectionProse_isNotCritical() {
        // Real text from LockContentionDetector's fix suggestion.
        String report = "    - Reduce the critical section to the minimum work that truly needs exclusive access\n";
        assertNotEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport(report),
                "lower-case prose mentioning 'critical section' must not be read as a CRITICAL marker");
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report),
                "with no real marker present, the default-when-unmatched behavior (HIGH) must still apply");
    }

    @Test
    void fromReport_allowsProse_isNotLow() {
        String report = "This design allows concurrent readers to proceed without blocking.";
        assertNotEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report),
                "'allows' must not be read as containing a LOW marker");
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report));
    }

    @Test
    void fromReport_belowProse_isNotLow() {
        String report = "See the details below for the full stack trace.";
        assertNotEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report),
                "'below' must not be read as containing a LOW marker");
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report));
    }

    // ---- real marker formats used by this codebase's detectors ----

    @Test
    void fromReport_formattedCriticalLabel_isCritical() {
        String report = IssueSeverity.CRITICAL.format() + ": Application threads are deadlocked";
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport(report));
    }

    @Test
    void fromReport_formattedHighLabel_isHigh() {
        String report = IssueSeverity.HIGH.format()
                + ": Potential race conditions detected — unsynchronized writes to shared fields allow threads"
                + " to overwrite each other's changes";
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report));
    }

    @Test
    void fromReport_formattedMediumLabel_isMedium() {
        String report = IssueSeverity.MEDIUM.format() + ": Gatherer integrator ran concurrently";
        assertEquals(IssueSeverity.MEDIUM, IssueSeverity.fromReport(report));
    }

    @Test
    void fromReport_formattedLowLabel_isLow() {
        String report = IssueSeverity.LOW.format() + ": Uncommitted repository changes detected";
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport(report));
    }

    @Test
    void fromReport_plainEmoji_resolvesSeverity() {
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport("🔴 something is very wrong"));
        assertEquals(IssueSeverity.HIGH,     IssueSeverity.fromReport("🟠 something is wrong"));
        assertEquals(IssueSeverity.MEDIUM,   IssueSeverity.fromReport("🟡 something to watch"));
        assertEquals(IssueSeverity.LOW,      IssueSeverity.fromReport("🟢 minor nit"));
    }

    @Test
    void fromReport_bracketedMarker_resolvesSeverity() {
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport("[CRITICAL] deadlock imminent"));
        assertEquals(IssueSeverity.HIGH,     IssueSeverity.fromReport("[HIGH] data corruption risk"));
        assertEquals(IssueSeverity.MEDIUM,   IssueSeverity.fromReport("[MEDIUM] resource leak"));
        assertEquals(IssueSeverity.LOW,      IssueSeverity.fromReport("[LOW] style nit"));
    }

    @Test
    void fromReport_severityPrefixMarker_resolvesSeverity() {
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport("Severity: CRITICAL - deadlock"));
        assertEquals(IssueSeverity.HIGH,     IssueSeverity.fromReport("Severity: HIGH - data race"));
    }

    @Test
    void fromReport_bareUpperCaseWord_resolvesSeverity() {
        // Detectors without the label/emoji convention: a standalone, upper-case,
        // word-bounded token should still resolve.
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport("STATUS: CRITICAL"));
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport("STATUS: LOW"));
    }

    // ---- worst-of-multiple resolution ----

    @Test
    void fromReport_multipleMarkers_resolvesToWorst() {
        String report = IssueSeverity.LOW.format() + ": minor issue\n"
                + IssueSeverity.CRITICAL.format() + ": also a deadlock risk";
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport(report),
                "a report quoting more than one real marker must resolve to the worst one");
    }

    @Test
    void fromReport_realMarkerAlongsideFalsePositiveProse_ignoresProse() {
        // A HIGH-labelled report whose fix-suggestion text also mentions "critical
        // section" must resolve to HIGH, not be escalated to CRITICAL by the prose.
        String report = IssueSeverity.HIGH.format() + ": race detected\n"
                + "Fix: reduce the critical section to the minimum work needed.";
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport(report));
    }
}
