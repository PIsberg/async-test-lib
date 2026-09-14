package se.deversity.asynctest.agent;

import com.example.agentfixture.AtomicSpinLockTableBean;
import com.example.agentfixture.PreAttachSpinLockTableBean;
import com.example.agentfixture.SpinLockTableBean;
import com.example.agentfixture.UpdaterSpinLockTableBean;
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
 * Pins that a compare-and-swap spinlock guards what is written under it (#554, #558).
 *
 * <p>A volatile field whose every write holds one lock is safe publication, and the validator has
 * always excused it. A spinlock is a lock the lockset could not see: the acquire is a
 * {@code VarHandle.compareAndSet(this, 0, 1)}, an {@code AtomicIntegerFieldUpdater} swap or a swap
 * on an atomic object, and the release a write of 0 or a second compare-and-swap. The quiet cases
 * cover each acquire and release; the twins write the same kind of table with nothing held.
 *
 * <p>The unobserved-release twins are the ones that matter most. A lock whose release the weaver
 * cannot see would make every later write on that thread look guarded, which hides a real race; a
 * spinlock therefore counts as held only while its flag still reads locked with this thread as its
 * holder, and those twins release through a call the weaver does not substitute.
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

        // Initialised before the attach on purpose: its VarHandle is bound by a type initializer
        // that has already run, so only the retransformed call sites can see it.
        PreAttachSpinLockTableBean.initialise();
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

    @Test
    @DisplayName("a VarHandle spinlock released through a call the weaver does not see guards nothing after it")
    void varHandleSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        SpinLockTableBean bean = new SpinLockTableBean();
        AtomicityValidator.AtomicityReport report =
                drive(bean::growThenWriteAfterUnobservedRelease);

        assertUnobservedReleaseReported(report);
    }

    // ---- AtomicIntegerFieldUpdater (#558) -------------------------------------------------------

    @Test
    @DisplayName("an updater spinlock released by a write of the flag guards the table")
    void updaterSpinLockReleasedByAWriteGuardsTheTable() throws Exception {
        assertQuiet(drive(new UpdaterSpinLockTableBean()::growReleasedByWrite),
                "AtomicIntegerFieldUpdater.compareAndSet(this, 0, 1), released by busy = 0");
    }

    @Test
    @DisplayName("an updater spinlock released by a compare-and-swap guards the table")
    void updaterSpinLockReleasedByACompareAndSetGuardsTheTable() throws Exception {
        assertQuiet(drive(new UpdaterSpinLockTableBean()::growReleasedByCompareAndSet),
                "AtomicIntegerFieldUpdater.compareAndSet(this, 0, 1), released by compareAndSet(this, 1, 0)");
    }

    @Test
    @DisplayName("an updater spinlock released by set guards the table")
    void updaterSpinLockReleasedBySetGuardsTheTable() throws Exception {
        assertQuiet(drive(new UpdaterSpinLockTableBean()::growReleasedBySet),
                "AtomicIntegerFieldUpdater.compareAndSet(this, 0, 1), released by set(this, 0)");
    }

    @Test
    @DisplayName("an updater spinlock released through a call the weaver does not see guards nothing after it")
    void updaterSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                drive(new UpdaterSpinLockTableBean()::growThenWriteAfterUnobservedRelease));
    }

    // ---- AtomicBoolean / AtomicInteger used as the lock (#558) ----------------------------------

    @Test
    @DisplayName("an AtomicBoolean spinlock released by set(false) guards the table")
    void atomicBooleanSpinLockReleasedBySetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growBooleanReleasedBySet),
                "busy.compareAndSet(false, true), released by busy.set(false)");
    }

    @Test
    @DisplayName("an AtomicBoolean spinlock released by a compare-and-swap guards the table")
    void atomicBooleanSpinLockReleasedByACompareAndSetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growBooleanReleasedByCompareAndSet),
                "busy.compareAndSet(false, true), released by busy.compareAndSet(true, false)");
    }

    @Test
    @DisplayName("an AtomicBoolean spinlock taken by getAndSet(true) guards the table")
    void atomicBooleanSpinLockAcquiredByGetAndSetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growBooleanAcquiredByGetAndSet),
                "!busy.getAndSet(true), released by busy.lazySet(false)");
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released by set(0) guards the table")
    void atomicIntegerSpinLockReleasedBySetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growIntegerReleasedBySet),
                "intBusy.compareAndSet(0, 1), released by intBusy.set(0)");
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released by a compare-and-swap guards the table")
    void atomicIntegerSpinLockReleasedByACompareAndSetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growIntegerReleasedByCompareAndSet),
                "intBusy.compareAndSet(0, 1), released by intBusy.compareAndSet(1, 0)");
    }

    @Test
    @DisplayName("an AtomicBoolean spinlock released through a call the weaver does not see guards nothing after it")
    void atomicBooleanSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                drive(new AtomicSpinLockTableBean()::growBooleanThenWriteAfterUnobservedRelease));
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released through a call the weaver does not see guards nothing after it")
    void atomicIntegerSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                drive(new AtomicSpinLockTableBean()::growIntegerThenWriteAfterUnobservedRelease));
    }

    // ---- A VarHandle bound before the agent attached (#558) -------------------------------------

    @Test
    @DisplayName("a VarHandle spinlock bound before the agent attached still guards the table")
    void preAttachVarHandleSpinLockGuardsTheTable() throws Exception {
        assertQuiet(drive(new PreAttachSpinLockTableBean()::growReleasedByWrite),
                "the handle's type initializer ran before the attach, so the field it reaches must be "
                        + "resolved from the handle itself");
    }

    private static void assertQuiet(AtomicityValidator.AtomicityReport report, String shape) {
        assertFalse(report.hasIssues(),
                "every write to the table happened with the spinlock held (" + shape + "); the won "
                        + "acquire must enter the lockset and the release leave it. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    private static void assertUnobservedReleaseReported(AtomicityValidator.AtomicityReport report) {
        assertTrue(report.hasIssues(),
                "after a release the weaver cannot see, two threads replace a volatile table with "
                        + "nothing excluding them. A lock that stays in the lockset because its "
                        + "release was invisible hides that race");
    }
}
