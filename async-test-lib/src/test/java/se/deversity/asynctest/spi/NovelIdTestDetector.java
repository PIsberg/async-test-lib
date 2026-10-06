package se.deversity.asynctest.spi;

import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stand-in for a genuinely new third-party detector: it declares its own {@link #id()} and no
 * {@link se.deversity.asynctest.DetectorType} at all, which before #919 it could not do.
 *
 * <p>Inert until {@link #arm()}, like {@link ExternalTestDetector}, so its presence on the test
 * classpath cannot leak a finding into any other test.
 */
public final class NovelIdTestDetector implements Detector {

    /** The identity this detector declares; not the name of any {@code DetectorType} constant. */
    public static final String ID = "acme.novel-test-detector";

    /** The detector name its {@link Violation} carries, and therefore its report key. */
    public static final String NAME = "AcmeNovel";

    /** The message its {@link Violation} carries. */
    public static final String MESSAGE = "a detector with its own identity reached the runner";

    private static final AtomicBoolean ARMED = new AtomicBoolean();

    /** Enables discovery. */
    public static void arm() {
        ARMED.set(true);
    }

    /** Disables discovery again; call from {@code @AfterEach}. */
    public static void disarm() {
        ARMED.set(false);
    }

    static boolean armed() {
        return ARMED.get();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Violation> analyze() {
        return List.of(new Violation(NAME, IssueSeverity.LOW, MESSAGE, List.of(), Map.of(), Instant.now()));
    }
}
