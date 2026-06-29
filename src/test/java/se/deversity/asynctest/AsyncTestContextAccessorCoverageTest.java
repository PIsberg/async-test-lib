package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
