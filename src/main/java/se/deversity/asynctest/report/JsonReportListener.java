package se.deversity.asynctest.report;

import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link AsyncTestListener} that writes detector findings to a structured JSON report.
 *
 * <p>The JSON output is suitable for programmatic consumption by dashboards, alerting systems,
 * quality gates, or any toolchain that understands JSON but not JUnit XML. Findings include the
 * detector name, parsed {@link IssueSeverity}, full report text, and a Unix timestamp.
 *
 * <h3>Usage</h3>
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
 *   "asyncTestVersion": "1.6.0",
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
public final class JsonReportListener implements AsyncTestListener {

    private static final String REPORT_FILENAME = "async-test-report.json";
    private static final String VERSION = "1.6.0";

    private final List<DetectorFinding> findings = new CopyOnWriteArrayList<>();
    private final String outputDir;
    private final AtomicBoolean flushed = new AtomicBoolean(false);

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
     * @param outputDir           the directory to write the JSON report into
     * @param registerShutdownHook whether to register a JVM shutdown hook for auto-flush
     */
    public JsonReportListener(String outputDir, boolean registerShutdownHook) {
        this.outputDir = outputDir;
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(this::flush, "async-test-json-report-flush"));
        }
    }

    @Override
    public void onStructuredReport(String detectorName, IssueSeverity severity, String report) {
        findings.add(new DetectorFinding(detectorName, severity, report, System.currentTimeMillis()));
    }

    /**
     * Writes the accumulated findings to a JSON file.
     * Safe to call multiple times; the report is written only once.
     *
     * @return the path of the written report file, or {@code null} if there were no findings
     */
    public Path flush() {
        if (findings.isEmpty() || !flushed.compareAndSet(false, true)) {
            return null;
        }
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Path jsonFile = dir.resolve(REPORT_FILENAME);
            writeJson(jsonFile, List.copyOf(findings));
            return jsonFile;
        } catch (IOException e) {
            System.err.println("async-test: Failed to write JSON report: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the number of accumulated findings.
     */
    public int getFindingCount() {
        return findings.size();
    }

    private static void writeJson(Path jsonFile, List<DetectorFinding> snapshot) throws IOException {
        int count = snapshot.size();
        StringBuilder sb = new StringBuilder(Math.max(1024, count * 300));
        sb.append("{\n");
        sb.append("  \"asyncTestVersion\": ").append(jsonString(VERSION)).append(",\n");
        sb.append("  \"generatedAt\": ").append(jsonString(Instant.now().toString())).append(",\n");
        sb.append("  \"totalFindings\": ").append(count).append(",\n");
        sb.append("  \"findings\": [\n");

        for (int i = 0; i < count; i++) {
            DetectorFinding f = snapshot.get(i);
            sb.append("    {\n");
            sb.append("      \"detectorName\": ").append(jsonString(f.detectorName)).append(",\n");
            sb.append("      \"severity\": ").append(jsonString(f.severity.name())).append(",\n");
            sb.append("      \"timestampMs\": ").append(f.timestampMs).append(",\n");
            sb.append("      \"report\": ").append(jsonString(f.report)).append("\n");
            sb.append("    }");
            if (i < count - 1) sb.append(",");
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");

        Files.writeString(jsonFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
