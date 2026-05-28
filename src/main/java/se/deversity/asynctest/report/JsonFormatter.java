package se.deversity.asynctest.report;

import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;
import java.util.Map;

/**
 * Renders violations as a compact JSON array. No external dependency — the
 * record schema is small enough to hand-render and a CI pipeline can pipe
 * the output into jq, GitHub Code Scanning, etc.
 *
 * <pre>{@code
 * [
 *   {
 *     "detector": "SharedMessageDigest",
 *     "severity": "HIGH",
 *     "message": "'sha-attr' accessed from 2 threads ...",
 *     "sites": [{"class": "MyService", "method": "encrypt", "file": "MyService.java", "line": 42}],
 *     "attributes": {"threads": 2, "type": "MessageDigest"},
 *     "when": "2026-05-20T17:00:00Z"
 *   }
 * ]
 * }</pre>
 *
 * @since 1.6.0
 */
@AIPublicAPI
public final class JsonFormatter implements Formatter {

    @Override
    public String format(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("[");
        boolean firstV = true;
        for (Violation v : violations) {
            if (!firstV) sb.append(',');
            firstV = false;
            sb.append("\n  {");
            sb.append("\"detector\":").append(jsonString(v.detector())).append(',');
            sb.append("\"severity\":").append(jsonString(v.severity().name())).append(',');
            sb.append("\"message\":").append(jsonString(v.message())).append(',');
            sb.append("\"sites\":[");
            boolean firstS = true;
            for (var s : v.sites()) {
                if (!firstS) sb.append(',');
                firstS = false;
                sb.append("{\"class\":").append(jsonString(s.className()))
                  .append(",\"method\":").append(jsonString(s.methodName()))
                  .append(",\"file\":").append(jsonString(s.fileName()))
                  .append(",\"line\":").append(s.lineNumber()).append('}');
            }
            sb.append("],\"attributes\":");
            renderAttributes(sb, v.attributes());
            sb.append(",\"when\":").append(jsonString(v.when().toString()));
            sb.append('}');
        }
        sb.append("\n]");
        return sb.toString();
    }

    private static void renderAttributes(StringBuilder sb, Map<String, Object> attrs) {
        sb.append('{');
        boolean first = true;
        for (var e : attrs.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(renderValue(e.getValue()));
        }
        sb.append('}');
    }

    private static String renderValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return jsonString(v.toString());
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
