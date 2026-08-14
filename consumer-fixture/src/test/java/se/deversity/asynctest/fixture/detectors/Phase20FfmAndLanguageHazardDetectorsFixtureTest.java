package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.VarHandleNonAtomicUpdateDetector;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 20, FFM / VarHandle / record / class-initialization group —
 * {@code CONFINED_ARENA_THREAD_ESCAPE}, {@code SHARED_MEMORY_SEGMENT_RACE},
 * {@code VAR_HANDLE_NON_ATOMIC_UPDATE}, {@code RECORD_MUTABLE_COMPONENT_LEAK} and
 * {@code STATIC_INIT_DEADLOCK}.
 *
 * <p>These fixtures exist to prove each detector is reachable from the published surface and
 * records without throwing under real contention. They are not accuracy tests — that is
 * {@code DetectorAccuracyEvalTest}'s job inside the library — so nothing here asserts a finding.
 *
 * <p>The two FFM fixtures deliberately use plain objects as segment stand-ins rather than real
 * {@code MemorySegment}s. The consumer fixture compiles at the library's JDK 21 baseline, where
 * {@code java.lang.foreign} is still preview, and the recording API takes {@link Object} for
 * exactly that reason. A consumer on JDK 22+ passes real segments and the same calls apply.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} for the buggy-vs-fixed pair behind each one.
 */
class Phase20FfmAndLanguageHazardDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "ConfinedArenaThreadEscapeDetector",
                    "SharedMemorySegmentRaceDetector",
                    "VarHandleNonAtomicUpdateDetector",
                    "RecordMutableComponentLeakDetector",
                    "StaticInitDeadlockDetector");
        } finally {
            findings.close();
        }
    }


    /** Stand-ins shared by every worker — the sharing is the hazard in each case. */
    private static final Object SHARED_ARENA = new Object();
    private static final Object SHARED_SEGMENT = new Object();

    /** A record whose list component is mutable: shallowly immutable, and that is the point. */
    record SharedOrder(String id, List<String> items) { }

    private static final SharedOrder SHARED_ORDER =
            new SharedOrder("fixture-order", new ArrayList<>(List.of("a")));

    /** Target of the VarHandle fixture. */
    static final class Holder {
        volatile int count;
    }

    private static final VarHandle COUNT;

    static {
        try {
            COUNT = MethodHandles.lookup().findVarHandle(Holder.class, "count", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Holder SHARED_HOLDER = new Holder();

    /** Assigns the two halves of the wait-for cycle, so both are always recorded. */
    private static final java.util.concurrent.atomic.AtomicInteger INIT_ROLE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Classes whose initialization the static-init fixture models as mutually blocking. */
    static final class ConfigLike { }

    static final class RegistryLike { }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONFINED_ARENA_THREAD_ESCAPE})
    void confinedArenaThreadEscape() {
        reachable("confinedArenaThreadEscapeDetector()",
                AsyncTestContext::confinedArenaThreadEscapeDetector);

        // The hazard: a segment allocated from a confined arena on one thread and touched from
        // another. Both workers record an access to the same segment, so one of them is not the
        // thread that registered the arena.
        var detector = AsyncTestContext.confinedArenaThreadEscapeDetector();
        detector.recordArena(SHARED_ARENA, "fixtureArena", Thread.currentThread());
        detector.recordAllocation(SHARED_SEGMENT, SHARED_ARENA, "fixtureSegment", 1024);
        spin(8);
        detector.recordAccess(SHARED_SEGMENT, "fixtureSegment", Thread.currentThread(), true);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_MEMORY_SEGMENT_RACE})
    void sharedMemorySegmentRace() {
        reachable("sharedMemorySegmentRaceDetector()",
                AsyncTestContext::sharedMemorySegmentRaceDetector);

        // The hazard: two workers writing the same byte range of a shared segment with no lock
        // recorded on either side. Plain segment access carries no ordering guarantee.
        var detector = AsyncTestContext.sharedMemorySegmentRaceDetector();
        detector.recordAccess(SHARED_SEGMENT, "fixtureBuffer", 0, 8, true, Thread.currentThread());
        spin(8);
        detector.recordAccess(SHARED_SEGMENT, "fixtureBuffer", 4, 8, false, Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.VAR_HANDLE_NON_ATOMIC_UPDATE})
    void varHandleNonAtomicUpdate() {
        reachable("varHandleNonAtomicUpdateDetector()",
                AsyncTestContext::varHandleNonAtomicUpdateDetector);

        // The hazard: a get followed by a set through a VarHandle. Two operations, not one, so a
        // write landing between them is lost — and setVolatile does not change that.
        var detector = AsyncTestContext.varHandleNonAtomicUpdateDetector();
        Thread me = Thread.currentThread();
        int seen = (int) COUNT.getVolatile(SHARED_HOLDER);
        detector.recordGet(COUNT, SHARED_HOLDER, "count",
                VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
        spin(8);
        COUNT.setVolatile(SHARED_HOLDER, seen + 1);
        detector.recordSet(COUNT, SHARED_HOLDER, "count",
                VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.RECORD_MUTABLE_COMPONENT_LEAK})
    void recordMutableComponentLeak() {
        reachable("recordMutableComponentLeakDetector()",
                AsyncTestContext::recordMutableComponentLeakDetector);

        // The hazard: a record shared across threads whose list component is an ArrayList rather
        // than a List.copyOf. Both workers record the same instance, which is what makes it
        // shared as far as the detector is concerned.
        var detector = AsyncTestContext.recordMutableComponentLeakDetector();
        detector.recordShared(SHARED_ORDER, "fixtureOrder", Thread.currentThread());
        spin(8);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STATIC_INIT_DEADLOCK})
    void staticInitDeadlock() {
        reachable("staticInitDeadlockDetector()",
                AsyncTestContext::staticInitDeadlockDetector);

        // The hazard modelled, not created: a real class-initialization deadlock cannot be
        // unwedged, so the fixture records the wait-for edges such a deadlock would produce and
        // then completes them. Creating one here would hang the consumer suite forever.
        // The hazard modelled, not created: a real class-initialization deadlock cannot be
        // unwedged, so creating one would hang the consumer suite forever. What is modelled is
        // the wait-for cycle itself, which is what the detector actually analyses - one worker
        // initializing Config and waiting on Registry, the other the mirror image.
        //
        // The previous version recorded only one half and then completed it, so there was no
        // cycle to find and the detector was right to stay silent. Roles are assigned rather
        // than raced so both halves are always present.
        var detector = AsyncTestContext.staticInitDeadlockDetector();
        Thread me = Thread.currentThread();
        if (INIT_ROLE.getAndIncrement() % 2 == 0) {
            detector.recordInitStart(ConfigLike.class, me);
            spin(8);
            detector.recordInitRequest(RegistryLike.class, me);
        } else {
            detector.recordInitStart(RegistryLike.class, me);
            spin(8);
            detector.recordInitRequest(ConfigLike.class, me);
        }
    }
}
