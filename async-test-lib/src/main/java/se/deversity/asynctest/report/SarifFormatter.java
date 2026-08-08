package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.SiteCapture;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders findings as SARIF 2.1.0, the format GitHub code scanning, Azure DevOps, GitLab and
 * SonarQube all ingest.
 *
 * <p>The other formatters produce something a person reads. This one produces something a
 * platform reads: upload the output to GitHub's {@code codeql-action/upload-sarif} and concurrency
 * findings appear in the Security tab next to CodeQL's, annotated on the pull request diff, with
 * the same triage and dismissal workflow a team already has. That matters more than the format
 * itself — a finding in a build log is read once by whoever broke the build, and a finding in the
 * code-scanning UI is visible to everyone until someone deals with it.
 *
 * <p><strong>Severity mapping.</strong> SARIF has three levels where this library has four, and
 * the mapping is deliberately conservative at the bottom end:
 *
 * <table border="1">
 * <caption>Severity mapping</caption>
 * <tr><th>{@link IssueSeverity}</th><th>SARIF level</th><th>security-severity</th></tr>
 * <tr><td>CRITICAL</td><td>{@code error}</td><td>9.0</td></tr>
 * <tr><td>HIGH</td><td>{@code error}</td><td>7.0</td></tr>
 * <tr><td>MEDIUM</td><td>{@code warning}</td><td>5.0</td></tr>
 * <tr><td>LOW</td><td>{@code note}</td><td>3.0</td></tr>
 * </table>
 *
 * <p>MEDIUM maps to {@code warning} rather than {@code error} on purpose. That is the tier the
 * access-pattern detectors report on correct-but-shared code, and a tool that fails a merge over
 * something it cannot prove gets uninstalled. See
 * {@code docs/DETECTOR_CATALOG.md} on trust tiers.
 *
 * <p><strong>Locations.</strong> A finding carries a location only when the detector captured
 * one. A concurrency bug's location is genuinely ambiguous — the interleaving involves at least
 * two sites — so the first captured site becomes the SARIF location and the rest are attached as
 * related locations. Findings with no captured site are emitted without a location rather than
 * being pinned to an arbitrary file, which would put an annotation on a line that is not the
 * problem.
 *
 * <p>Usage:
 * <pre>{@code
 * String sarif = new SarifFormatter().format(violations);
 * Files.writeString(Path.of("target/async-test.sarif"), sarif);
 * }</pre>
 * then in the workflow:
 * <pre>{@code
 * - uses: github/codeql-action/upload-sarif@v3
 *   with:
 *     sarif_file: target/async-test.sarif
 *     category: async-test
 * }</pre>
 *
 * @since 1.8.0
 */
@AIPublicAPI
@API(status = Status.EXPERIMENTAL)
public final class SarifFormatter implements Formatter {

    private static final String SCHEMA =
            "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/sarif-2.1/schema/sarif-schema-2.1.0.json";
    private static final String TOOL_NAME = "async-test-lib";
    private static final String INFO_URI = "https://github.com/PIsberg/async-test-lib";

    private final String version;

    /** Creates a formatter that reports the library version from the runtime package metadata. */
    public SarifFormatter() {
        this(versionFromPackage());
    }

    /**
     * Creates a formatter reporting a fixed version, which keeps the output byte-stable for
     * tests that would otherwise depend on how the classes were packaged.
     *
     * @param version the version string to report as the tool version
     */
    public SarifFormatter(String version) {
        this.version = version == null ? "unknown" : version;
    }

    private static String versionFromPackage() {
        Package p = SarifFormatter.class.getPackage();
        String v = (p == null) ? null : p.getImplementationVersion();
        return v == null ? "unknown" : v;
    }

    @Override
    public String format(List<Violation> violations) {
        List<Violation> safe = (violations == null) ? List.of() : violations;

        // One rule per detector, in first-seen order, so the SARIF run declares every rule it
        // references. Emitting a result whose ruleId has no matching rule is the most common way
        // to have an upload rejected.
        Map<String, Violation> rules = new LinkedHashMap<>();
        for (Violation v : safe) rules.putIfAbsent(v.detector(), v);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"$schema\": ").append(json(SCHEMA)).append(",\n");
        sb.append("  \"version\": \"2.1.0\",\n");
        sb.append("  \"runs\": [\n    {\n");
        appendTool(sb, rules.values());
        sb.append(",\n");
        appendResults(sb, safe);
        sb.append("\n    }\n  ]\n}\n");
        return sb.toString();
    }

    private void appendTool(StringBuilder sb, Iterable<Violation> firstPerDetector) {
        sb.append("      \"tool\": {\n");
        sb.append("        \"driver\": {\n");
        sb.append("          \"name\": ").append(json(TOOL_NAME)).append(",\n");
        sb.append("          \"version\": ").append(json(version)).append(",\n");
        sb.append("          \"informationUri\": ").append(json(INFO_URI)).append(",\n");
        sb.append("          \"rules\": [");

        boolean first = true;
        for (Violation v : firstPerDetector) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("            {\n");
            sb.append("              \"id\": ").append(json(v.detector())).append(",\n");
            sb.append("              \"name\": ").append(json(v.detector())).append(",\n");
            sb.append("              \"shortDescription\": { \"text\": ")
              .append(json(v.detector() + " reported a concurrency finding")).append(" },\n");
            sb.append("              \"defaultConfiguration\": { \"level\": ")
              .append(json(level(v.severity()))).append(" },\n");
            sb.append("              \"properties\": {\n");
            sb.append("                \"security-severity\": ")
              .append(json(securitySeverity(v.severity()))).append(",\n");
            sb.append("                \"tags\": [\"concurrency\", \"async-test\"]\n");
            sb.append("              }\n");
            sb.append("            }");
        }
        sb.append(first ? "]\n" : "\n          ]\n");
        sb.append("        }\n      }");
    }

    private void appendResults(StringBuilder sb, List<Violation> violations) {
        sb.append("      \"results\": [");
        boolean first = true;
        for (Violation v : violations) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("        {\n");
            sb.append("          \"ruleId\": ").append(json(v.detector())).append(",\n");
            sb.append("          \"level\": ").append(json(level(v.severity()))).append(",\n");
            sb.append("          \"message\": { \"text\": ").append(json(v.message())).append(" },\n");
            appendLocations(sb, v.sites());
            sb.append("          \"properties\": {\n");
            sb.append("            \"severity\": ").append(json(v.severity().name())).append(",\n");
            sb.append("            \"detectedAt\": ").append(json(String.valueOf(v.when()))).append('\n');
            sb.append("          }\n");
            sb.append("        }");
        }
        sb.append(first ? "]" : "\n      ]");
    }

    /**
     * Emits the first captured site as the result location and the rest as related locations.
     * A finding with no captured site gets an empty {@code locations} array, which SARIF permits
     * and which consumers render as a run-level finding rather than a file annotation.
     */
    private void appendLocations(StringBuilder sb, List<SiteCapture.Site> sites) {
        List<SiteCapture.Site> usable = new ArrayList<>();
        if (sites != null) {
            for (SiteCapture.Site s : sites) {
                if (s != null && s.fileName() != null && !s.fileName().isBlank()) usable.add(s);
            }
        }
        if (usable.isEmpty()) {
            sb.append("          \"locations\": [],\n");
            return;
        }
        sb.append("          \"locations\": [\n");
        appendPhysicalLocation(sb, usable.get(0), "            ");
        sb.append("\n          ],\n");

        if (usable.size() > 1) {
            sb.append("          \"relatedLocations\": [\n");
            for (int i = 1; i < usable.size(); i++) {
                appendPhysicalLocation(sb, usable.get(i), "            ");
                sb.append(i < usable.size() - 1 ? ",\n" : "\n");
            }
            sb.append("          ],\n");
        }
    }

    private void appendPhysicalLocation(StringBuilder sb, SiteCapture.Site site, String indent) {
        sb.append(indent).append("{ \"physicalLocation\": { \"artifactLocation\": { \"uri\": ")
          .append(json(uriFor(site)))
          .append(" }, \"region\": { \"startLine\": ")
          .append(Math.max(1, site.lineNumber()))
          .append(" } } }");
    }

    /**
     * Best-effort source path. A {@code StackTraceElement} gives a class name and a simple file
     * name, not a repository path; deriving the directory from the package is the closest a
     * runtime detector can get, and consumers that cannot match it fall back to a run-level
     * finding rather than failing the upload.
     */
    private static String uriFor(SiteCapture.Site site) {
        String cls = site.className();
        String file = site.fileName();
        if (cls == null || cls.isBlank() || !cls.contains(".")) return file;
        String pkgPath = cls.substring(0, cls.lastIndexOf('.')).replace('.', '/');
        return pkgPath + '/' + file;
    }

    private static String level(IssueSeverity severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW -> "note";
        };
    }

    private static String securitySeverity(IssueSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "9.0";
            case HIGH -> "7.0";
            case MEDIUM -> "5.0";
            case LOW -> "3.0";
        };
    }

    /** JSON string escaping, matching {@link JsonFormatter}'s rules so the two agree. */
    private static String json(String raw) {
        if (raw == null) return "null";
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
