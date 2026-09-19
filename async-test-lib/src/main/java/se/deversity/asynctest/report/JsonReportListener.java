package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.vibetags.annotations.AIInputSanitized;
import se.deversity.vibetags.annotations.AIInputSanitized.SanitizerType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * An {@link AsyncTestListener} that writes detector findings to a structured JSON report.
 *
 * <p>The JSON output is suitable for programmatic consumption by dashboards, alerting systems,
 * quality gates, or any toolchain that understands JSON but not JUnit XML. Findings include the
 * detector name, parsed {@link IssueSeverity}, full report text, and a Unix timestamp.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @BeforeAll
 * static void setup() {
 *     AsyncTestListenerRegistry.register(new JsonReportListener());
 * }
 * }</pre>
 *
 * <h3>Output format</h3>
 * <pre>{@code
 * {
 *   "asyncTestVersion": "1.12.1",
 *   "generatedAt": "2026-05-16T10:30:00Z",
 *   "totalFindings": 2,
 *   "findings": [
 *     {
 *       "detectorName": "FalseSharingDetector",
 *       "severity": "HIGH",
 *       "timestampMs": 1747382000000,
 *       "report": "..."
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>The default output path is {@code target/async-test-reports/async-test-report.json} (Maven)
 * or {@code build/async-test-reports/async-test-report.json} (Gradle).
 *
 * @see JUnitXmlReportListener
 */
@API(status = Status.STABLE)
public final class JsonReportListener implements AsyncTestListener {

    private static final String REPORT_FILENAME = "async-test-report.json";
    private static final String VERSION = ReportListeners.libraryVersion();

    private final ReportSink sink;

    /**
     * Creates a listener that auto-detects the output directory (Maven or Gradle build dir).
     * Registers a JVM shutdown hook to flush the report automatically.
     */
    public JsonReportListener() {
        this(ReportListeners.resolveDefaultOutputDir(), true);
    }

    /**
     * Creates a listener that writes to the given directory.
     * Registers a JVM shutdown hook to flush the report automatically.
     *
     * @param outputDir the directory to write the JSON report into
     */
    public JsonReportListener(String outputDir) {
        this(outputDir, true);
    }

    /**
     * Creates a JsonReportListener.
     *
     * @param outputDir           the directory to write the JSON report into
     * @param registerShutdownHook whether to register a JVM shutdown hook for auto-flush
     */
    public JsonReportListener(String outputDir, boolean registerShutdownHook) {
        this.sink = new ReportSink(outputDir, REPORT_FILENAME, "JSON", JsonReportListener::writeJson);
        if (registerShutdownHook) {
            sink.flushOnShutdown("async-test-json-report-flush");
        }
    }

    @Override
    public void onStructuredReport(String detectorName, IssueSeverity severity,
            @AIInputSanitized(SanitizerType.XSS) String report) {
        sink.add(detectorName, severity, report);
    }

    /**
     * Writes the accumulated findings to a JSON file.
     * Safe to call multiple times; the report is written only once.
     *
     * @return the path of the written report file, or {@code null} if there were no findings
     */
    public @Nullable Path flush() {
        return sink.flush();
    }

    /**
     * Returns the number of accumulated findings.
     *
     * @return the number of findings written so far
     */
    public int getFindingCount() {
        return sink.count();
    }

    private static void writeJson(Path jsonFile, List<DetectorFinding> snapshot) throws IOException {
        int count = snapshot.size();
        StringBuilder sb = new StringBuilder(Math.max(1024, count * 300));
        sb.append("{\n");
        sb.append("  \"asyncTestVersion\": ").append(JsonFormatter.jsonString(VERSION)).append(",\n");
        sb.append("  \"generatedAt\": ").append(JsonFormatter.jsonString(Instant.now().toString())).append(",\n");
        sb.append("  \"totalFindings\": ").append(count).append(",\n");
        sb.append("  \"findings\": [\n");

        for (int i = 0; i < count; i++) {
            DetectorFinding f = snapshot.get(i);
            sb.append("    {\n");
            sb.append("      \"detectorName\": ").append(JsonFormatter.jsonString(f.detectorName)).append(",\n");
            sb.append("      \"severity\": ").append(JsonFormatter.jsonString(f.severity.name())).append(",\n");
            sb.append("      \"timestampMs\": ").append(f.timestampMs).append(",\n");
            sb.append("      \"report\": ").append(JsonFormatter.jsonString(f.report)).append("\n");
            sb.append("    }");
            if (i < count - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");

        Files.writeString(jsonFile, sb.toString(), StandardCharsets.UTF_8);
    }
}
