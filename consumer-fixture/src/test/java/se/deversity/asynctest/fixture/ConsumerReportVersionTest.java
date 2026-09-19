package se.deversity.asynctest.fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.JsonReportListener;
import se.deversity.asynctest.report.SarifFormatter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version a report names must be the version of the jar that wrote it (#703). Only the
 * packaged artifact can answer that, because the value comes from the jar manifest, so the
 * check lives here rather than in the library's own suite, where the classes are unpackaged.
 */
class ConsumerReportVersionTest {

    private static final String EXPECTED = System.getProperty("async-test.expected-version");

    @Test
    void jsonReportNamesTheVersionOfTheResolvedJar(@TempDir Path dir) throws Exception {
        assertNotNull(EXPECTED, "surefire must pass async-test.expected-version");
        JsonReportListener listener = new JsonReportListener(dir.toString(), false);
        listener.onStructuredReport("RaceConditionDetector", IssueSeverity.HIGH, "finding");

        Path written = listener.flush();

        assertNotNull(written);
        String json = Files.readString(written);
        assertTrue(json.contains("\"asyncTestVersion\": \"" + EXPECTED + "\""), json);
    }

    @Test
    void sarifReportNamesTheVersionOfTheResolvedJar() {
        assertNotNull(EXPECTED, "surefire must pass async-test.expected-version");

        String sarif = new SarifFormatter().format(List.of());

        assertTrue(sarif.contains("\"version\": \"" + EXPECTED + "\""), sarif);
    }
}
