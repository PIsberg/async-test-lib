package se.deversity.asynctest.agent;

import com.example.agentfixture.SpinLockTableBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a compare-and-swap spinlock guards what is written under it (#554).
 *
 * <p>A volatile field whose every write holds one lock is safe publication, and the validator has
 * always excused it. A spinlock is a lock the lockset could not see: the acquire is a
 * {@code VarHandle.compareAndSet(this, 0, 1)} and the release a write of 0 or a second
 * compare-and-swap. The two quiet cases cover both releases; the twin writes the same kind of
 * table with nothing held.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class SpinLockWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Runs {@code work} 200 times on each of two threads with the bridge active, then analyzes. */
    private static AtomicityValidator.AtomicityReport drive(Runnable work) throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        work.run();
                    }
                    done.countDown();
                }, "spin-lock-worker-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity();
    }

    @Test
    @DisplayName("a volatile table replaced under a CAS spinlock released by a write is not a race")
    void spinLockReleasedByAWriteGuardsTheTable() throws Exception {
        SpinLockTableBean bean = new SpinLockTableBean();
        AtomicityValidator.AtomicityReport report = drive(bean::growReleasedByWrite);

        assertFalse(report.hasIssues(),
                "every write to the table happened with the spinlock held; the won compareAndSet "
                        + "must enter the lockset and the write of 0 leave it. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("a volatile table replaced under a CAS spinlock released by a CAS is not a race")
    void spinLockReleasedByACompareAndSetGuardsTheTable() throws Exception {
        SpinLockTableBean bean = new SpinLockTableBean();
        AtomicityValidator.AtomicityReport report = drive(bean::growReleasedByCompareAndSet);

        assertFalse(report.hasIssues(),
                "the release is compareAndSet(this, 1, 0); it must leave the lockset as the write "
                        + "of 0 does. Findings: " + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("the same table replaced with no spinlock still fires")
    void tableReplacedWithoutASpinLockIsStillReported() throws Exception {
        SpinLockTableBean bean = new SpinLockTableBean();
        AtomicityValidator.AtomicityReport report = drive(bean::growUnguarded);

        assertTrue(report.hasIssues(),
                "two threads replace a volatile table with nothing excluding them; volatility "
                        + "without a lock at every write is not safe publication");
    }
}
