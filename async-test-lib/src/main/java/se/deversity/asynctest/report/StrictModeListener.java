package se.deversity.asynctest.report;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.AsyncTestListener;

/**
 * An {@link AsyncTestListener} that converts any detector report into an immediate test failure.
 *
 * <p>Register this listener in a strict CI pipeline where <em>any</em> concurrency finding
 * should break the build — not just test-body assertion failures.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @BeforeAll
 * static void setup() {
 *     AsyncTestListenerRegistry.register(new StrictModeListener());
 * }
 * }</pre>
 *
 * <p>When a detector fires, this listener throws an {@link AssertionError} from the calling
 * thread. JUnit 5 catches it and marks the test as failed, and the full detector report
 * appears in the failure message.
 *
 * @see JUnitXmlReportListener
 */
@API(status = Status.STABLE)
public final class StrictModeListener implements AsyncTestListener {

    @Override
    public void onDetectorReport(String detectorName, String report) {
        throw new AssertionError(
            "[async-test strict mode] Concurrency issue detected by " + detectorName
            + ":\n" + report);
    }
}
