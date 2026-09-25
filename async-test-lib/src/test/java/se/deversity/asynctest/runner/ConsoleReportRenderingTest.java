package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.E2E;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a passing run prints by default: findings the library stands behind in full, the rest as
 * one line each.
 *
 * <p><strong>Why this exists.</strong> A default run enables every detector with
 * {@code failOn = NONE}, and every PROMPT and ADVISORY block printed its whole report, "Why" and
 * "Fix" text included. The console was dominated by findings the library itself calls a prompt to
 * verify, and the verdicts drowned in them.
 */
@E2E
class ConsoleReportRenderingTest {

    /** Text only the full RecordMutableComponentLeak report contains: its "Fix" advice. */
    private static final String PROMPT_REPORT_BODY = "Copy defensively in a compact constructor";

    @AfterEach
    void clearProperty() {
        System.clearProperty(ConcurrencyRunner.FULL_REPORT_PROPERTY);
    }

    @Test
    @DisplayName("a prompt-grade block prints as one line with a pointer to the full text")
    void promptBlockIsFoldedToOneLine() {
        Map<String, String> heard = new ConcurrentHashMap<>();
        String err = run(PromptOnlyDummy.class, heard);

        assertTrue(err.contains("RecordMutableComponentLeakDetector trust=PROMPT findings=1"), err);
        assertTrue(err.contains("-D" + ConcurrencyRunner.FULL_REPORT_PROPERTY + "=true"),
                "the folded line must say how to see the report in full: " + err);
        assertFalse(err.contains(PROMPT_REPORT_BODY), "the prompt report must not print in full: " + err);
        assertTrue(heard.getOrDefault("RecordMutableComponentLeakDetector", "").contains(PROMPT_REPORT_BODY),
                "listeners still receive the full report text; heard: " + heard.keySet());
    }

    @Test
    @DisplayName("a verdict-grade block still prints in full")
    void verdictBlockPrintsInFull() {
        String err = run(VerdictDummy.class, new ConcurrentHashMap<>());

        assertTrue(err.contains("SHARED MESSAGE DIGEST / CRYPTOGRAPHY DETECTED"), err);
    }

    @Test
    @DisplayName("-Dasync-test.report.full=true prints prompt-grade blocks in full again")
    void propertyRestoresFullPrinting() {
        System.setProperty(ConcurrencyRunner.FULL_REPORT_PROPERTY, "true");
        String err = run(PromptOnlyDummy.class, new ConcurrentHashMap<>());

        assertTrue(err.contains(PROMPT_REPORT_BODY), err);
    }

    private static String run(Class<?> fixture, Map<String, String> heard) {
        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                heard.merge(detectorName, report, String::concat);
            }
        };
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        AsyncTestListenerRegistry.register(listener);
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            EngineTestKit.engine("junit-jupiter")
                    .selectors(DiscoverySelectors.selectClass(fixture))
                    .execute()
                    .testEvents()
                    .assertStatistics(s -> s.started(1).succeeded(1));
        } finally {
            System.setErr(original);
            AsyncTestListenerRegistry.unregister(listener);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** A record shared by two threads and never written through: a PROMPT-grade finding only. */
    public static class PromptOnlyDummy {
        private final Order order = new Order("A-2", new ArrayList<>(List.of("fixed")));

        @AsyncTest(threads = 2, invocations = 2, licenseMockMode = true,
                   includes = {DetectorType.RECORD_MUTABLE_COMPONENT_LEAK})
        void shareWithoutMutating() {
            AsyncTestContext.recordMutableComponentLeakDetector()
                    .recordShared(order, "order", Thread.currentThread());
        }
    }

    /** One MessageDigest used from every worker: a VERDICT-tier detector. */
    public static class VerdictDummy {
        private final MessageDigest shared = sha256();

        @AsyncTest(threads = 2, invocations = 2, licenseMockMode = true,
                   includes = {DetectorType.SHARED_MESSAGE_DIGEST})
        void sharedDigest() {
            AsyncTestContext.sharedMessageDigestDetector()
                    .recordAccess(shared, "shared-sha256", Thread.currentThread());
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Shallowly immutable: the list reference is final, the list is not. */
    public record Order(String id, List<String> items) { }
}
