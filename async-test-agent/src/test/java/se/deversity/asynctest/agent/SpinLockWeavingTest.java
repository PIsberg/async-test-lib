package se.deversity.asynctest.agent;

import com.example.agentfixture.AtomicSpinLockTableBean;
import com.example.agentfixture.InheritedUpdaterSpinLockBean;
import com.example.agentfixture.OutsideUpdaterTargetBean;
import com.example.agentfixture.PreAttachSpinLockTableBean;
import com.example.agentfixture.PreAttachUpdaterSpinLockTableBean;
import com.example.agentfixture.SpinLockHandOffBean;
import com.example.agentfixture.SpinLockTableBean;
import com.example.agentfixture.UpdaterSpinLockTableBean;
import com.example.unwovenfixture.OutsideUpdaterMaker;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * holder. The twins release through a form the weaver observes, since #658 or since #667
 * ({@code setPlain}, {@code updateAndGet}, {@code getAndUpdate}, {@code getAndSetRelease}), and
 * must fire all the same: the write after the release is unguarded whichever form ended the hold.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true,collections=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class SpinLockWeavingTest {

    /** The registry's package-private spinlock table, reached reflectively for its test seams. */
    private static final String SPIN_LOCKS = "se.deversity.asynctest.telemetry.SpinLocks";

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

        // Initialised before the attach on purpose: their handles/updaters are bound by type
        // initializers that have already run, so only the retransformed call sites can see them.
        PreAttachSpinLockTableBean.initialise();
        PreAttachUpdaterSpinLockTableBean.initialise();
        InheritedUpdaterSpinLockBean.initialise();
        OutsideUpdaterMaker.initialise();
        OutsideUpdaterTargetBean.initialise();
        isolatedBean = loadIsolatedBeforeAttach();
        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    /** {@code IsolatedUpdaterSpinLockBean} as its own child-first loader defined it (#659). */
    private static Class<?> isolatedBean;

    private static final String ISOLATED_BEAN = "com.example.agentfixture.IsolatedUpdaterSpinLockBean";

    /**
     * Loads and initialises the isolated fixture in a loader that defines its own copy of the
     * library, the way a runner with an isolated test classloader does.
     */
    private static Class<?> loadIsolatedBeforeAttach() {
        try {
            URL library = TelemetryRegistry.class.getProtectionDomain().getCodeSource().getLocation();
            URL fixtures = SpinLockWeavingTest.class.getProtectionDomain().getCodeSource().getLocation();
            ClassLoader loader = new ChildFirstLoader(new URL[] {library, fixtures},
                    SpinLockWeavingTest.class.getClassLoader());
            return Class.forName(ISOLATED_BEAN, true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Defines the library and the isolated fixture itself, and delegates everything else. */
    private static final class ChildFirstLoader extends URLClassLoader {

        ChildFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean own = ISOLATED_BEAN.equals(name)
                    || (name.startsWith("se.deversity.asynctest.") && !name.startsWith("se.deversity.asynctest.agent."));
            if (!own) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException e) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Runs {@code work} 200 times on each of two threads with the bridge active, then analyzes. */
    private static AtomicityValidator.AtomicityReport drive(Runnable work) throws Exception {
        return driveTwin(() -> {
            work.run();
            return true;
        });
    }

    /**
     * Runs a twin the way {@link #drive(Runnable)} does, except that each worker keeps going past
     * 200 calls until one of its calls took the spinlock, which is when {@code work} returns
     * {@code true}. A twin's finding is two threads writing after the release, and a worker that
     * lost all 200 attempts wrote nothing: on JDK 21 a stall with the lock held did exactly that,
     * and the single-writer run, correctly quiet, failed the twin without saying why.
     */
    private static AtomicityValidator.AtomicityReport driveTwin(BooleanSupplier work) throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger winners = new AtomicInteger();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    boolean won = false;
                    for (int i = 0; i < 200 || !won && System.nanoTime() < deadline; i++) {
                        won |= work.getAsBoolean();
                    }
                    if (won) {
                        winners.incrementAndGet();
                    }
                    done.countDown();
                }, "spin-lock-worker-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            assertEquals(2, winners.get(), "both workers must take the spinlock at least once, "
                    + "or the run cannot show two threads writing");
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
    @DisplayName("a VarHandle spinlock released by getAndSet guards nothing after it")
    void varHandleSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        SpinLockTableBean bean = new SpinLockTableBean();
        AtomicityValidator.AtomicityReport report =
                driveTwin(bean::growThenWriteAfterUnobservedRelease);

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
    @DisplayName("an updater spinlock released by getAndSet guards nothing after it")
    void updaterSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new UpdaterSpinLockTableBean()::growThenWriteAfterUnobservedRelease));
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
    @DisplayName("an AtomicBoolean spinlock released by compareAndExchange guards nothing after it")
    void atomicBooleanSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new AtomicSpinLockTableBean()::growBooleanThenWriteAfterUnobservedRelease));
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released by decrementAndGet guards nothing after it")
    void atomicIntegerSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new AtomicSpinLockTableBean()::growIntegerThenWriteAfterUnobservedRelease));
    }

    // ---- A VarHandle bound before the agent attached (#558) -------------------------------------

    @Test
    @DisplayName("a VarHandle spinlock bound before the agent attached still guards the table")
    void preAttachVarHandleSpinLockGuardsTheTable() throws Exception {
        assertQuiet(drive(new PreAttachSpinLockTableBean()::growReleasedByWrite),
                "the handle's type initializer ran before the attach, so the field it reaches must be "
                        + "resolved from the handle itself");
    }

    @Test
    @DisplayName("an AtomicIntegerFieldUpdater spinlock bound before the agent attached still guards the table (#619)")
    void preAttachUpdaterSpinLockGuardsTheTable() throws Exception {
        assertQuiet(drive(new PreAttachUpdaterSpinLockTableBean()::growReleasedByWrite),
                "the updater's type initializer ran before the attach, so the field it reaches must be "
                        + "resolved from the updater's own target class and offset (#659)");
    }

    @Test
    @DisplayName("a pre-attach updater spinlock released by getAndSet guards nothing after it (#619)")
    void preAttachUpdaterSpinLockWithUnobservedReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new PreAttachUpdaterSpinLockTableBean()::growThenWriteAfterUnobservedRelease));
    }

    @Test
    @DisplayName("an updater bound in a superclass the weaver never scanned locks its own field, never the subclass's (#619, #659)")
    void updaterFromAnUnscannedSuperclassIsNotResolvedToTheSubclassField() throws Exception {
        InheritedUpdaterSpinLockBean bean = new InheritedUpdaterSpinLockBean();
        String state = "com.example.unwovenfixture.UnwovenUpdaterBase.state";
        String busy = InheritedUpdaterSpinLockBean.class.getName() + ".busy";
        try {
            assertTrue(bean.acquireState());
            assertNull(fieldLockFor(bean, busy),
                    "STATE is bound in UnwovenUpdaterBase, which the weaver never scanned, and swaps "
                            + "state, not busy. A lock on busy would be the wrong flag in the lockset "
                            + "and can excuse a race");
            Object stateLock = fieldLockFor(bean, state);
            assertNotNull(stateLock, "STATE names its own target class and offset, which is the field "
                    + "it swaps whatever the weaver scanned (#659); JDK superclasses resolve the same way");
            bean.releaseState();

            assertTrue(bean.acquireBusy());
            Object busyLock = fieldLockFor(bean, busy);
            assertNotNull(busyLock, "BUSY resolves to busy from the updater itself");
            assertNotSame(stateLock, busyLock, "two flags are two locks");
            bean.releaseBusy();
        } finally {
            HeldLocks.clear();
        }
    }

    @Test
    @DisplayName("an updater an unscanned class makes on a woven receiver's field locks that field, not the receiver's own flag (#659)")
    void updaterMadeOutsideTheReceiversHierarchyLocksItsOwnField() throws Exception {
        OutsideUpdaterTargetBean bean = new OutsideUpdaterTargetBean();
        String state = OutsideUpdaterTargetBean.class.getName() + ".state";
        String busy = OutsideUpdaterTargetBean.class.getName() + ".busy";
        try {
            assertTrue(bean.acquireOutside());
            assertNull(fieldLockFor(bean, busy),
                    "OutsideUpdaterMaker.STATE swaps state; the receiver's only recorded updater field is "
                            + "busy, and naming it for this swap would declare a lock on a flag nobody "
                            + "took, shared with every real busy holder");
            assertNotNull(fieldLockFor(bean, state),
                    "the swap is resolved from the updater's own target class and offset");
            bean.releaseOutside();
        } finally {
            HeldLocks.clear();
        }
    }

    @Test
    @DisplayName("a pre-attach updater under an isolated classloader resolves in that loader's registry copy (#659)")
    void preAttachUpdaterUnderAnIsolatedLoaderResolvesInThatLoadersRegistry() throws Exception {
        ClassLoader loader = isolatedBean.getClassLoader();
        Class<?> isolatedHeldLocks = Class.forName("se.deversity.asynctest.diagnostics.HeldLocks", true, loader);
        assertNotSame(HeldLocks.class, isolatedHeldLocks,
                "the fixture must reach its own copy of the library, or this proves nothing");
        Method anyHeld = isolatedHeldLocks.getMethod("anyHeld");
        Object bean = isolatedBean.getConstructor().newInstance();
        try {
            assertTrue((boolean) isolatedBean.getMethod("acquire").invoke(bean));
            assertTrue((boolean) anyHeld.invoke(null),
                    "the updater was bound before the attach, in a loader whose registry copy the "
                            + "agent's weave-time records never reach; resolution has to happen in the "
                            + "copy the woven call site calls");
            assertTrue((boolean) isolatedBean.getMethod("release").invoke(bean));
            assertFalse((boolean) anyHeld.invoke(null), "the swap back releases it");
        } finally {
            isolatedHeldLocks.getMethod("clear").invoke(null);
        }
    }

    private static Object fieldLockFor(Object receiver, String field) throws ReflectiveOperationException {
        return lockFor(new Class<?>[] {Object.class, String.class}, receiver, field);
    }

    // ---- Releases through value-returning forms (#658) -----------------------------------------

    @Test
    @DisplayName("an AtomicInteger spinlock released by decrementAndGet guards the table (#658)")
    void atomicIntegerSpinLockReleasedByDecrementAndGetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growIntegerReleasedByDecrementAndGet),
                "intBusy.compareAndSet(0, 1), released by intBusy.decrementAndGet()");
    }

    @Test
    @DisplayName("an AtomicBoolean spinlock released by compareAndExchange guards the table (#658)")
    void atomicBooleanSpinLockReleasedByCompareAndExchangeGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growBooleanReleasedByCompareAndExchange),
                "busy.compareAndSet(false, true), released by busy.compareAndExchange(true, false)");
    }

    @Test
    @DisplayName("an updater spinlock released by getAndSet guards the table (#658)")
    void updaterSpinLockReleasedByGetAndSetGuardsTheTable() throws Exception {
        assertQuiet(drive(new UpdaterSpinLockTableBean()::growReleasedByGetAndSet),
                "AtomicIntegerFieldUpdater.compareAndSet(this, 0, 1), released by getAndSet(this, 0)");
    }

    @Test
    @DisplayName("a VarHandle spinlock released by a getAndSet statement guards the table (#658)")
    void varHandleSpinLockReleasedByGetAndSetStatementGuardsTheTable() throws Exception {
        assertQuiet(drive(new SpinLockTableBean()::growReleasedByGetAndSetStatement),
                "VarHandle.compareAndSet(this, 0, 1), released by a void getAndSet(this, 0)");
    }

    // ---- Releases woven since #667 --------------------------------------------------------------

    @Test
    @DisplayName("an AtomicBoolean spinlock released by setPlain guards the table (#667)")
    void atomicBooleanSpinLockReleasedBySetPlainGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growBooleanReleasedBySetPlain),
                "AtomicBoolean.compareAndSet(false, true), released by setPlain(false)");
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released by updateAndGet guards the table (#667)")
    void atomicIntegerSpinLockReleasedByUpdateAndGetGuardsTheTable() throws Exception {
        assertQuiet(drive(new AtomicSpinLockTableBean()::growIntegerReleasedByUpdateAndGet),
                "AtomicInteger.compareAndSet(0, 1), released by updateAndGet(held -> 0)");
    }

    @Test
    @DisplayName("an updater spinlock released by getAndUpdate guards the table (#667)")
    void updaterSpinLockReleasedByGetAndUpdateGuardsTheTable() throws Exception {
        assertQuiet(drive(new UpdaterSpinLockTableBean()::growReleasedByGetAndUpdate),
                "AtomicIntegerFieldUpdater.compareAndSet(this, 0, 1), released by getAndUpdate");
    }

    @Test
    @DisplayName("a VarHandle spinlock released by getAndSetRelease guards the table (#667)")
    void varHandleSpinLockReleasedByGetAndSetReleaseGuardsTheTable() throws Exception {
        assertQuiet(drive(new SpinLockTableBean()::growReleasedByGetAndSetRelease),
                "VarHandle.compareAndSet(this, 0, 1), released by a void getAndSetRelease(this, 0)");
    }

    @Test
    @DisplayName("a pre-attach updater spinlock released by getAndUpdate guards the table (#667)")
    void preAttachUpdaterSpinLockReleasedByGetAndUpdateGuardsTheTable() throws Exception {
        assertQuiet(drive(new PreAttachUpdaterSpinLockTableBean()::growReleasedByGetAndUpdate),
                "a pre-attach AtomicIntegerFieldUpdater, released by getAndUpdate");
    }

    @Test
    @DisplayName("an AtomicBoolean spinlock released by setPlain guards nothing after it")
    void atomicBooleanSpinLockReleasedBySetPlainDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new AtomicSpinLockTableBean()::growBooleanThenWriteAfterSetPlain));
    }

    @Test
    @DisplayName("an AtomicInteger spinlock released by updateAndGet guards nothing after it")
    void atomicIntegerSpinLockReleasedByUpdateAndGetDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new AtomicSpinLockTableBean()::growIntegerThenWriteAfterUpdateAndGet));
    }

    @Test
    @DisplayName("an updater spinlock released by getAndUpdate guards nothing after it")
    void updaterSpinLockReleasedByGetAndUpdateDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new UpdaterSpinLockTableBean()::growThenWriteAfterGetAndUpdate));
    }

    @Test
    @DisplayName("a VarHandle spinlock released by getAndSetRelease guards nothing after it")
    void varHandleSpinLockReleasedByGetAndSetReleaseDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new SpinLockTableBean()::growThenWriteAfterGetAndSetRelease));
    }

    @Test
    @DisplayName("a pre-attach updater spinlock released by getAndUpdate guards nothing after it")
    void preAttachUpdaterSpinLockReleasedByGetAndUpdateDoesNotExcuseLaterWrites() throws Exception {
        assertUnobservedReleaseReported(
                driveTwin(new PreAttachUpdaterSpinLockTableBean()::growThenWriteAfterGetAndUpdate));
    }

    // ---- A woven release between a contender's check and its swap (#658) -----------------------

    @Test
    @DisplayName("a woven AtomicInteger.decrementAndGet release between a contender's check and its swap revokes the holder (#658)")
    void wovenDecrementAndGetReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("AtomicInteger.decrementAndGet()",
                bean::acquireInteger, bean::releaseIntegerByDecrementAndGet,
                () -> lockFor(new Class<?>[] {Object.class}, bean.intLock()));
    }

    @Test
    @DisplayName("a woven VarHandle.getAndSet statement release between a contender's check and its swap revokes the holder (#658)")
    void wovenVarHandleGetAndSetReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("VarHandle.getAndSet(this, 0)",
                bean::acquireHandle, bean::releaseHandleByGetAndSet,
                () -> lockFor(new Class<?>[] {Object.class, String.class}, bean,
                        SpinLockHandOffBean.class.getName() + ".busy"));
    }

    @Test
    @DisplayName("a woven AtomicBoolean.setPlain release between a contender's check and its swap revokes the holder (#667)")
    void wovenSetPlainReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("AtomicBoolean.setPlain(false)",
                bean::acquireBoolean, bean::releaseBooleanBySetPlain,
                () -> lockFor(new Class<?>[] {Object.class}, bean.booleanLock()));
    }

    @Test
    @DisplayName("a woven AtomicInteger.getAndUpdate release between a contender's check and its swap revokes the holder (#667)")
    void wovenGetAndUpdateReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("AtomicInteger.getAndUpdate(held -> 0)",
                bean::acquireInteger, bean::releaseIntegerByGetAndUpdate,
                () -> lockFor(new Class<?>[] {Object.class}, bean.intLock()));
    }

    @Test
    @DisplayName("a woven AtomicInteger.accumulateAndGet release between a contender's check and its swap revokes the holder (#667)")
    void wovenAccumulateAndGetReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("AtomicInteger.accumulateAndGet(0, (held, zero) -> zero)",
                bean::acquireInteger, bean::releaseIntegerByAccumulateAndGet,
                () -> lockFor(new Class<?>[] {Object.class}, bean.intLock()));
    }

    @Test
    @DisplayName("a woven VarHandle.getAndSetRelease statement release between a contender's check and its swap revokes the holder (#667)")
    void wovenGetAndSetReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("VarHandle.getAndSetRelease(this, 0)",
                bean::acquireHandle, bean::releaseHandleByGetAndSetRelease,
                () -> lockFor(new Class<?>[] {Object.class, String.class}, bean,
                        SpinLockHandOffBean.class.getName() + ".busy"));
    }

    @Test
    @DisplayName("a woven VarHandle.getAndBitwiseAnd release between a contender's check and its swap revokes the holder (#667)")
    void wovenGetAndBitwiseAndReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("VarHandle.getAndBitwiseAnd(this, 0)",
                bean::acquireHandle, bean::releaseHandleByGetAndBitwiseAnd,
                () -> lockFor(new Class<?>[] {Object.class, String.class}, bean,
                        SpinLockHandOffBean.class.getName() + ".busy"));
    }

    @Test
    @DisplayName("a woven VarHandle.compareAndExchangeRelease release between a contender's check and its swap revokes the holder (#667)")
    void wovenCompareAndExchangeReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("VarHandle.compareAndExchangeRelease(this, 1, 0)",
                bean::acquireHandle, bean::releaseHandleByCompareAndExchangeRelease,
                () -> lockFor(new Class<?>[] {Object.class, String.class}, bean,
                        SpinLockHandOffBean.class.getName() + ".busy"));
    }

    @Test
    @DisplayName("a woven updater getAndUpdate release between a contender's check and its swap revokes the holder (#667)")
    void wovenUpdaterGetAndUpdateReleaseInTheCheckToSwapWindowRevokesTheHolder() throws Exception {
        SpinLockHandOffBean bean = new SpinLockHandOffBean();
        assertWovenReleaseBetweenCheckAndSwapRevokes("AtomicIntegerFieldUpdater.getAndUpdate(this, held -> 0)",
                bean::acquireUpdater, bean::releaseUpdaterByGetAndUpdate,
                () -> lockFor(new Class<?>[] {Object.class, String.class}, bean,
                        SpinLockHandOffBean.class.getName() + ".updaterBusy"));
    }

    /** A lock lookup through the package-private registry, which only reflection can reach from here. */
    @FunctionalInterface
    private interface LockLookup {
        Object find() throws ReflectiveOperationException;
    }

    private static Object lockFor(Class<?>[] parameters, Object... arguments)
            throws ReflectiveOperationException {
        Method lookup = Class.forName(SPIN_LOCKS).getDeclaredMethod("lockFor", parameters);
        lookup.setAccessible(true);
        return lookup.invoke(null, arguments);
    }

    private static void setSeam(String setter, Runnable hook) throws ReflectiveOperationException {
        Method seam = Class.forName(SPIN_LOCKS).getDeclaredMethod(setter, Runnable.class);
        seam.setAccessible(true);
        seam.invoke(null, hook);
    }

    /**
     * The #658 interleaving through woven call sites: this thread holds, a contender reads the flag
     * locked and pauses before its swap, this thread releases, and the contender pauses again once
     * its swap has landed and before it records itself. In that span this thread must not still
     * hold the spinlock, or its unguarded writes there look guarded.
     */
    private static void assertWovenReleaseBetweenCheckAndSwapRevokes(String form, BooleanSupplier acquire,
            Runnable release, LockLookup lockLookup) throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch swapped = new CountDownLatch(1);
        CountDownLatch verified = new CountDownLatch(1);
        AtomicBoolean contenderWon = new AtomicBoolean();
        Thread contender = new Thread(() -> contenderWon.set(acquire.getAsBoolean()),
                "spin-lock-woven-contender");
        HeldLocks.clear();
        try {
            assertTrue(acquire.getAsBoolean(), "this thread takes the flag through the woven swap");
            HeldLocks.Revocable lock = (HeldLocks.Revocable) lockLookup.find();
            assertTrue(lock != null && HeldLocks.holds(lock),
                    "the woven swap must declare the spinlock before the window can be tested");

            setSeam("setTestHookAfterCheck", () -> pauseOn(contender, checked, released));
            setSeam("setTestHookAfterSwap", () -> pauseOn(contender, swapped, verified));
            contender.start();
            assertTrue(checked.await(5, TimeUnit.SECONDS), "the contender checked the flag while it was held");

            release.run();
            released.countDown();
            assertTrue(swapped.await(5, TimeUnit.SECONDS), "the contender's swap landed after the release");

            boolean stillHeld = lock.stillHeld();
            boolean inLockset = HeldLocks.holds(lock);
            verified.countDown();
            contender.join(5_000);

            assertFalse(stillHeld, "the release through " + form + " is woven, so from the contender's "
                    + "swap until its stamp write the releasing thread must not pass re-confirmation");
            assertFalse(inLockset, "the woven release through " + form + " must leave the lockset");
            assertTrue(contenderWon.get(), "the contender's swap must win once the flag was released");
        } finally {
            released.countDown();
            verified.countDown();
            contender.join(5_000);
            setSeam("setTestHookAfterCheck", null);
            setSeam("setTestHookAfterSwap", null);
            HeldLocks.clear();
        }
    }

    private static void pauseOn(Thread thread, CountDownLatch reached, CountDownLatch resume) {
        if (Thread.currentThread() != thread) {
            return;
        }
        reached.countDown();
        try {
            assertTrue(resume.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
