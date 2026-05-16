package se.deversity.asynctest.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonReportListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void noFindings_flushReturnsNull() {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        assertNull(listener.flush());
    }

    @Test
    void onStructuredReport_incrementsFindingCount() {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("FalseSharingDetector", IssueSeverity.HIGH, "Report A");
        listener.onStructuredReport("DeadlockDetector", IssueSeverity.CRITICAL, "Report B");
        assertEquals(2, listener.getFindingCount());
    }

    @Test
    void flush_writesJsonFile() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("DeadlockDetector", IssueSeverity.CRITICAL, "Deadlock found");

        Path result = listener.flush();

        assertNotNull(result);
        assertTrue(Files.exists(result));
    }

    @Test
    void flush_jsonContainsVersion() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.LOW, "r");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"asyncTestVersion\""));
    }

    @Test
    void flush_jsonContainsGeneratedAt() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.LOW, "r");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"generatedAt\""));
    }

    @Test
    void flush_jsonContainsTotalFindings() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("A", IssueSeverity.HIGH, "r1");
        listener.onStructuredReport("B", IssueSeverity.MEDIUM, "r2");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"totalFindings\": 2"));
    }

    @Test
    void flush_jsonContainsDetectorName() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("VisibilityMonitor", IssueSeverity.HIGH, "Visibility issue");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"detectorName\": \"VisibilityMonitor\""));
    }

    @Test
    void flush_jsonContainsSeverity() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("SomeDetector", IssueSeverity.CRITICAL, "Critical issue");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"severity\": \"CRITICAL\""));
    }

    @Test
    void flush_jsonContainsTimestampMs() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.LOW, "r");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"timestampMs\""));
    }

    @Test
    void flush_jsonContainsReport() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.MEDIUM, "Thread t1 violated lock order");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("Thread t1 violated lock order"));
    }

    @Test
    void flush_multipleFindings_allIncluded() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("A", IssueSeverity.HIGH, "r1");
        listener.onStructuredReport("B", IssueSeverity.CRITICAL, "r2");
        listener.onStructuredReport("C", IssueSeverity.LOW, "r3");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"totalFindings\": 3"));
        assertTrue(json.contains("\"detectorName\": \"A\""));
        assertTrue(json.contains("\"detectorName\": \"B\""));
        assertTrue(json.contains("\"detectorName\": \"C\""));
    }

    @Test
    void flush_idempotent() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.HIGH, "r");

        Path first = listener.flush();
        assertNotNull(first);

        listener.onStructuredReport("E", IssueSeverity.HIGH, "r2");
        Path second = listener.flush();
        assertNull(second, "Second flush() call should be a no-op");
    }

    @Test
    void flush_jsonEscapesQuotesInReport() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.HIGH, "Field \"counter\" is not volatile");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\\\"counter\\\""),
            "Double quotes in report should be JSON-escaped");
    }

    @Test
    void flush_jsonEscapesNewlinesInReport() throws IOException {
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        listener.onStructuredReport("D", IssueSeverity.HIGH, "Line 1\nLine 2");

        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\\n"),
            "Newlines in report should be JSON-escaped");
    }

    @Test
    void fireDetectorReport_triggersOnStructuredReport() {
        AsyncTestListenerRegistry.clearAll();
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        AsyncTestListenerRegistry.register(listener);

        AsyncTestListenerRegistry.fireDetectorReport("FalseSharingDetector",
            "🔴 CRITICAL: False sharing on field counter");

        assertEquals(1, listener.getFindingCount(),
            "fireDetectorReport should trigger onStructuredReport via registry");

        AsyncTestListenerRegistry.clearAll();
    }

    @Test
    void fireDetectorReport_severityParsedCorrectly() throws IOException {
        AsyncTestListenerRegistry.clearAll();
        JsonReportListener listener = new JsonReportListener(tempDir.toString(), false);
        AsyncTestListenerRegistry.register(listener);

        AsyncTestListenerRegistry.fireDetectorReport("D", "🟡 MEDIUM: some issue");
        String json = Files.readString(listener.flush(), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"severity\": \"MEDIUM\""),
            "Severity should be parsed from the report emoji marker");

        AsyncTestListenerRegistry.clearAll();
    }
}
