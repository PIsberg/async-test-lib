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
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link AsyncTestListener} that writes detector findings to a JUnit-compatible XML report.
 *
 * <p>CI systems (GitHub Actions, Jenkins, GitLab CI) parse JUnit XML to surface test failures
 * in their dashboards. Registering this listener ensures every concurrency finding appears
 * as a named test case failure visible in the CI run, not just as stderr noise.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @BeforeAll
 * static void setup() {
 *     AsyncTestListenerRegistry.register(new JUnitXmlReportListener());
 * }
 * }</pre>
 *
 * <p>The report is written to {@code target/async-test-reports/TEST-AsyncTestConcurrencyReport.xml}
 * (Maven) or {@code build/async-test-reports/TEST-AsyncTestConcurrencyReport.xml} (Gradle)
 * when the JVM shuts down, or immediately via {@link #flush()}.
 *
 * <h3>GitHub Actions integration</h3>
 * <pre>{@code
 * - name: Upload async-test detector reports
 *   uses: actions/upload-artifact@v4
 *   if: always()
 *   with:
 *     name: async-test-reports
 *     path: target/async-test-reports/
 * }</pre>
 *
 * @see StrictModeListener
 */
@API(status = Status.STABLE)
public final class JUnitXmlReportListener implements AsyncTestListener {

    private static final String REPORT_FILENAME = "TEST-AsyncTestConcurrencyReport.xml";

    private final List<DetectorFinding> findings = new CopyOnWriteArrayList<>();
    private final String outputDir;
    private final AtomicBoolean flushed = new AtomicBoolean(false);

    /**
     * Creates a listener that auto-detects the output directory (Maven or Gradle build dir).
     * Registers a JVM shutdown hook to flush the report automatically.
     */
    public JUnitXmlReportListener() {
        this(ReportListeners.resolveDefaultOutputDir(), true);
    }

    /**
     * Creates a listener that writes to the given directory.
     * Registers a JVM shutdown hook to flush the report automatically.
     *
     * @param outputDir the directory to write the XML report into
     */
    public JUnitXmlReportListener(String outputDir) {
        this(outputDir, true);
    }

    /**
     * @param outputDir            the directory to write the XML report into
     * @param registerShutdownHook whether to register a JVM shutdown hook for auto-flush
     */
    public JUnitXmlReportListener(String outputDir, boolean registerShutdownHook) {
        this.outputDir = outputDir;
        if (registerShutdownHook) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(this::flush, "async-test-xml-report-flush"));
        }
    }

    @Override
    public void onStructuredReport(String detectorName, IssueSeverity severity,
            @AIInputSanitized(SanitizerType.XSS) String report) {
        findings.add(new DetectorFinding(detectorName, severity, report, System.currentTimeMillis()));
    }

    /**
     * Writes the accumulated findings to a JUnit XML file.
     * Safe to call multiple times; the report is written only once.
     *
     * @return the path of the written report file, or {@code null} if there were no findings
     */
    public @Nullable Path flush() {
        if (findings.isEmpty() || !flushed.compareAndSet(false, true)) {
            return null;
        }
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            Path xmlFile = dir.resolve(REPORT_FILENAME);
            writeXml(xmlFile, List.copyOf(findings));
            return xmlFile;
        } catch (IOException e) {
            System.err.println("async-test: Failed to write JUnit XML report: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the number of accumulated findings (useful for assertions in tests of this listener).
     *
     * @return the get finding count
     */
    public int getFindingCount() {
        return findings.size();
    }

    private static void writeXml(Path xmlFile, List<DetectorFinding> snapshot) throws IOException {
        int count = snapshot.size();
        StringBuilder sb = new StringBuilder(Math.max(1024, count * 300));
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"AsyncTest Concurrency Detector Report\"")
          .append(" tests=\"").append(count).append("\"")
          .append(" failures=\"").append(count).append("\"")
          .append(" errors=\"0\" skipped=\"0\" time=\"0.000\"")
          .append(" timestamp=\"").append(Instant.now()).append("\">\n");

        for (DetectorFinding f : snapshot) {
            String message = "[" + f.severity.name() + "] " + xmlEscape(f.detectorName)
                           + " detected a concurrency issue";
            sb.append("  <testcase name=\"").append(xmlEscape(f.detectorName))
              .append("\" classname=\"se.deversity.asynctest.detectors\"")
              .append(" time=\"0.000\">\n");
            sb.append("    <failure message=\"").append(xmlEscape(message))
              .append("\" type=\"ConcurrencyIssueDetected\"><![CDATA[")
              .append(cdataEscape(f.report))
              .append("]]></failure>\n");
            sb.append("  </testcase>\n");
        }
        sb.append("</testsuite>\n");

        Files.writeString(xmlFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Neutralizes the CDATA terminator sequence {@code ]]>} inside untrusted report text so it
     * cannot close the CDATA section early and inject arbitrary XML into the report. The report
     * carries runtime-captured data (e.g. thread names) that the code under test controls, so it
     * must not be trusted to be free of {@code ]]>}. The standard trick splits the terminator
     * across two CDATA sections, leaving the rendered text unchanged.
     */
    private static String cdataEscape(String report) {
        return report.replace("]]>", "]]]]><![CDATA[>");
    }
}
