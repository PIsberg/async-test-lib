package se.deversity.asynctest.runner;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * A finding's identity used to be derived by slicing its report text at the first colon.
 * Fourteen detectors open their report with {@code IssueSeverity.X.format()} — an ANSI-wrapped
 * severity label — so the "detector name" for every one of them came out as the same string:
 * {@code ESC[33m🟠 HIGH ESC[0m}.
 *
 * <p>That identity is the key of the findings map ({@code putIfAbsent}), the argument to
 * {@link AsyncTestListener#onDetectorReport} (whose own Javadoc promises a name like
 * {@code "FalseSharingDetector"}), and the baseline suppression key. So:
 *
 * <ul>
 *   <li>Two HIGH findings from different detectors collapsed into one map entry — the second
 *       was never printed, never fired to a listener, and never failed the test.</li>
 *   <li>Baselining one known HIGH finding wrote that severity label into the baseline file,
 *       which then suppressed <em>every</em> future HIGH finding for that test — including a
 *       brand-new data race.</li>
 * </ul>
 */
class DetectorReportKeyTest {

    /** The ASCII escape character that opens every ANSI colour sequence. */
    private static final String ESC = String.valueOf((char) 27);

    private static final List<String> REPORTED = new CopyOnWriteArrayList<>();

    /**
     * The direct pin: a race finding must be filed under the detector that produced it, even
     * though its report opens with the HIGH severity marker.
     */
    @Test
    void aFindingIsKeyedByItsDetectorNotByItsSeverityMarker() throws InterruptedException {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectRaceConditions(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        RaceConditionDetector race = ctx.sharedRaceConditionDetector();

        // Two threads write the same field with no synchronization — a real race.
        Object shared = new Object();
        Thread t1 = new Thread(() -> race.recordFieldWrite(shared, "balance"));
        Thread t2 = new Thread(() -> race.recordFieldWrite(shared, "balance"));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        Map<String, String> findings = ctx.analyzeAllNamed();

        assertTrue(findings.containsKey("RaceConditionDetector"),
            "the race must be filed under RaceConditionDetector, but the keys were: "
                + debug(findings.keySet().toString()));
        for (String key : findings.keySet()) {
            assertFalse(key.contains(ESC),
                "a finding key must not be an ANSI-wrapped severity label: " + debug(key));
        }
    }

    /** End-to-end: the name handed to listeners must be a detector, not a severity marker. */
    @Test
    void listenersReceiveDetectorNamesNotSeverityMarkers() {
        REPORTED.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                REPORTED.add(detectorName);
            }
        };

        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(RaceTest.class))
                .execute();
        }

        assertFalse(REPORTED.isEmpty(), "the run must surface at least one finding");
        for (String name : REPORTED) {
            assertFalse(name.contains(ESC),
                "a detector name must not be an ANSI-wrapped severity label: " + debug(name));
            assertFalse(name.contains("🟠") || name.contains("🔴") || name.contains("🟡") || name.contains("🟢"),
                "a detector name must not be a severity marker: " + debug(name));
        }
    }

    private static String debug(String s) {
        return s.replace(ESC, "<ESC>");
    }

    /** Two threads incrementing a plain field. */
    public static class RaceTest {
        private int counter;

        @AsyncTest(threads = 4, invocations = 20, detectAll = true, failOn = FailOn.HIGH)
        void race() {
            counter++;
        }
    }
}
