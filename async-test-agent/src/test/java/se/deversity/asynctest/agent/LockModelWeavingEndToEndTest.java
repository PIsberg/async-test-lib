package se.deversity.asynctest.agent;

import com.example.agentfixture.DirectFieldMutationBean;
import com.example.agentfixture.ActingOnUnlockedReadBean;
import com.example.agentfixture.ReadLockWritingBean;
import com.example.agentfixture.RevalidatingHintBean;
import com.example.agentfixture.ReadWriteLockBean;
import com.example.agentfixture.StampedLockBean;
import com.example.agentfixture.StampedReadLockWritingBean;
import com.example.agentfixture.SynchronizedMethodBean;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins what the lock model can now see end to end: {@code synchronized} methods, and the two
 * views of a read-write lock resolving to one owner without losing their modes.
 *
 * <p>Each case drives a fixture from two threads through the full woven pipeline and asks the
 * {@link AtomicityValidator} what it saw. The quiet cases and the loud ones travel together on
 * purpose: a rule that silences a false positive by silencing everything would fail the last
 * test here, and a rule that never silences anything would fail the first.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class LockModelWeavingEndToEndTest {

    @BeforeAll
    static void attachWithFieldAndLockWeaving() {
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
                }, "lock-model-worker-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity();
    }

    @Test
    @DisplayName("a class built on synchronized methods is not a race")
    void synchronizedMethodsReadAsGuarded() throws Exception {
        SynchronizedMethodBean bean = new SynchronizedMethodBean();
        AtomicityValidator.AtomicityReport report = drive(() -> {
            bean.increment();
            bean.current();
        });

        assertFalse(report.hasIssues(),
                "every access happens inside a synchronized method, whose ACC_SYNCHRONIZED flag "
                        + "emits no monitor instruction; the receiver probe and the method-monitor "
                        + "argument are what make it readable as guarded. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("reader under the read view and writer under the write view share one lock")
    void readWriteLockViewsResolveToTheirOwner() throws Exception {
        ReadWriteLockBean bean = new ReadWriteLockBean();
        AtomicityValidator.AtomicityReport report = drive(() -> {
            bean.increment();
            bean.current();
        });

        assertFalse(report.hasIssues(),
                "writes hold the write view and reads the read view of one "
                        + "ReentrantReadWriteLock; resolving both views to the owner is what lets "
                        + "them intersect. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    /**
     * The safe and unsafe shapes of an unlocked read are the same access stream.
     *
     * <p>[#311](https://github.com/PIsberg/async-test-lib/issues/311) proposed retracting a
     * finding when an unlocked read is followed by a read of the same field under a lock that
     * covers its writes, which is the safe half of double-checked locking and what Spring's
     * {@code ConcurrentReferenceHashMap$Segment.resizeThreshold} does. This test is why that rule
     * cannot be written against the access stream.
     *
     * <p>{@link RevalidatingHintBean} and {@link ActingOnUnlockedReadBean} differ only in whether
     * the branch consumes the unlocked value or the re-read one. Both compile to the same
     * sequence: unlocked GETFIELD, lock, locked GETFIELD, locked PUTFIELD. A rule keyed on that
     * sequence would retract the lost update along with the hint.
     *
     * <p>So this asserts the current, honest state rather than the desired one: the two report the
     * same fields. Telling them apart needs weave-time dataflow, asking whether the value a
     * GETFIELD loaded reaches a branch whose taken path writes. When that lands, this test goes
     * red and #311 is the reason.
     */
    @Test
    @DisplayName("the hint shape and acting on the read are one access stream, so far")
    void theHintShapeIsNotYetDistinguishableFromActingOnTheRead() throws Exception {
        RevalidatingHintBean hint = new RevalidatingHintBean();
        AtomicityValidator.AtomicityReport hintReport = drive(() -> {
            hint.put();
            hint.threshold();
        });
        ActingOnUnlockedReadBean acting = new ActingOnUnlockedReadBean();
        AtomicityValidator.AtomicityReport actingReport = drive(() -> {
            acting.put();
            acting.threshold();
        });

        assertEquals(
                fieldsIn(hintReport, RevalidatingHintBean.class.getName()),
                fieldsIn(actingReport, ActingOnUnlockedReadBean.class.getName()),
                "the correct bean and the racing one must still look identical to the model. If "
                        + "they no longer do, the analyzer has gained the dataflow #311 needs and "
                        + "this test should become the pair of assertions that issue asks for: "
                        + "the hint silent, the lost update still reported");
        assertTrue(hintReport.hasIssues(),
                "the hint bean draws the false positive #311 is about; if it went quiet on its "
                        + "own, find out why before celebrating");
    }

    /** {@return the field names a report mentions, stripped of the bean's package and class} */
    private static java.util.Set<String> fieldsIn(AtomicityValidator.AtomicityReport report,
                                                  String className) {
        java.util.Set<String> fields = new java.util.TreeSet<>();
        java.util.stream.Stream.concat(report.unsafeFieldAccesses.stream(),
                        report.totcouRaces.stream())
                .filter(entry -> entry.startsWith(className + "."))
                .map(entry -> entry.substring(className.length() + 1, entry.indexOf(':')))
                .forEach(fields::add);
        return fields;
    }

    @Test
    @DisplayName("a write under the read view keeps firing: a shared lock guards no write")
    void writingUnderTheReadViewIsStillReported() throws Exception {
        ReadLockWritingBean bean = new ReadLockWritingBean();
        AtomicityValidator.AtomicityReport report = drive(bean::increment);

        assertTrue(report.hasIssues(),
                "count++ under the read view is mutation under a lock that admits every other "
                        + "reader; if this stops firing, resolving the views has erased the "
                        + "shared/exclusive distinction");
    }

    @Test
    @DisplayName("stamped locking reads as guarded: write stamps exclude, read stamps share")
    void stampedLockReadsAsGuarded() throws Exception {
        StampedLockBean bean = new StampedLockBean();
        AtomicityValidator.AtomicityReport report = drive(() -> {
            bean.increment();
            bean.current();
        });

        assertFalse(report.hasIssues(),
                "StampedLock implements no locking interface, so only its own call-site hooks "
                        + "can make this bean readable as guarded. Findings: "
                        + report.unsafeFieldAccesses + report.totcouRaces);
    }

    @Test
    @DisplayName("a write under a read stamp keeps firing: a shared stamp guards no write")
    void writingUnderAReadStampIsStillReported() throws Exception {
        StampedReadLockWritingBean bean = new StampedReadLockWritingBean();
        AtomicityValidator.AtomicityReport report = drive(bean::increment);

        assertTrue(report.hasIssues(),
                "count++ under a read stamp is mutation under a lock that admits every other "
                        + "reader; if this stops firing, the stamp modelling has erased the "
                        + "shared/exclusive distinction");
    }

    @Test
    @DisplayName("the bug direction survives it all: a bare count++ still fires")
    void unguardedMutationIsStillReported() throws Exception {
        DirectFieldMutationBean bean = new DirectFieldMutationBean();
        AtomicityValidator.AtomicityReport report = drive(bean::increment);

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("DirectFieldMutationBean.count")),
                "the same pipeline that clears the guarded fixtures must keep reporting the "
                        + "unguarded one. Findings were: " + report.unsafeFieldAccesses);
    }
}
