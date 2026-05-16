package se.deversity.asynctest.intellij.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonReportParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parse_nullPath_returnsEmpty() {
        List<DetectorFinding> findings = JsonReportParser.parse(null);
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_nonExistentFile_returnsEmpty() {
        List<DetectorFinding> findings = JsonReportParser.parse(tempDir.resolve("missing.json"));
        assertTrue(findings.isEmpty());
    }

    @Test
    void parse_emptyJson_returnsEmpty() throws IOException {
        Path file = writeJson("{}");
        assertTrue(JsonReportParser.parse(file).isEmpty());
    }

    @Test
    void parse_singleFinding_parsesDetectorName() throws IOException {
        Path file = writeJson("""
            {
              "asyncTestVersion": "1.5.0",
              "generatedAt": "2026-05-16T10:00:00Z",
              "totalFindings": 1,
              "findings": [
                {
                  "detectorName": "FalseSharingDetector",
                  "severity": "HIGH",
                  "timestampMs": 1747382000000,
                  "report": "False sharing detected on field counter"
                }
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals(1, findings.size());
        assertEquals("FalseSharingDetector", findings.get(0).detectorName);
    }

    @Test
    void parse_singleFinding_parsesSeverity() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {
                  "detectorName": "DeadlockDetector",
                  "severity": "CRITICAL",
                  "timestampMs": 0,
                  "report": "Deadlock"
                }
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals(DetectorFinding.Severity.CRITICAL, findings.get(0).severity);
    }

    @Test
    void parse_singleFinding_parsesReport() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {
                  "detectorName": "D",
                  "severity": "LOW",
                  "timestampMs": 0,
                  "report": "Thread t1 violated lock order"
                }
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals("Thread t1 violated lock order", findings.get(0).report);
    }

    @Test
    void parse_singleFinding_parsesTimestamp() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {
                  "detectorName": "D",
                  "severity": "HIGH",
                  "timestampMs": 1234567890,
                  "report": "r"
                }
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals(1234567890L, findings.get(0).timestampMs);
    }

    @Test
    void parse_multipleFindings_allParsed() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {"detectorName": "A", "severity": "HIGH",   "timestampMs": 1, "report": "r1"},
                {"detectorName": "B", "severity": "MEDIUM", "timestampMs": 2, "report": "r2"},
                {"detectorName": "C", "severity": "LOW",    "timestampMs": 3, "report": "r3"}
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals(3, findings.size());
        assertEquals("A", findings.get(0).detectorName);
        assertEquals("B", findings.get(1).detectorName);
        assertEquals("C", findings.get(2).detectorName);
    }

    @Test
    void parse_unknownSeverity_defaultsToUnknown() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {"detectorName": "D", "severity": "VERY_BAD", "timestampMs": 0, "report": "r"}
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals(DetectorFinding.Severity.UNKNOWN, findings.get(0).severity);
    }

    @Test
    void parse_escapedQuotesInReport_parsedCorrectly() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {"detectorName": "D", "severity": "HIGH", "timestampMs": 0, "report": "Field \\"counter\\" is not volatile"}
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertEquals("Field \"counter\" is not volatile", findings.get(0).report);
    }

    @Test
    void parse_escapedNewlineInReport_parsedCorrectly() throws IOException {
        Path file = writeJson("""
            {
              "findings": [
                {"detectorName": "D", "severity": "HIGH", "timestampMs": 0, "report": "Line 1\\nLine 2"}
              ]
            }
            """);

        List<DetectorFinding> findings = JsonReportParser.parse(file);
        assertTrue(findings.get(0).report.contains("\n"),
            "Escaped \\n should be decoded to a real newline");
    }

    @Test
    void severityParse_allKnownValues() {
        assertEquals(DetectorFinding.Severity.CRITICAL, DetectorFinding.Severity.parse("CRITICAL"));
        assertEquals(DetectorFinding.Severity.HIGH,     DetectorFinding.Severity.parse("HIGH"));
        assertEquals(DetectorFinding.Severity.MEDIUM,   DetectorFinding.Severity.parse("MEDIUM"));
        assertEquals(DetectorFinding.Severity.LOW,      DetectorFinding.Severity.parse("LOW"));
        assertEquals(DetectorFinding.Severity.UNKNOWN,  DetectorFinding.Severity.parse(null));
        assertEquals(DetectorFinding.Severity.UNKNOWN,  DetectorFinding.Severity.parse("INVALID"));
    }

    private Path writeJson(String content) throws IOException {
        Path file = tempDir.resolve("async-test-report.json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
