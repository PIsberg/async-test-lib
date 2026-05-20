package se.deversity.asynctest.report;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.SiteCapture;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FormattersTest {

    private static Violation sample() {
        return new Violation(
                "SharedMessageDigest",
                IssueSeverity.HIGH,
                "'sha256' accessed from 2 threads (T1, T2)",
                List.of(new SiteCapture.Site("com.acme.Svc", "encrypt", "Svc.java", 42)),
                Map.of("threads", 2, "type", "MessageDigest"),
                Instant.parse("2026-05-20T17:00:00Z"));
    }

    // ---- Violation record ----

    @Test
    void violation_rejectsBlankDetector() {
        assertThrows(IllegalArgumentException.class, () -> new Violation(
                "", IssueSeverity.HIGH, "msg", List.of(), Map.of(), Instant.now()));
    }

    @Test
    void violation_defaultsCollectionsAndTimestamp() {
        Violation v = new Violation(
                "X", IssueSeverity.HIGH, "msg", null, null, null);
        assertNotNull(v.sites());
        assertTrue(v.sites().isEmpty());
        assertNotNull(v.attributes());
        assertNotNull(v.when());
    }

    // ---- MarkdownFormatter ----

    @Test
    void markdown_emitsHeaderSitesAndAttrs() {
        String md = new MarkdownFormatter().format(List.of(sample()));
        assertTrue(md.contains("## AsyncTest Violations"));
        assertTrue(md.contains("### SharedMessageDigest [HIGH]"));
        assertTrue(md.contains("'sha256' accessed from 2 threads"));
        assertTrue(md.contains("`Svc.encrypt(Svc.java:42)`"));
        assertTrue(md.contains("threads: `2`"));
    }

    @Test
    void markdown_emptyListProducesEmptyString() {
        assertEquals("", new MarkdownFormatter().format(List.of()));
    }

    // ---- JsonFormatter ----

    @Test
    void json_emitsRequiredKeys() {
        String json = new JsonFormatter().format(List.of(sample()));
        assertTrue(json.startsWith("["));
        assertTrue(json.endsWith("]"));
        assertTrue(json.contains("\"detector\":\"SharedMessageDigest\""));
        assertTrue(json.contains("\"severity\":\"HIGH\""));
        assertTrue(json.contains("\"line\":42"));
        assertTrue(json.contains("\"when\":\"2026-05-20T17:00:00Z\""));
        assertTrue(json.contains("\"threads\":2"));
    }

    @Test
    void json_escapesSpecialCharactersInMessage() {
        Violation v = new Violation("X", IssueSeverity.HIGH,
                "line\nwith\"quotes\tand\\backslash",
                List.of(), Map.of(), Instant.now());
        String json = new JsonFormatter().format(List.of(v));
        assertTrue(json.contains("line\\nwith\\\"quotes\\tand\\\\backslash"),
                "Special chars must be escaped per JSON spec: " + json);
    }

    @Test
    void json_emptyListProducesEmptyArray() {
        String json = new JsonFormatter().format(List.of());
        assertEquals("[\n]", json);
    }
}
