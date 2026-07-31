package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for every {@link AsyncTestContext} detector accessor — the ~107 static
 * {@code xxxDetector()} / {@code xxxMonitor()} / {@code xxxValidator()} methods — across
 * the three {@code require(...)} outcomes: active context with the detector enabled
 * (happy path), no active context, and active context with the detector disabled.
 *
 * <p>Discovered reflectively so the test stays complete as detectors are added/removed:
 * any public static no-arg method returning a type in the {@code diagnostics} package is
 * treated as an accessor.
 */
class AsyncTestContextAccessorCoverageTest {

    /** All public static no-arg accessors returning a detector/monitor/validator type. */
    private static List<Method> detectorAccessors() {
        List<Method> out = new ArrayList<>();
        for (Method m : AsyncTestContext.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            Package p = m.getReturnType().getPackage();
            if (p != null && p.getName().equals("se.deversity.asynctest.diagnostics")) {
                out.add(m);
            }
        }
        return out;
    }

    @Test
    void accessors_areDiscovered() {
        // Guards the reflective discovery itself: if this drops to ~0 the other two
        // tests would pass vacuously.
        assertTrue(detectorAccessors().size() >= 100,
                "expected the full detector-accessor surface, found " + detectorAccessors().size());
    }

    @AsyncTest(threads = 1, invocations = 1)
    void everyAccessor_returnsNonNull_insideDetectAllContext() throws Exception {
        // Inside @AsyncTest with detectAll (the default), ConcurrencyRunner installs a
        // context in which every detector is enabled, so every accessor must resolve.
        List<String> failures = new ArrayList<>();
        for (Method m : detectorAccessors()) {
            try {
                Object detector = m.invoke(null);
                if (detector == null) failures.add(m.getName() + " returned null");
            } catch (InvocationTargetException e) {
                failures.add(m.getName() + " threw " + e.getCause());
            }
        }
        assertTrue(failures.isEmpty(), "accessors that failed under detectAll: " + failures);
    }

    /** Internal registry-backed accessors: public instance, no args, a diagnostics return. */
    private static List<Method> sharedAccessors() {
        List<Method> out = new ArrayList<>();
        for (Method m : AsyncTestContext.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0 || !m.getName().startsWith("shared")) continue;
            Package p = m.getReturnType().getPackage();
            if (p != null && p.getName().equals("se.deversity.asynctest.diagnostics")) {
                out.add(m);
            }
        }
        return out;
    }

    @AsyncTest(threads = 1, invocations = 1)
    void everySharedAccessor_hasAPublicCounterpart_returningTheSameInstance() throws Exception {
        // The sharedXxx() methods are documented as internal — "public only so
        // Phase1DetectorSet can call it" — and return null instead of throwing when the
        // detector is off. That makes them the wrong thing for a consumer to call, so every
        // detector they reach must also be reachable through a public static accessor.
        //
        // Six detectors (VISIBILITY, LIVELOCKS, RACE_CONDITIONS, THREAD_LOCAL_LEAKS,
        // BUSY_WAITING, INTERRUPT_MISHANDLING) had no such accessor for several releases
        // while exposing recordXxx methods written for a test body to call. Nothing failed,
        // because the older coverage test only inspected the accessors that existed. This
        // one starts from the registry side instead.
        AsyncTestContext ctx = AsyncTestContext.get();
        assertNotNull(ctx, "precondition: a context must be installed");

        List<Method> shared = sharedAccessors();
        assertTrue(shared.size() >= 6,
                "expected the internal registry-backed accessors, found " + shared.size());

        List<String> unreachable = new ArrayList<>();
        for (Method internal : shared) {
            Object viaInternal = internal.invoke(ctx);
            if (viaInternal == null) {
                continue;   // detector disabled for this round; nothing to pair up
            }
            Method publicAccessor = null;
            for (Method candidate : detectorAccessors()) {
                if (candidate.getReturnType().equals(internal.getReturnType())) {
                    publicAccessor = candidate;
                    break;
                }
            }
            if (publicAccessor == null) {
                unreachable.add(internal.getName() + " -> no public static accessor returning "
                        + internal.getReturnType().getSimpleName());
                continue;
            }
            assertSame(viaInternal, publicAccessor.invoke(null),
                    publicAccessor.getName() + "() must hand out the registry's instance, "
                            + "not a second one");
        }

        assertTrue(unreachable.isEmpty(),
                "detectors reachable from the registry but not from the public API: " + unreachable);
    }

    @AsyncTest(threads = 1, invocations = 1)
    void deadlockDetector_isReachable() {
        // DEADLOCKS has no sharedXxx() pair, so the test above cannot see it. Its instance
        // analyze() is the only per-round part of its API — the rest is static.
        assertNotNull(AsyncTestContext.deadlockDetector());
    }

    @Test
    void everyAccessor_throwsIllegalState_whenNoContextActive() throws Exception {
        // Plain @Test → no ConcurrencyRunner, no installed context. Every accessor must
        // route through require() and fail fast rather than NPE or return null.
        assertNotNull(AsyncTestContext.get() == null ? "no-context" : null,
                "precondition: no context should be active");
        List<String> wrong = new ArrayList<>();
        for (Method m : detectorAccessors()) {
            try {
                m.invoke(null);
                wrong.add(m.getName() + " did not throw");
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof IllegalStateException)) {
                    wrong.add(m.getName() + " threw " + e.getCause());
                }
            }
        }
        assertTrue(wrong.isEmpty(), "accessors with wrong no-context behavior: " + wrong);
    }

    @AsyncTest(threads = 1, invocations = 1, preset = Preset.NONE)
    void accessor_throwsDetectorNotActive_whenDisabled() {
        // Context is active (preset = NONE) but every detector field is null, exercising
        // require()'s "detector disabled" branch.
        IllegalStateException ex = null;
        try {
            AsyncTestContext.falseSharingDetector();
        } catch (IllegalStateException e) {
            ex = e;
        }
        assertNotNull(ex, "disabled detector accessor must throw IllegalStateException");
        assertTrue(ex.getMessage().contains("Detector not active"),
                "message should explain the detector is disabled: " + ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    @AsyncTest(threads = 1, invocations = 1)
    void deprecatedMonitorAlias_returnsSameInstance_asRenamedDetectorAccessor() {
        // Several xxxMonitor() accessors were misnamed — they return *Detector instances,
        // not *Monitor instances. Each was kept (now @Deprecated) and given a same-behavior
        // xxxDetector() alias that simply delegates to it. Spot-check that the delegation
        // yields the identical instance, not a second one, for a representative sample.
        assertSame(AsyncTestContext.lockLeakMonitor(), AsyncTestContext.lockLeakDetector());
        assertSame(AsyncTestContext.sharedRandomMonitor(), AsyncTestContext.sharedRandomDetector());
        assertSame(AsyncTestContext.conditionMonitor(), AsyncTestContext.conditionVariableDetector());
        assertSame(AsyncTestContext.nestedMonitorLockoutMonitor(), AsyncTestContext.nestedMonitorLockoutDetector());
        assertSame(AsyncTestContext.cfCommonPoolBlockingMonitor(), AsyncTestContext.cfCommonPoolBlockingDetector());
    }
}
