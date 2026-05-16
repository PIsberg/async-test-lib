package se.deversity.asynctest.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JUnitXmlReportListenerTest {

    @TempDir
    Path tempDir;

    @Test
    void noFindings_flushReturnsNull() {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        Path result = listener.flush();
        assertNull(result, "flush() should return null when there are no findings");
    }

    @Test
    void onDetectorReport_incrementsFindingCount() {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("FalseSharingDetector", "False sharing detected");
        listener.onDetectorReport("DeadlockDetector", "Deadlock detected");
        assertEquals(2, listener.getFindingCount());
    }

    @Test
    void flush_writesXmlFile() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("FalseSharingDetector", "False sharing detected in field 'counter'");

        Path result = listener.flush();

        assertNotNull(result, "flush() should return the written file path");
        assertTrue(Files.exists(result), "XML report file should exist on disk");
    }

    @Test
    void flush_xmlContainsTestsuite() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("VisibilityMonitor", "Visibility issue detected");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("<testsuite"), "XML should contain <testsuite>");
        assertTrue(xml.contains("tests=\"1\""), "XML should report 1 test");
        assertTrue(xml.contains("failures=\"1\""), "XML should report 1 failure");
    }

    @Test
    void flush_xmlContainsDetectorName() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("ReentrantLockDetector", "Lock acquired unfairly");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("ReentrantLockDetector"),
            "XML should contain the detector name");
        assertTrue(xml.contains("ConcurrencyIssueDetected"),
            "XML failure type should be ConcurrencyIssueDetected");
    }

    @Test
    void flush_xmlContainsReportContent() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        String report = "Thread t1 acquired lock A before lock B, violating established order";
        listener.onDetectorReport("LockOrderValidator", report);

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains(report), "XML should contain the full report text in CDATA");
    }

    @Test
    void flush_multipleFindings_allIncluded() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("DetectorA", "Report A");
        listener.onDetectorReport("DetectorB", "Report B");
        listener.onDetectorReport("DetectorC", "Report C");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("tests=\"3\""), "XML should report 3 tests");
        assertTrue(xml.contains("failures=\"3\""), "XML should report 3 failures");
        assertTrue(xml.contains("DetectorA"));
        assertTrue(xml.contains("DetectorB"));
        assertTrue(xml.contains("DetectorC"));
    }

    @Test
    void flush_idempotent_secondCallDoesNothing() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("SomeDetector", "Some report");

        Path first = listener.flush();
        assertNotNull(first);

        listener.onDetectorReport("AnotherDetector", "Another report");
        Path second = listener.flush();
        assertNull(second, "Second flush() call should be a no-op");

        // File should contain only 1 finding (the one before first flush)
        String xml = Files.readString(first, StandardCharsets.UTF_8);
        assertTrue(xml.contains("tests=\"1\""), "Only findings before first flush should be included");
    }

    @Test
    void flush_xmlEscapesSpecialChars() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("Detector<A>&B\"", "Normal report content");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("Detector&lt;A&gt;&amp;B&quot;"),
            "Special characters in detector name should be XML-escaped");
    }

    @Test
    void severityParsed_criticalInReport() {
        DetectorFinding finding = new DetectorFinding("D", "🔴 CRITICAL: deadlock imminent", 0L);
        assertEquals("CRITICAL", finding.severity);
    }

    @Test
    void severityParsed_highInReport() {
        DetectorFinding finding = new DetectorFinding("D", "🟠 HIGH: data corruption possible", 0L);
        assertEquals("HIGH", finding.severity);
    }

    @Test
    void severityParsed_mediumInReport() {
        DetectorFinding finding = new DetectorFinding("D", "🟡 MEDIUM: performance issue", 0L);
        assertEquals("MEDIUM", finding.severity);
    }

    @Test
    void severityParsed_lowInReport() {
        DetectorFinding finding = new DetectorFinding("D", "🟢 LOW: minor inefficiency", 0L);
        assertEquals("LOW", finding.severity);
    }

    @Test
    void severityParsed_unknownDefaultsToHigh() {
        DetectorFinding finding = new DetectorFinding("D", "Something went wrong", 0L);
        assertEquals("HIGH", finding.severity);
    }

    @Test
    void flush_xmlContainsSeverityInMessage() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onDetectorReport("DeadlockDetector", "🔴 CRITICAL: Thread deadlock detected");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("[CRITICAL]"),
            "Severity should appear in the failure message attribute");
    }
}
