package se.deversity.asynctest.intellij.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON parser for async-test-report.json.
 *
 * <p>Uses hand-written parsing rather than a JSON library to avoid adding a
 * runtime dependency to the plugin. The report format is simple and stable.
 */
public final class JsonReportParser {

    private JsonReportParser() {}

    /**
     * Parses the async-test JSON report at the given path.
     *
     * @param reportFile path to async-test-report.json
     * @return list of findings; empty if the file is absent, empty, or malformed
     */
    public static List<DetectorFinding> parse(Path reportFile) {
        if (reportFile == null || !Files.exists(reportFile)) {
            return List.of();
        }
        try {
            String content = Files.readString(reportFile, StandardCharsets.UTF_8);
            return parseFindings(content);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<DetectorFinding> parseFindings(String json) {
        List<DetectorFinding> results = new ArrayList<>();
        int findingsStart = json.indexOf("\"findings\"");
        if (findingsStart < 0) return results;

        int arrayStart = json.indexOf('[', findingsStart);
        int arrayEnd   = json.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < arrayStart) return results;

        String findingsArray = json.substring(arrayStart + 1, arrayEnd);
        // Split on object boundaries — each finding is a { ... } block. Braces inside a string
        // are report text, not structure, so they must not move the depth.
        int depth = 0;
        int objStart = -1;
        boolean inString = false;
        for (int i = 0; i < findingsArray.length(); i++) {
            char c = findingsArray.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = findingsArray.substring(objStart, i + 1);
                    DetectorFinding f = parseFinding(obj);
                    if (f != null) results.add(f);
                    objStart = -1;
                }
            }
        }
        return results;
    }

    private static DetectorFinding parseFinding(String obj) {
        String name      = extractString(obj, "detectorName");
        String severity  = extractString(obj, "severity");
        String report    = extractString(obj, "report");
        long   timestamp = extractLong(obj, "timestampMs");

        if (name == null) return null;
        return new DetectorFinding(
            name,
            DetectorFinding.Severity.parse(severity),
            report != null ? report : "",
            timestamp);
    }

    private static String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return null;

        int colon = json.indexOf(':', keyIdx + search.length());
        if (colon < 0) return null;

        // Skip whitespace after colon
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;

        // Read until closing quote, handling escapes
        StringBuilder sb = new StringBuilder();
        int i = valueStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                int consumed = 2;
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        // The writer emits every control character below 0x20 in this form.
                        int code = i + 6 <= json.length() ? hex4(json, i + 2) : -1;
                        if (code >= 0) {
                            sb.append((char) code);
                            consumed = 6;
                        } else {
                            sb.append(next);
                        }
                    }
                    default   -> sb.append(next);
                }
                i += consumed;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** The four hex digits at {@code from} as a code unit, or -1 if any is not a hex digit. */
    private static int hex4(String json, int from) {
        int code = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(json.charAt(i), 16);
            if (digit < 0) return -1;
            code = code * 16 + digit;
        }
        return code;
    }

    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\"";
        int keyIdx = json.indexOf(search);
        if (keyIdx < 0) return 0L;

        int colon = json.indexOf(':', keyIdx + search.length());
        if (colon < 0) return 0L;

        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        if (valueEnd == valueStart) return 0L;
        try {
            return Long.parseLong(json.substring(valueStart, valueEnd));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
