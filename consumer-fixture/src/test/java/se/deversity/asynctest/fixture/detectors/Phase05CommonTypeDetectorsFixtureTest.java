package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 5, thread-safety-of-common-types group - {@code CALENDAR} through
 * {@code STRING_BUILDER}.
 *
 * <p>Each fixture shares one deliberately thread-unsafe instance across the colliding workers,
 * records the access through the detector's public API, and the class asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}. Losing that
 * race can throw from inside the JDK class itself, which is the bug being demonstrated - so
 * those calls are caught and the round continues.
 *
 * <p>The instances are static on purpose. A fixture-local {@code Calendar} or list is shared
 * with nothing, so the detector sees one thread and reports nothing; two of these fixtures used
 * to allocate per invocation and could not have failed however broken the detector was.
 *
 * <p>Corresponding examples: {@code examples/35-calendar-misuse},
 * {@code examples/03-shared-collection}, {@code examples/88-timer-misuse},
 * {@code examples/43-copy-on-write}, {@code examples/78-string-builder-shared}.
 */
class Phase05CommonTypeDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "CalendarDetector",
                    "SharedCollectionDetector",
                    "TimerDetector",
                    "CopyOnWriteCollectionDetector",
                    "StringBuilderDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CALENDAR})
    void calendar() {
        reachable("calendarDetector()", AsyncTestContext::calendarDetector);

        var detector = AsyncTestContext.calendarDetector();
        try {
            detector.recordSet(SHARED_CALENDAR, "shared-calendar");
            SHARED_CALENDAR.setTimeInMillis(0L);
            detector.recordGet(SHARED_CALENDAR, "shared-calendar");
            SHARED_CALENDAR.get(Calendar.YEAR);
        } catch (RuntimeException expected) {
            // A shared Calendar losing a race is the point of this fixture.
            detector.recordError(SHARED_CALENDAR, "shared-calendar",
                    expected.getClass().getSimpleName());
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_COLLECTIONS})
    void sharedCollections() {
        reachable("sharedCollectionDetector()", AsyncTestContext::sharedCollectionDetector);

        var detector = AsyncTestContext.sharedCollectionDetector();
        try {
            detector.recordWrite(SHARED_LIST, "shared-list", "add");
            SHARED_LIST.add("entry");
            detector.recordRead(SHARED_LIST, "shared-list", "size");
            SHARED_LIST.size();
        } catch (RuntimeException expected) {
            // A plain ArrayList mutated concurrently is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.TIMER})
    void timer() {
        reachable("timerDetector()", AsyncTestContext::timerDetector);

        // java.util.Timer dies permanently on the first task exception - the hazard, and the
        // reason one Timer shared by several producers is worth flagging. Shared across the
        // round rather than allocated per invocation, so the detector sees the sharing; it is a
        // daemon and is never cancelled, because cancelling it in one worker would break the
        // other, and a daemon thread does not hold the JVM open.
        var detector = AsyncTestContext.timerDetector();
        detector.recordTaskSchedule(SHARED_TIMER, "shared-timer", "fixture-task");
        SHARED_TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                spin(32);
            }
        }, 1L);
        detector.recordTaskRun(SHARED_TIMER, "shared-timer", "fixture-task");

        // The finding itself: one uncaught task exception kills the Timer's single thread for
        // good, and every task queued behind it silently never runs. Recorded rather than
        // actually thrown - a genuinely dead timer thread would make the other worker's
        // schedule() throw IllegalStateException, so a real throw here would trade a
        // deterministic fixture for no extra coverage.
        detector.recordTaskException(SHARED_TIMER, "shared-timer", "fixture-task",
                new IllegalStateException("task threw; the timer thread is gone"));
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COPY_ON_WRITE_COLLECTIONS})
    void copyOnWriteCollections() {
        reachable("copyOnWriteCollectionDetector()",
            AsyncTestContext::copyOnWriteCollectionDetector);

        // Write-heavy use of a copy-on-write list: correct, but O(n) per write, so the whole
        // finding is about the read/write ratio on ONE shared instance.
        var detector = AsyncTestContext.copyOnWriteCollectionDetector();
        for (int i = 0; i < 16; i++) {
            detector.recordWrite(SHARED_COW_LIST, "shared-cow-list");
            SHARED_COW_LIST.add(i);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STRING_BUILDER})
    void stringBuilder() {
        reachable("stringBuilderDetector()", AsyncTestContext::stringBuilderDetector);

        var detector = AsyncTestContext.stringBuilderDetector();
        try {
            detector.recordAppend(SHARED_BUILDER, "shared-builder");
            SHARED_BUILDER.append("x");
            detector.recordRead(SHARED_BUILDER, "shared-builder");
            if (SHARED_BUILDER.length() > 512) {
                SHARED_BUILDER.setLength(0);
            }
        } catch (RuntimeException expected) {
            // StringBuilder is documented as unsynchronised; the throw is the finding.
        }
    }

    private static final Calendar SHARED_CALENDAR = Calendar.getInstance();

    private static final List<String> SHARED_LIST = new ArrayList<>();

    private static final StringBuilder SHARED_BUILDER = new StringBuilder();

    private static final CopyOnWriteArrayList<Integer> SHARED_COW_LIST =
            new CopyOnWriteArrayList<>();

    private static final Timer SHARED_TIMER = new Timer("fixture-timer", true);
}
