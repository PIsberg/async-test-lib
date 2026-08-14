package se.deversity.asynctest.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code RecordMutableComponentLeakDetector} must work on a record it cannot reach without
 * widening access - which is every record a real consumer declares.
 *
 * <p><strong>Why this lives in this package.</strong> Deliberately not beside the detector. The
 * detector reads each component through {@code RecordComponent.getAccessor().invoke(...)}, and
 * that call is rejected for a record declared outside the detector's own package unless access
 * is widened first. Every read then returned {@code null}, so nothing looked mutable, nothing
 * looked changed, and the detector reported nothing for a record two threads were plainly
 * sharing - a silent false negative.
 *
 * <p>The existing unit tests could not catch it: they declare their fixture records in
 * {@code se.deversity.asynctest.diagnostics}, next to the detector, where the accessor is
 * reachable without any widening. The bug was only visible from a consumer, and was found by
 * the consumer fixture asserting detection rather than reachability. Moving this test one
 * package away reproduces the consumer's situation inside the library's own suite.
 *
 * <p>The record below is {@code private} on purpose. Making it public would restore the
 * accessible case and the test would pass whether or not the fix is present.
 */
@DisplayName("Record leak detection works on a record outside the detector's package")
class RecordLeakInaccessibleRecordTest {

    /** Private, in a package the detector cannot see into: the consumer's normal situation. */
    private record Order(String id, List<String> items) { }

    @Test
    @DisplayName("a shared record with a mutable component is reported even when not accessible")
    void sharedRecordWithMutableComponentIsReportedFromAnotherPackage() throws Exception {
        RecordMutableComponentLeakDetector detector = new RecordMutableComponentLeakDetector();
        Order shared = new Order("order-1", new ArrayList<>(List.of("first")));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable touch = () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            detector.recordShared(shared, "order", Thread.currentThread());
        };
        Thread t1 = new Thread(touch, "record-leak-1");
        Thread t2 = new Thread(touch, "record-leak-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertTrue(detector.analyze().hasIssues(),
                "Two threads shared a record whose items component is an ArrayList - shallowly "
                        + "immutable, which is the whole hazard. The detector reported nothing, "
                        + "which means reading the component failed and every component looked "
                        + "absent rather than mutable. Check that read() widens access to the "
                        + "accessor before invoking it; without that this detector is silent for "
                        + "every record a consumer declares outside this library's packages.");
    }
}
