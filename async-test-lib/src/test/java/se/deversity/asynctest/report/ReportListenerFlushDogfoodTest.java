package se.deversity.asynctest.report;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dogfoods the one-shot flush in {@link JsonReportListener} and {@link JUnitXmlReportListener}
 * with {@code @AsyncTest}.
 *
 * <p>Why this exists: both listeners are registered process-wide and receive
 * {@code onStructuredReport} from the racing workers, and both promise that {@code flush()} is
 * safe to call repeatedly because the report is written only once. That promise rests on a single
 * {@code compareAndSet} on an {@code AtomicBoolean}, and a shutdown hook can call {@code flush()}
 * while a test is calling it too. If the guard degraded to a read followed by a write, several
 * callers would pass it together and race to write the same file, so a reader could find the
 * report truncated or interleaved. Nothing in a straight-line test reaches that: the guard is
 * one-shot, so a second call in the same thread always takes the closed branch.
 *
 * <p>So each round gets its own listener and every worker in the round records a finding and then
 * flushes. Exactly one flush per round may report that it wrote, which is what the
 * {@code compareAndSet} is for, and the file it names must exist. The listeners are built with
 * {@code registerShutdownHook = false}: this test creates one per round, and registering a hook
 * for each would leak them for the life of the JVM.
 *
 * <p>Findings are counted too. Every worker records one before flushing, so a listener that ends a
 * round holding fewer than the workers that fed it lost one on the way in.
 */
class ReportListenerFlushDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 50;

    @TempDir
    static Path reportDir;

    private static final Map<Integer, JsonReportListener> JSON = new ConcurrentHashMap<>();
    private static final AtomicInteger JSON_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger JSON_WROTE = new AtomicInteger();

    private static final Map<Integer, JUnitXmlReportListener> XML = new ConcurrentHashMap<>();
    private static final AtomicInteger XML_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger XML_WROTE = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 30_000)
    void jsonReportIsWrittenByExactlyOneFlusherPerRound() {
        int round = JSON_SEQUENCE.getAndIncrement() / THREADS;
        JsonReportListener listener = JSON.computeIfAbsent(round,
                r -> new JsonReportListener(reportDir.resolve("json-" + r).toString(), false));

        listener.onStructuredReport("DogfoodDetector", IssueSeverity.HIGH, "finding in round " + round);
        if (listener.flush() != null) {
            JSON_WROTE.incrementAndGet();
        }
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 30_000)
    void xmlReportIsWrittenByExactlyOneFlusherPerRound() {
        int round = XML_SEQUENCE.getAndIncrement() / THREADS;
        JUnitXmlReportListener listener = XML.computeIfAbsent(round,
                r -> new JUnitXmlReportListener(reportDir.resolve("xml-" + r).toString(), false));

        listener.onStructuredReport("DogfoodDetector", IssueSeverity.HIGH, "finding in round " + round);
        if (listener.flush() != null) {
            XML_WROTE.incrementAndGet();
        }
    }

    @AfterAll
    static void oneWriterPerRoundAndNoFindingLost() {
        assertEquals(ROUNDS, JSON.size(), "rounds shared a JSON listener");
        assertEquals(ROUNDS, XML.size(), "rounds shared an XML listener");

        assertEquals(ROUNDS, JSON_WROTE.get(),
                "the JSON report was claimed by a number of flushers other than one per round, so "
                        + "concurrent writers raced to write the same file");
        assertEquals(ROUNDS, XML_WROTE.get(),
                "the XML report was claimed by a number of flushers other than one per round, so "
                        + "concurrent writers raced to write the same file");

        int jsonFindings = 0;
        for (Map.Entry<Integer, JsonReportListener> entry : JSON.entrySet()) {
            jsonFindings += entry.getValue().getFindingCount();
            assertTrue(Files.isRegularFile(
                            reportDir.resolve("json-" + entry.getKey()).resolve("async-test-report.json")),
                    "a flush claimed the JSON report for round " + entry.getKey()
                            + " but no file was written");
        }
        assertEquals(THREADS * ROUNDS, jsonFindings,
                "the JSON listeners hold fewer findings than the workers recorded");

        int xmlFindings = 0;
        for (JUnitXmlReportListener listener : XML.values()) {
            xmlFindings += listener.getFindingCount();
        }
        assertEquals(THREADS * ROUNDS, xmlFindings,
                "the XML listeners hold fewer findings than the workers recorded");
    }
}
