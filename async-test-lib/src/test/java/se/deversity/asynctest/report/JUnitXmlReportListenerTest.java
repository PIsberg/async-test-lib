package se.deversity.asynctest.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
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
    void onStructuredReport_incrementsFindingCount() {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("FalseSharingDetector", IssueSeverity.HIGH, "False sharing detected");
        listener.onStructuredReport("DeadlockDetector", IssueSeverity.CRITICAL, "Deadlock detected");
        assertEquals(2, listener.getFindingCount());
    }

    @Test
    void flush_writesXmlFile() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("FalseSharingDetector", IssueSeverity.HIGH,
            "False sharing detected in field 'counter'");

        Path result = listener.flush();

        assertNotNull(result, "flush() should return the written file path");
        assertTrue(Files.exists(result), "XML report file should exist on disk");
    }

    @Test
    void flush_xmlContainsTestsuite() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("VisibilityMonitor", IssueSeverity.HIGH, "Visibility issue detected");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("<testsuite"), "XML should contain <testsuite>");
        assertTrue(xml.contains("tests=\"1\""), "XML should report 1 test");
        assertTrue(xml.contains("failures=\"1\""), "XML should report 1 failure");
    }

    @Test
    void flush_xmlContainsDetectorName() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("ReentrantLockDetector", IssueSeverity.HIGH, "Lock acquired unfairly");

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
        listener.onStructuredReport("LockOrderValidator", IssueSeverity.HIGH, report);

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains(report), "XML should contain the full report text in CDATA");
    }

    @Test
    void flush_multipleFindings_allIncluded() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("DetectorA", IssueSeverity.HIGH, "Report A");
        listener.onStructuredReport("DetectorB", IssueSeverity.MEDIUM, "Report B");
        listener.onStructuredReport("DetectorC", IssueSeverity.LOW, "Report C");

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
        listener.onStructuredReport("SomeDetector", IssueSeverity.HIGH, "Some report");

        Path first = listener.flush();
        assertNotNull(first);

        listener.onStructuredReport("AnotherDetector", IssueSeverity.MEDIUM, "Another report");
        Path second = listener.flush();
        assertNull(second, "Second flush() call should be a no-op");

        // File should contain only 1 finding (the one before first flush)
        String xml = Files.readString(first, StandardCharsets.UTF_8);
        assertTrue(xml.contains("tests=\"1\""), "Only findings before first flush should be included");
    }

    @Test
    void flush_xmlEscapesSpecialChars() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("Detector<A>&B\"", IssueSeverity.HIGH, "Normal report content");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("Detector&lt;A&gt;&amp;B&quot;"),
            "Special characters in detector name should be XML-escaped");
    }

    @Test
    void flush_reportWithCdataTerminator_cannotBreakOutOfCdata() throws Exception {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        // Simulates a thread name (runtime-controlled by code under test) crafted to close the
        // CDATA section early and inject a forged passing test case into the CI-consumed report.
        String malicious = "state]]></failure></testcase>"
                         + "<testcase name=\"forged.SecurityTest\" time=\"0.000\"/>"
                         + "<testcase name=\"x\"><failure><![CDATA[rest";
        listener.onStructuredReport("SharedMessageDigestDetector", IssueSeverity.HIGH, malicious);

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        // Parse the emitted report the way a CI JUnit consumer would. The forged breakout must
        // remain inert CDATA text, so the document must contain exactly one <testcase> element.
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, doc.getElementsByTagName("testcase").getLength(),
            "Exactly one <testcase> must exist; the injected report text must not forge more");
        assertEquals(0, doc.getElementsByTagName("forged").getLength(),
            "No forged element name attribute should ever become a real element");
        assertTrue(malicious.contains("]]>") && !xml.contains("]]></failure></testcase><testcase"),
            "The raw CDATA terminator must be neutralized, not emitted verbatim");
    }

    @Test
    void fromReport_critical() {
        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport("🔴 CRITICAL: deadlock imminent"));
    }

    @Test
    void fromReport_high() {
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport("🟠 HIGH: data corruption possible"));
    }

    @Test
    void fromReport_medium() {
        assertEquals(IssueSeverity.MEDIUM, IssueSeverity.fromReport("🟡 MEDIUM: performance issue"));
    }

    @Test
    void fromReport_low() {
        assertEquals(IssueSeverity.LOW, IssueSeverity.fromReport("🟢 LOW: minor inefficiency"));
    }

    @Test
    void fromReport_unknownDefaultsToHigh() {
        assertEquals(IssueSeverity.HIGH, IssueSeverity.fromReport("Something went wrong"));
    }

    @Test
    void flush_xmlContainsSeverityInMessage() throws IOException {
        JUnitXmlReportListener listener = new JUnitXmlReportListener(tempDir.toString(), false);
        listener.onStructuredReport("DeadlockDetector", IssueSeverity.CRITICAL,
            "🔴 CRITICAL: Thread deadlock detected");

        Path result = listener.flush();
        String xml = Files.readString(result, StandardCharsets.UTF_8);

        assertTrue(xml.contains("[CRITICAL]"),
            "Severity should appear in the failure message attribute");
    }
}
