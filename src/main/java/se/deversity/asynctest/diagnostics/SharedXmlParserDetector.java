package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects XML parser instances shared across multiple threads.
 *
 * <p>The following types are <strong>not thread-safe</strong>:
 * {@link javax.xml.parsers.DocumentBuilder},
 * {@link javax.xml.parsers.SAXParser},
 * {@link javax.xml.transform.Transformer},
 * {@link javax.xml.xpath.XPath}.
 * Sharing a single instance across threads causes corrupted parse results,
 * wrong XPath evaluations, or {@link java.util.ConcurrentModificationException}s
 * that are difficult to reproduce and diagnose.
 *
 * <p>The corresponding factory classes ({@code DocumentBuilderFactory},
 * {@code SAXParserFactory}, {@code TransformerFactory}, {@code XPathFactory})
 * are thread-safe for {@code newXxx()} calls and can be shared freely.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedXmlParserDetector();
 * d.recordAccess(documentBuilder, "DocumentBuilder", Thread.currentThread());
 * Document doc = documentBuilder.parse(inputStream);
 * }</pre>
 *
 * @since 0.10.0
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedXmlParserDetectorTest.java"
)
public class SharedXmlParserDetector {

    private static class ParserState {
        final String      parserType;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        ParserState(String parserType) { this.parserType = parserType; }
    }

    private final Map<Integer, ParserState> parsers = new ConcurrentHashMap<>();

    /**
     * Records an access to an XML parser instance.
     *
     * @param parser     the parser instance (null-safe)
     * @param parserType descriptive type name, e.g. {@code "DocumentBuilder"},
     *                   {@code "SAXParser"}, {@code "Transformer"}, {@code "XPath"}
     * @param thread     the accessing thread (null-safe)
     */
    public void recordAccess(Object parser, String parserType, Thread thread) {
        if (parser == null || thread == null) return;
        String label = parserType != null ? parserType
                : parser.getClass().getSimpleName();
        ParserState s = parsers.computeIfAbsent(
                System.identityHashCode(parser), id -> new ParserState(label));
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
    }

    /** {@return report of XML parser instances accessed from multiple threads} */
    public SharedXmlParserReport analyze() {
        SharedXmlParserReport r = new SharedXmlParserReport();
        for (ParserState s : parsers.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "'%s' instance accessed from %d threads (%s) — "
                                + "XML parsers are not thread-safe; concurrent use causes "
                                + "corrupted parse results or ConcurrentModificationExceptions",
                        s.parserType, s.accessingThreadIds.size(),
                        String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedXmlParserReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED XML PARSER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: XML parsers (DocumentBuilder, SAXParser, Transformer) maintain mutable internal state during\n" +
                       "       parsing. Concurrent use corrupts that state, producing wrong parse results, missed elements, or\n" +
                       "       parser exceptions that vary by thread scheduling.\n" +
                       "  Fix: Create a new parser instance per call — DocumentBuilderFactory.newDocumentBuilder() is the correct pattern:\n" +
                       "       DocumentBuilder db = factory.newDocumentBuilder(); // factory is thread-safe; builder is not\n" +
                       "       Or use ThreadLocal<DocumentBuilder> to amortise construction cost across repeated calls.");
            return sb.toString();
        }
    }
}
