package se.deversity.asynctest.spi;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-in for a detector a user would ship in their own jar: discovered purely through
 * {@code META-INF/services}, with no entry in {@code DetectorType}-keyed built-in wiring
 * beyond the type it declares.
 *
 * <p>It is inert until {@link #arm()} is called, so its presence on the test classpath
 * cannot leak findings into any other test class — {@link ExternalTestDetectorFactory}
 * reports it as disabled and the registry never even instantiates it.
 */
public final class ExternalTestDetector implements Detector {

    /** Detector name carried by the emitted {@link Violation}, and therefore the report key. */
    public static final String NAME = "ExternalTest";

    /** Message carried by the emitted {@link Violation}. */
    public static final String MESSAGE = "external SPI detector reached the runner";

    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicInteger STARTS = new AtomicInteger();
    private static final AtomicInteger ENDS = new AtomicInteger();

    /** Enables discovery and resets the lifecycle counters. */
    public static void arm() {
        STARTS.set(0);
        ENDS.set(0);
        ARMED.set(true);
    }

    /** Disables discovery again; call from {@code @AfterEach} so other tests are unaffected. */
    public static void disarm() {
        ARMED.set(false);
        STARTS.set(0);
        ENDS.set(0);
    }

    static boolean armed() {
        return ARMED.get();
    }

    /** {@return how many times {@link #onTestStart()} has fired since {@link #arm()}} */
    public static int starts() {
        return STARTS.get();
    }

    /** {@return how many times {@link #onTestEnd()} has fired since {@link #arm()}} */
    public static int ends() {
        return ENDS.get();
    }

    @Override
    public DetectorType type() {
        return DetectorType.EXPLICIT_GC;
    }

    @Override
    public List<Violation> analyze() {
        return List.of(new Violation(NAME, IssueSeverity.MEDIUM, MESSAGE,
                List.of(), Map.of(), Instant.now()));
    }

    @Override
    public void onTestStart() {
        STARTS.incrementAndGet();
    }

    @Override
    public void onTestEnd() {
        ENDS.incrementAndGet();
    }
}
