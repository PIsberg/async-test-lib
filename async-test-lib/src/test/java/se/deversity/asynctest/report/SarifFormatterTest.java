package se.deversity.asynctest.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.SiteCapture;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SarifFormatter}.
 *
 * <p>The assertions that matter are the ones a SARIF consumer would enforce: every referenced
 * {@code ruleId} is declared in the run's rule list, the JSON is well formed with balanced
 * delimiters and correctly escaped strings, and the severity mapping does not promote a finding
 * the library cannot prove into something that blocks a merge.
 */
class SarifFormatterTest {

    private SarifFormatter formatter;

    @BeforeEach
    void setUp() {
        // Fixed version so the output does not depend on how the classes were packaged.
        formatter = new SarifFormatter("1.8.0-test");
    }

    private static Violation violation(String detector, IssueSeverity severity, String message,
                                       List<SiteCapture.Site> sites) {
        return new Violation(detector, severity, message, sites, Map.of(), Instant.EPOCH);
    }

    @Test
    void emptyInputProducesAValidEmptyRun() {
        String sarif = formatter.format(List.of());

        assertTrue(sarif.contains("\"version\": \"2.1.0\""), sarif);
        assertTrue(sarif.contains("\"results\": []"), "An empty run needs an empty results array: " + sarif);
        assertBalanced(sarif);
    }

    @Test
    void nullInputIsTreatedAsEmpty() {
        assertBalanced(formatter.format(null));
    }

    @Test
    void everyReferencedRuleIdIsDeclared() {
        String sarif = formatter.format(List.of(
                violation("DeadlockDetector", IssueSeverity.CRITICAL, "cycle", List.of()),
                violation("RaceConditionDetector", IssueSeverity.MEDIUM, "shared", List.of()),
                violation("DeadlockDetector", IssueSeverity.CRITICAL, "another cycle", List.of())));

        // A result whose ruleId has no matching rule is the usual reason an upload is rejected.
        assertEquals(2, countOf(sarif, "\"id\": \"DeadlockDetector\"")
                      + countOf(sarif, "\"id\": \"RaceConditionDetector\""),
                "Each detector must be declared exactly once: " + sarif);
        assertEquals(3, countOf(sarif, "\"ruleId\":"), "Every finding must produce a result");
        assertBalanced(sarif);
    }

    @Test
    void criticalAndHighAreErrors() {
        String sarif = formatter.format(List.of(
                violation("DeadlockDetector", IssueSeverity.CRITICAL, "c", List.of()),
                violation("LockOrderValidator", IssueSeverity.HIGH, "h", List.of())));

        // Four: each detector declares a rule with a defaultConfiguration level, and each
        // finding emits a result with its own level. Both have to say error, because a consumer
        // that suppresses a rule by default renders the results as suppressed too.
        assertEquals(4, countOf(sarif, "\"level\": \"error\""), sarif);
        assertFalse(sarif.contains("\"level\": \"warning\"") || sarif.contains("\"level\": \"note\""),
                "Nothing here is below error: " + sarif);
        assertTrue(sarif.contains("\"security-severity\": \"9.0\""), sarif);
        assertTrue(sarif.contains("\"security-severity\": \"7.0\""), sarif);
    }

    @Test
    void mediumIsAWarningNotAnError() {
        String sarif = formatter.format(List.of(
                violation("RaceConditionDetector", IssueSeverity.MEDIUM, "verify synchronization", List.of())));

        assertTrue(sarif.contains("\"level\": \"warning\""),
                "MEDIUM is the tier detectors use for correct-but-shared code. Mapping it to "
                + "error would fail merges over findings the library cannot prove: " + sarif);
        assertFalse(sarif.contains("\"level\": \"error\""), sarif);
    }

    @Test
    void lowIsANote() {
        String sarif = formatter.format(List.of(
                violation("HighContentionAtomicDetector", IssueSeverity.LOW, "advisory", List.of())));
        assertTrue(sarif.contains("\"level\": \"note\""), sarif);
    }

    @Test
    void aCapturedSiteBecomesAFileAnnotation() {
        String sarif = formatter.format(List.of(violation(
                "DeadlockDetector", IssueSeverity.CRITICAL, "cycle",
                List.of(new SiteCapture.Site("com.example.OrderService", "checkout", "OrderService.java", 42)))));

        assertTrue(sarif.contains("\"uri\": \"com/example/OrderService.java\""),
                "The package must be folded into the path so the file can be matched: " + sarif);
        assertTrue(sarif.contains("\"startLine\": 42"), sarif);
        assertBalanced(sarif);
    }

    @Test
    void extraSitesBecomeRelatedLocations() {
        String sarif = formatter.format(List.of(violation(
                "LockOrderValidator", IssueSeverity.HIGH, "inversion",
                List.of(new SiteCapture.Site("com.example.A", "m", "A.java", 10),
                        new SiteCapture.Site("com.example.B", "m", "B.java", 20)))));

        assertTrue(sarif.contains("\"relatedLocations\""),
                "A concurrency bug involves at least two sites; the others must survive: " + sarif);
        assertTrue(sarif.contains("com/example/A.java") && sarif.contains("com/example/B.java"), sarif);
        assertBalanced(sarif);
    }

    @Test
    void aFindingWithNoSiteGetsNoLocationRatherThanAGuess() {
        String sarif = formatter.format(List.of(
                violation("RaceConditionDetector", IssueSeverity.MEDIUM, "no site captured", List.of())));

        assertTrue(sarif.contains("\"locations\": []"),
                "Pinning a finding to an arbitrary file annotates a line that is not the "
                + "problem; an empty locations array is the honest output: " + sarif);
    }

    @Test
    void lineNumberIsClampedToOne() {
        String sarif = formatter.format(List.of(violation(
                "DeadlockDetector", IssueSeverity.CRITICAL, "c",
                List.of(new SiteCapture.Site("com.example.A", "m", "A.java", 0)))));

        assertTrue(sarif.contains("\"startLine\": 1"),
                "SARIF startLine is 1-based; 0 is rejected by strict consumers: " + sarif);
    }

    @Test
    void messagesWithQuotesAndNewlinesAreEscaped() {
        String sarif = formatter.format(List.of(violation(
                "DeadlockDetector", IssueSeverity.CRITICAL,
                "thread \"worker-1\" holds\n\ta \\ lock", List.of())));

        assertTrue(sarif.contains("\\\"worker-1\\\""), "Quotes must be escaped: " + sarif);
        assertTrue(sarif.contains("\\n") && sarif.contains("\\t"), "Control chars must be escaped: " + sarif);
        assertTrue(sarif.contains("\\\\"), "Backslashes must be escaped: " + sarif);
        assertBalanced(sarif);
    }

    @Test
    void toolMetadataIsPresent() {
        String sarif = formatter.format(List.of(
                violation("DeadlockDetector", IssueSeverity.CRITICAL, "c", List.of())));

        assertTrue(sarif.contains("\"name\": \"async-test-lib\""), sarif);
        assertTrue(sarif.contains("\"version\": \"1.8.0-test\""), sarif);
        assertTrue(sarif.contains("\"informationUri\""), sarif);
        assertTrue(sarif.contains("\"tags\": [\"concurrency\", \"async-test\"]"), sarif);
    }

    @Test
    void isUsableAsAFormatter() {
        Formatter asFormatter = formatter;   // must satisfy the public SPI
        assertBalanced(asFormatter.format(List.of(
                violation("DeadlockDetector", IssueSeverity.CRITICAL, "c", List.of()))));
    }

    /**
     * Structural well-formedness without pulling in a JSON parser: braces and brackets balance,
     * quotes are even once escaped pairs are removed, and there is no trailing comma before a
     * closing delimiter. A malformed document is the failure mode that only shows up when a
     * platform rejects the upload, long after the build went green.
     */
    private static void assertBalanced(String sarif) {
        int braces = 0;
        int brackets = 0;
        boolean inString = false;
        boolean escaped = false;
        char lastMeaningful = ' ';

        for (int i = 0; i < sarif.length(); i++) {
            char c = sarif.charAt(i);
            if (inString) {
                if (escaped)            escaped = false;
                else if (c == '\\')     escaped = true;
                else if (c == '"')      inString = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> braces++;
                case '}' -> {
                    braces--;
                    assertFalse(lastMeaningful == ',', "Trailing comma before '}' at index " + i);
                }
                case '[' -> brackets++;
                case ']' -> {
                    brackets--;
                    assertFalse(lastMeaningful == ',', "Trailing comma before ']' at index " + i);
                }
                default -> { }
            }
            if (!Character.isWhitespace(c)) lastMeaningful = c;
            assertTrue(braces >= 0 && brackets >= 0, "Delimiters closed before opening: " + sarif);
        }
        assertFalse(inString, "Unterminated string: " + sarif);
        assertEquals(0, braces, "Unbalanced braces: " + sarif);
        assertEquals(0, brackets, "Unbalanced brackets: " + sarif);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }
}
