package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 5, thread-safety-of-common-types group — {@code CALENDAR} through
 * {@code STRING_BUILDER}.
 *
 * <p>Each fixture shares one deliberately thread-unsafe instance across the colliding
 * workers. Losing that race can throw from inside the JDK class itself, which is the bug
 * being demonstrated — so those calls are caught and reported through the detector instead
 * of failing the round.
 *
 * <p>Corresponding examples: {@code examples/35-calendar-misuse},
 * {@code examples/03-shared-collection}, {@code examples/88-timer-misuse},
 * {@code examples/43-copy-on-write}, {@code examples/78-string-builder-shared}.
 */
class Phase05CommonTypeDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CALENDAR})
    void calendar() {
        reachable("calendarDetector()", AsyncTestContext::calendarDetector);

        try {
            SHARED_CALENDAR.setTimeInMillis(0L);
            SHARED_CALENDAR.get(Calendar.YEAR);
        } catch (RuntimeException expected) {
            // A shared Calendar losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_COLLECTIONS})
    void sharedCollections() {
        reachable("sharedCollectionDetector()", AsyncTestContext::sharedCollectionDetector);

        try {
            SHARED_LIST.add("entry");
            SHARED_LIST.size();
        } catch (RuntimeException expected) {
            // A plain ArrayList mutated concurrently is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.TIMER})
    void timer() {
        reachable("timerDetector()", AsyncTestContext::timerDetector);

        // java.util.Timer dies permanently on the first task exception — the hazard.
        Timer timer = new Timer("fixture-timer", true);
        try {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    spin(32);
                }
            }, 1L);
        } finally {
            timer.cancel();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.COPY_ON_WRITE_COLLECTIONS})
    void copyOnWriteCollections() {
        reachable("copyOnWriteCollectionDetector()",
            AsyncTestContext::copyOnWriteCollectionDetector);

        // Write-heavy use of a copy-on-write list: correct, but O(n) per write.
        CopyOnWriteArrayList<Integer> list = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 16; i++) {
            list.add(i);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STRING_BUILDER})
    void stringBuilder() {
        reachable("stringBuilderDetector()", AsyncTestContext::stringBuilderDetector);

        try {
            SHARED_BUILDER.append("x");
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
}
