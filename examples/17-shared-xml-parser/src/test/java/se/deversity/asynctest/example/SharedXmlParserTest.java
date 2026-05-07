package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the SharedXmlParserDetector (Phase 12).
 *
 * ============================================================
 * NOTE: SharedXmlParserDetector ships in async-test-lib 0.10.0.
 * This example targets 0.9.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: An XML service holds a single shared DocumentBuilder.
 * DocumentBuilder is stateful and NOT thread-safe: concurrent parse()
 * calls share internal SAX parser state (entity resolver, error handler,
 * locator) and produce corrupted Documents or throw unexpected exceptions.
 *
 * WHY @Test PASSES: Sequential parsing means only one thread uses the
 * DocumentBuilder at a time — no concurrent state corruption.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector tracks which
 * threads access each parser instance and reports when multiple threads
 * share one instance.
 */
class SharedXmlParserTest {

    private static final String XML_TEMPLATE = "<item><id>%d</id><value>%s</value></item>";

    private DocumentBuilder sharedBuilder;

    @BeforeEach
    void setUp() throws Exception {
        sharedBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    }

    static class XmlProcessor {
        private final DocumentBuilder builder;

        XmlProcessor(DocumentBuilder builder) { this.builder = builder; }

        Document parse(String xml) throws Exception {
            return builder.parse(new InputSource(new StringReader(xml)));
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_parseXml_singleThread() throws Exception {
        XmlProcessor proc = new XmlProcessor(sharedBuilder);
        String xml = String.format(XML_TEMPLATE, 1, "hello");
        Document doc = proc.parse(xml);
        assertNotNull(doc.getElementsByTagName("id").item(0));
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectSharedXmlParser = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectSharedParser_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.sharedXmlParserDetector();
        //   d.recordAccess(sharedBuilder, "DocumentBuilder", Thread.currentThread());
        //   sharedBuilder.parse(...); // shared instance — flagged!
        //
        // The detector will report "'DocumentBuilder' instance accessed from N threads
        // — XML parsers are not thread-safe; concurrent use causes corrupted parse
        // results or ConcurrentModificationExceptions."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — new DocumentBuilder per thread via ThreadLocal
    // =========================================================================

    private static final ThreadLocal<DocumentBuilder> THREAD_LOCAL_BUILDER =
            ThreadLocal.withInitial(() -> {
                try { return DocumentBuilderFactory.newInstance().newDocumentBuilder(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });

    @Test
    void part3_fixed_threadLocalBuilder() throws Exception {
        DocumentBuilder localBuilder = THREAD_LOCAL_BUILDER.get();
        String xml = String.format(XML_TEMPLATE, 1, "hello");
        Document doc = localBuilder.parse(new InputSource(new StringReader(xml)));
        assertNotNull(doc.getElementsByTagName("id").item(0));
    }
}
