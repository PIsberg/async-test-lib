package se.deversity.asynctest.telemetry;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.AgentConcurrencyUtilHooks;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.HeldLocks;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link TelemetryRegistry} hook the weaver substitutes for an atomic, updater, VarHandle or
 * stamped-reference call returns what the JDK call it replaces returns, and leaves the subject in
 * the same state (#971).
 *
 * <p>The hooks run only where the agent weaves user code, so the library module's tests called few
 * of them: the mutation run of 2026-10-09 found 497 of {@code TelemetryRegistry}'s mutants with no
 * coverage at all. A hook that stores the wrong argument, returns the wrong value, reads the wrong
 * index or drops the operation changes what every woven program computes. Each case below runs the
 * hook on one fresh subject and the JDK call on a twin, and compares the result and the subject's
 * state afterwards; a compare-and-set runs once where it must win and once where it must lose.
 * The table runs twice: with no test installed, and with an {@code ABAProblemDetector} installed,
 * where the reference hooks perform the operation through the detector's view of the slot (#817).
 */
class TelemetryRegistryHooksMatchTheJdkTest {

    private static final String A = "a";
    private static final String B = "b";
    private static final String C = "c";
    private static final String Z = "z";

    /** A field-holding subject for the VarHandle and updater hooks. */
    static final class Bean {
        volatile int count = 5;
        volatile Object ref = A;

        @Override
        public String toString() {
            return count + "/" + ref;
        }
    }

    static volatile Object staticRef = A;

    private static final VarHandle INT;
    private static final VarHandle REF;
    private static final VarHandle STATIC_REF;
    private static final VarHandle ARRAY = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final AtomicIntegerFieldUpdater<Bean> INT_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Bean.class, "count");
    private static final AtomicReferenceFieldUpdater<Bean, Object> REF_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(Bean.class, Object.class, "ref");

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            INT = lookup.findVarHandle(Bean.class, "count", int.class);
            REF = lookup.findVarHandle(Bean.class, "ref", Object.class);
            STATIC_REF = lookup.findStaticVarHandle(TelemetryRegistryHooksMatchTheJdkTest.class,
                    "staticRef", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** One hook and the JDK call it replaces, each applied to its own fresh subject. */
    private record Case(String name, Supplier<?> fresh, Function<Object, Object> hook,
                        Function<Object, Object> jdk, Function<Object, Object> state) {
    }

    private final List<Case> cases = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void resetSpinLocks() {
        SpinLocks.resetForTesting();
        HeldLocks.clear();
        staticRef = A;
    }

    @Test
    @DisplayName("every atomic, updater, VarHandle and stamped hook matches the JDK with no test installed")
    void everyHookMatchesTheJdkWithNoTestInstalled() {
        assertEveryCaseMatches();
    }

    @Test
    @DisplayName("every hook matches the JDK when an ABAProblemDetector performs the reference operations")
    void everyHookMatchesTheJdkThroughTheAbaDetectorsView() {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectABAProblem(true).build());
        context.markInvocationStart();
        AsyncTestContext.install(context);
        try {
            assertTrue(AgentConcurrencyUtilHooks.abaSlot(new AtomicReference<>(A)) != null,
                    "the ABA detector is installed, so the reference hooks take its path");
            assertEveryCaseMatches();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private void assertEveryCaseMatches() {
        varHandleIntCases();
        intUpdaterCases();
        atomicIntegerCases();
        atomicBooleanCases(false);
        atomicBooleanCases(true);
        atomicReferenceCases();
        referenceUpdaterCases();
        referenceArrayCases();
        referenceHandleCases();
        staticReferenceHandleCases();
        arrayReferenceHandleCases();
        stampedReferenceCases();

        List<String> mismatches = new ArrayList<>();
        for (Case c : cases) {
            Object hooked = c.fresh().get();
            List<Object> viaHook = Arrays.asList(c.hook().apply(hooked), c.state().apply(hooked));
            resetSpinLocks();
            Object direct = c.fresh().get();
            List<Object> viaJdk = Arrays.asList(c.jdk().apply(direct), c.state().apply(direct));
            resetSpinLocks();
            if (!Objects.equals(viaHook, viaJdk)) {
                mismatches.add(c.name() + ": hook gave " + viaHook + ", the JDK gave " + viaJdk);
            }
        }
        assertTrue(cases.size() > 150, "the table shrank to " + cases.size() + " cases");
        assertTrue(mismatches.isEmpty(), mismatches.size() + " hooks differ from the JDK call they"
                + " replace:\n" + String.join("\n", mismatches));
    }

    // ---- registration helpers ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <S> void add(String name, Supplier<S> fresh, Function<S, Object> hook,
                         Function<S, Object> jdk, Function<S, Object> state) {
        cases.add(new Case(name, fresh, (Function<Object, Object>) hook,
                (Function<Object, Object>) jdk, (Function<Object, Object>) state));
    }

    /** A void operation: its result is the state it leaves. */
    private interface Store<S> {
        void apply(S subject);
    }

    private <S> void addStore(String name, Supplier<S> fresh, Store<S> hook, Store<S> jdk,
                              Function<S, Object> state) {
        add(name, fresh, s -> {
            hook.apply(s);
            return null;
        }, s -> {
            jdk.apply(s);
            return null;
        }, state);
    }

    /** A weak compare-and-set may fail spuriously; one whose expected value matches must win in the end. */
    private static boolean untilWon(BooleanSupplier attempt) {
        for (int i = 0; i < 10_000; i++) {
            if (attempt.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ---- int VarHandle -------------------------------------------------------------------------

    private void varHandleIntCases() {
        Supplier<Bean> bean = Bean::new;
        Function<Bean, Object> state = Bean::toString;
        addStore("setInt", bean, b -> TelemetryRegistry.setInt(INT, b, 7), b -> INT.set(b, 7), state);
        addStore("setVolatileInt", bean, b -> TelemetryRegistry.setVolatileInt(INT, b, 7),
                b -> INT.setVolatile(b, 7), state);
        addStore("setReleaseInt", bean, b -> TelemetryRegistry.setReleaseInt(INT, b, 7),
                b -> INT.setRelease(b, 7), state);
        addStore("setOpaqueInt", bean, b -> TelemetryRegistry.setOpaqueInt(INT, b, 7),
                b -> INT.setOpaque(b, 7), state);
        for (int expected : new int[] {5, 4}) {
            String e = "(" + expected + ", 7)";
            add("compareAndSetInt" + e, bean, b -> TelemetryRegistry.compareAndSetInt(INT, b, expected, 7),
                    b -> (boolean) INT.compareAndSet(b, expected, 7), state);
            add("compareAndExchangeInt" + e, bean,
                    b -> TelemetryRegistry.compareAndExchangeInt(INT, b, expected, 7),
                    b -> (int) INT.compareAndExchange(b, expected, 7), state);
            add("compareAndExchangeAcquireInt" + e, bean,
                    b -> TelemetryRegistry.compareAndExchangeAcquireInt(INT, b, expected, 7),
                    b -> (int) INT.compareAndExchangeAcquire(b, expected, 7), state);
            add("compareAndExchangeReleaseInt" + e, bean,
                    b -> TelemetryRegistry.compareAndExchangeReleaseInt(INT, b, expected, 7),
                    b -> (int) INT.compareAndExchangeRelease(b, expected, 7), state);
            add("weakCompareAndSetInt" + e, bean,
                    b -> untilWon(() -> TelemetryRegistry.weakCompareAndSetInt(INT, b, expected, 7)),
                    b -> untilWon(() -> (boolean) INT.weakCompareAndSet(b, expected, 7)), state);
            add("weakCompareAndSetPlainInt" + e, bean,
                    b -> untilWon(() -> TelemetryRegistry.weakCompareAndSetPlainInt(INT, b, expected, 7)),
                    b -> untilWon(() -> (boolean) INT.weakCompareAndSetPlain(b, expected, 7)), state);
            add("weakCompareAndSetAcquireInt" + e, bean,
                    b -> untilWon(() -> TelemetryRegistry.weakCompareAndSetAcquireInt(INT, b, expected, 7)),
                    b -> untilWon(() -> (boolean) INT.weakCompareAndSetAcquire(b, expected, 7)), state);
            add("weakCompareAndSetReleaseInt" + e, bean,
                    b -> untilWon(() -> TelemetryRegistry.weakCompareAndSetReleaseInt(INT, b, expected, 7)),
                    b -> untilWon(() -> (boolean) INT.weakCompareAndSetRelease(b, expected, 7)), state);
        }
        add("getAndSetInt", bean, b -> TelemetryRegistry.getAndSetInt(INT, b, 7),
                b -> (int) INT.getAndSet(b, 7), state);
        add("getAndSetAcquireInt", bean, b -> TelemetryRegistry.getAndSetAcquireInt(INT, b, 7),
                b -> (int) INT.getAndSetAcquire(b, 7), state);
        add("getAndSetReleaseInt", bean, b -> TelemetryRegistry.getAndSetReleaseInt(INT, b, 7),
                b -> (int) INT.getAndSetRelease(b, 7), state);
        add("getAndAddInt", bean, b -> TelemetryRegistry.getAndAddInt(INT, b, 3),
                b -> (int) INT.getAndAdd(b, 3), state);
        add("getAndAddAcquireInt", bean, b -> TelemetryRegistry.getAndAddAcquireInt(INT, b, 3),
                b -> (int) INT.getAndAddAcquire(b, 3), state);
        add("getAndAddReleaseInt", bean, b -> TelemetryRegistry.getAndAddReleaseInt(INT, b, 3),
                b -> (int) INT.getAndAddRelease(b, 3), state);
        add("getAndBitwiseOrInt", bean, b -> TelemetryRegistry.getAndBitwiseOrInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseOr(b, 6), state);
        add("getAndBitwiseOrAcquireInt", bean, b -> TelemetryRegistry.getAndBitwiseOrAcquireInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseOrAcquire(b, 6), state);
        add("getAndBitwiseOrReleaseInt", bean, b -> TelemetryRegistry.getAndBitwiseOrReleaseInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseOrRelease(b, 6), state);
        add("getAndBitwiseAndInt", bean, b -> TelemetryRegistry.getAndBitwiseAndInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseAnd(b, 6), state);
        add("getAndBitwiseAndAcquireInt", bean, b -> TelemetryRegistry.getAndBitwiseAndAcquireInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseAndAcquire(b, 6), state);
        add("getAndBitwiseAndReleaseInt", bean, b -> TelemetryRegistry.getAndBitwiseAndReleaseInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseAndRelease(b, 6), state);
        add("getAndBitwiseXorInt", bean, b -> TelemetryRegistry.getAndBitwiseXorInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseXor(b, 6), state);
        add("getAndBitwiseXorAcquireInt", bean, b -> TelemetryRegistry.getAndBitwiseXorAcquireInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseXorAcquire(b, 6), state);
        add("getAndBitwiseXorReleaseInt", bean, b -> TelemetryRegistry.getAndBitwiseXorReleaseInt(INT, b, 6),
                b -> (int) INT.getAndBitwiseXorRelease(b, 6), state);
    }

    // ---- AtomicIntegerFieldUpdater -------------------------------------------------------------

    private void intUpdaterCases() {
        Supplier<Bean> bean = Bean::new;
        Function<Bean, Object> state = Bean::toString;
        addStore("setIntUpdater", bean, b -> TelemetryRegistry.setIntUpdater(INT_UPDATER, b, 7),
                b -> INT_UPDATER.set(b, 7), state);
        addStore("lazySetIntUpdater", bean, b -> TelemetryRegistry.lazySetIntUpdater(INT_UPDATER, b, 7),
                b -> INT_UPDATER.lazySet(b, 7), state);
        for (int expected : new int[] {5, 4}) {
            String e = "(" + expected + ", 7)";
            add("compareAndSetIntUpdater" + e, bean,
                    b -> TelemetryRegistry.compareAndSetIntUpdater(INT_UPDATER, b, expected, 7),
                    b -> INT_UPDATER.compareAndSet(b, expected, 7), state);
            add("weakCompareAndSetIntUpdater" + e, bean,
                    b -> untilWon(() -> TelemetryRegistry.weakCompareAndSetIntUpdater(INT_UPDATER, b, expected, 7)),
                    b -> untilWon(() -> INT_UPDATER.weakCompareAndSet(b, expected, 7)), state);
        }
        add("getAndSetIntUpdater", bean, b -> TelemetryRegistry.getAndSetIntUpdater(INT_UPDATER, b, 7),
                b -> INT_UPDATER.getAndSet(b, 7), state);
        add("getAndAddIntUpdater", bean, b -> TelemetryRegistry.getAndAddIntUpdater(INT_UPDATER, b, 3),
                b -> INT_UPDATER.getAndAdd(b, 3), state);
        add("addAndGetIntUpdater", bean, b -> TelemetryRegistry.addAndGetIntUpdater(INT_UPDATER, b, 3),
                b -> INT_UPDATER.addAndGet(b, 3), state);
        add("getAndDecrementIntUpdater", bean, b -> TelemetryRegistry.getAndDecrementIntUpdater(INT_UPDATER, b),
                INT_UPDATER::getAndDecrement, state);
        add("decrementAndGetIntUpdater", bean, b -> TelemetryRegistry.decrementAndGetIntUpdater(INT_UPDATER, b),
                INT_UPDATER::decrementAndGet, state);
        add("getAndUpdateIntUpdater", bean,
                b -> TelemetryRegistry.getAndUpdateIntUpdater(INT_UPDATER, b, x -> x * 3),
                b -> INT_UPDATER.getAndUpdate(b, x -> x * 3), state);
        add("updateAndGetIntUpdater", bean,
                b -> TelemetryRegistry.updateAndGetIntUpdater(INT_UPDATER, b, x -> x * 3),
                b -> INT_UPDATER.updateAndGet(b, x -> x * 3), state);
        add("getAndAccumulateIntUpdater", bean,
                b -> TelemetryRegistry.getAndAccumulateIntUpdater(INT_UPDATER, b, 2, (x, y) -> x * 10 + y),
                b -> INT_UPDATER.getAndAccumulate(b, 2, (x, y) -> x * 10 + y), state);
        add("accumulateAndGetIntUpdater", bean,
                b -> TelemetryRegistry.accumulateAndGetIntUpdater(INT_UPDATER, b, 2, (x, y) -> x * 10 + y),
                b -> INT_UPDATER.accumulateAndGet(b, 2, (x, y) -> x * 10 + y), state);
    }

    // ---- AtomicInteger -------------------------------------------------------------------------

    private void atomicIntegerCases() {
        Supplier<AtomicInteger> count = () -> new AtomicInteger(5);
        Function<AtomicInteger, Object> state = AtomicInteger::get;
        addStore("setAtomicInteger", count, a -> TelemetryRegistry.setAtomicInteger(a, 7), a -> a.set(7), state);
        addStore("lazySetAtomicInteger", count, a -> TelemetryRegistry.lazySetAtomicInteger(a, 7),
                a -> a.lazySet(7), state);
        addStore("setPlainAtomicInteger", count, a -> TelemetryRegistry.setPlainAtomicInteger(a, 7),
                a -> a.setPlain(7), state);
        addStore("setOpaqueAtomicInteger", count, a -> TelemetryRegistry.setOpaqueAtomicInteger(a, 7),
                a -> a.setOpaque(7), state);
        addStore("setReleaseAtomicInteger", count, a -> TelemetryRegistry.setReleaseAtomicInteger(a, 7),
                a -> a.setRelease(7), state);
        for (int expected : new int[] {5, 4}) {
            String e = "(" + expected + ", 7)";
            add("compareAndSetAtomicInteger" + e, count,
                    a -> TelemetryRegistry.compareAndSetAtomicInteger(a, expected, 7),
                    a -> a.compareAndSet(expected, 7), state);
            add("compareAndExchangeAtomicInteger" + e, count,
                    a -> TelemetryRegistry.compareAndExchangeAtomicInteger(a, expected, 7),
                    a -> a.compareAndExchange(expected, 7), state);
            add("compareAndExchangeAcquireAtomicInteger" + e, count,
                    a -> TelemetryRegistry.compareAndExchangeAcquireAtomicInteger(a, expected, 7),
                    a -> a.compareAndExchangeAcquire(expected, 7), state);
            add("compareAndExchangeReleaseAtomicInteger" + e, count,
                    a -> TelemetryRegistry.compareAndExchangeReleaseAtomicInteger(a, expected, 7),
                    a -> a.compareAndExchangeRelease(expected, 7), state);
            add("weakCompareAndSetAtomicInteger" + e, count,
                    a -> untilWon(() -> TelemetryRegistry.weakCompareAndSetAtomicInteger(a, expected, 7)),
                    a -> untilWon(() -> a.weakCompareAndSetVolatile(expected, 7)), state);
            add("weakCompareAndSetPlainAtomicInteger" + e, count,
                    a -> untilWon(() -> TelemetryRegistry.weakCompareAndSetPlainAtomicInteger(a, expected, 7)),
                    a -> untilWon(() -> a.weakCompareAndSetPlain(expected, 7)), state);
            add("weakCompareAndSetVolatileAtomicInteger" + e, count,
                    a -> untilWon(() -> TelemetryRegistry.weakCompareAndSetVolatileAtomicInteger(a, expected, 7)),
                    a -> untilWon(() -> a.weakCompareAndSetVolatile(expected, 7)), state);
            add("weakCompareAndSetAcquireAtomicInteger" + e, count,
                    a -> untilWon(() -> TelemetryRegistry.weakCompareAndSetAcquireAtomicInteger(a, expected, 7)),
                    a -> untilWon(() -> a.weakCompareAndSetAcquire(expected, 7)), state);
            add("weakCompareAndSetReleaseAtomicInteger" + e, count,
                    a -> untilWon(() -> TelemetryRegistry.weakCompareAndSetReleaseAtomicInteger(a, expected, 7)),
                    a -> untilWon(() -> a.weakCompareAndSetRelease(expected, 7)), state);
        }
        add("getAndSetAtomicInteger", count, a -> TelemetryRegistry.getAndSetAtomicInteger(a, 7),
                a -> a.getAndSet(7), state);
        add("getAndAddAtomicInteger", count, a -> TelemetryRegistry.getAndAddAtomicInteger(a, 3),
                a -> a.getAndAdd(3), state);
        add("addAndGetAtomicInteger", count, a -> TelemetryRegistry.addAndGetAtomicInteger(a, 3),
                a -> a.addAndGet(3), state);
        add("getAndDecrementAtomicInteger", count, TelemetryRegistry::getAndDecrementAtomicInteger,
                AtomicInteger::getAndDecrement, state);
        add("decrementAndGetAtomicInteger", count, TelemetryRegistry::decrementAndGetAtomicInteger,
                AtomicInteger::decrementAndGet, state);
        add("getAndUpdateAtomicInteger", count, a -> TelemetryRegistry.getAndUpdateAtomicInteger(a, x -> x * 3),
                a -> a.getAndUpdate(x -> x * 3), state);
        add("updateAndGetAtomicInteger", count, a -> TelemetryRegistry.updateAndGetAtomicInteger(a, x -> x * 3),
                a -> a.updateAndGet(x -> x * 3), state);
        add("getAndAccumulateAtomicInteger", count,
                a -> TelemetryRegistry.getAndAccumulateAtomicInteger(a, 2, (x, y) -> x * 10 + y),
                a -> a.getAndAccumulate(2, (x, y) -> x * 10 + y), state);
        add("accumulateAndGetAtomicInteger", count,
                a -> TelemetryRegistry.accumulateAndGetAtomicInteger(a, 2, (x, y) -> x * 10 + y),
                a -> a.accumulateAndGet(2, (x, y) -> x * 10 + y), state);
    }

    // ---- AtomicBoolean -------------------------------------------------------------------------

    /** Every operation from {@code initial}, storing its opposite: a store of false is a release. */
    private void atomicBooleanCases(boolean initial) {
        boolean other = !initial;
        String from = " from " + initial;
        Supplier<AtomicBoolean> flag = () -> new AtomicBoolean(initial);
        Function<AtomicBoolean, Object> state = AtomicBoolean::get;
        addStore("setAtomicBoolean" + from, flag, f -> TelemetryRegistry.setAtomicBoolean(f, other),
                f -> f.set(other), state);
        addStore("lazySetAtomicBoolean" + from, flag, f -> TelemetryRegistry.lazySetAtomicBoolean(f, other),
                f -> f.lazySet(other), state);
        addStore("setPlainAtomicBoolean" + from, flag, f -> TelemetryRegistry.setPlainAtomicBoolean(f, other),
                f -> f.setPlain(other), state);
        addStore("setOpaqueAtomicBoolean" + from, flag, f -> TelemetryRegistry.setOpaqueAtomicBoolean(f, other),
                f -> f.setOpaque(other), state);
        addStore("setReleaseAtomicBoolean" + from, flag, f -> TelemetryRegistry.setReleaseAtomicBoolean(f, other),
                f -> f.setRelease(other), state);
        add("getAndSetAtomicBoolean" + from, flag, f -> TelemetryRegistry.getAndSetAtomicBoolean(f, other),
                f -> f.getAndSet(other), state);
        for (boolean expected : new boolean[] {initial, other}) {
            String e = from + " (" + expected + ", " + other + ")";
            add("compareAndSetAtomicBoolean" + e, flag,
                    f -> TelemetryRegistry.compareAndSetAtomicBoolean(f, expected, other),
                    f -> f.compareAndSet(expected, other), state);
            add("compareAndExchangeAtomicBoolean" + e, flag,
                    f -> TelemetryRegistry.compareAndExchangeAtomicBoolean(f, expected, other),
                    f -> f.compareAndExchange(expected, other), state);
            add("compareAndExchangeAcquireAtomicBoolean" + e, flag,
                    f -> TelemetryRegistry.compareAndExchangeAcquireAtomicBoolean(f, expected, other),
                    f -> f.compareAndExchangeAcquire(expected, other), state);
            add("compareAndExchangeReleaseAtomicBoolean" + e, flag,
                    f -> TelemetryRegistry.compareAndExchangeReleaseAtomicBoolean(f, expected, other),
                    f -> f.compareAndExchangeRelease(expected, other), state);
            add("weakCompareAndSetAtomicBoolean" + e, flag,
                    f -> untilWon(() -> TelemetryRegistry.weakCompareAndSetAtomicBoolean(f, expected, other)),
                    f -> untilWon(() -> f.weakCompareAndSetVolatile(expected, other)), state);
            add("weakCompareAndSetPlainAtomicBoolean" + e, flag,
                    f -> untilWon(() -> TelemetryRegistry.weakCompareAndSetPlainAtomicBoolean(f, expected, other)),
                    f -> untilWon(() -> f.weakCompareAndSetPlain(expected, other)), state);
            add("weakCompareAndSetVolatileAtomicBoolean" + e, flag,
                    f -> untilWon(() -> TelemetryRegistry.weakCompareAndSetVolatileAtomicBoolean(f, expected, other)),
                    f -> untilWon(() -> f.weakCompareAndSetVolatile(expected, other)), state);
            add("weakCompareAndSetAcquireAtomicBoolean" + e, flag,
                    f -> untilWon(() -> TelemetryRegistry.weakCompareAndSetAcquireAtomicBoolean(f, expected, other)),
                    f -> untilWon(() -> f.weakCompareAndSetAcquire(expected, other)), state);
            add("weakCompareAndSetReleaseAtomicBoolean" + e, flag,
                    f -> untilWon(() -> TelemetryRegistry.weakCompareAndSetReleaseAtomicBoolean(f, expected, other)),
                    f -> untilWon(() -> f.weakCompareAndSetRelease(expected, other)), state);
        }
    }

    // ---- AtomicReference -----------------------------------------------------------------------

    private void atomicReferenceCases() {
        Supplier<AtomicReference<Object>> slot = () -> new AtomicReference<>(A);
        Function<AtomicReference<Object>, Object> state = AtomicReference::get;
        addStore("setAtomicReference", slot, s -> TelemetryRegistry.setAtomicReference(s, B), s -> s.set(B), state);
        addStore("lazySetAtomicReference", slot, s -> TelemetryRegistry.lazySetAtomicReference(s, B),
                s -> s.lazySet(B), state);
        addStore("setReleaseAtomicReference", slot, s -> TelemetryRegistry.setReleaseAtomicReference(s, B),
                s -> s.setRelease(B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetAtomicReference(" + expected + ", c)", slot,
                    s -> TelemetryRegistry.compareAndSetAtomicReference(s, expected, C),
                    s -> s.compareAndSet(expected, C), state);
        }
        add("getAndSetAtomicReference", slot, s -> TelemetryRegistry.getAndSetAtomicReference(s, B),
                s -> s.getAndSet(B), state);
        add("getAtomicReference", slot, TelemetryRegistry::getAtomicReference, AtomicReference::get, state);
        add("getAcquireAtomicReference", slot, TelemetryRegistry::getAcquireAtomicReference,
                AtomicReference::getAcquire, state);
    }

    // ---- AtomicReferenceFieldUpdater -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static AtomicReferenceFieldUpdater<Object, Object> erasedRefUpdater() {
        return (AtomicReferenceFieldUpdater<Object, Object>) (AtomicReferenceFieldUpdater<?, ?>) REF_UPDATER;
    }

    private void referenceUpdaterCases() {
        AtomicReferenceFieldUpdater<Object, Object> updater = erasedRefUpdater();
        Supplier<Bean> bean = Bean::new;
        Function<Bean, Object> state = Bean::toString;
        addStore("setReferenceUpdater", bean, b -> TelemetryRegistry.setReferenceUpdater(updater, b, B),
                b -> REF_UPDATER.set(b, B), state);
        addStore("lazySetReferenceUpdater", bean, b -> TelemetryRegistry.lazySetReferenceUpdater(updater, b, B),
                b -> REF_UPDATER.lazySet(b, B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetReferenceUpdater(" + expected + ", c)", bean,
                    b -> TelemetryRegistry.compareAndSetReferenceUpdater(updater, b, expected, C),
                    b -> REF_UPDATER.compareAndSet(b, expected, C), state);
        }
        add("getAndSetReferenceUpdater", bean, b -> TelemetryRegistry.getAndSetReferenceUpdater(updater, b, B),
                b -> REF_UPDATER.getAndSet(b, B), state);
        add("getReferenceUpdater", bean, b -> TelemetryRegistry.getReferenceUpdater(updater, b),
                REF_UPDATER::get, state);
    }

    // ---- AtomicReferenceArray ------------------------------------------------------------------

    private void referenceArrayCases() {
        // Index 1 holds A and index 0 something else, so a hook reading or writing the wrong index shows.
        Supplier<AtomicReferenceArray<Object>> slots = () -> new AtomicReferenceArray<>(new Object[] {Z, A});
        Function<AtomicReferenceArray<Object>, Object> state = AtomicReferenceArray::toString;
        addStore("setReferenceArray", slots, s -> TelemetryRegistry.setReferenceArray(s, 1, B),
                s -> s.set(1, B), state);
        addStore("lazySetReferenceArray", slots, s -> TelemetryRegistry.lazySetReferenceArray(s, 1, B),
                s -> s.lazySet(1, B), state);
        addStore("setReleaseReferenceArray", slots, s -> TelemetryRegistry.setReleaseReferenceArray(s, 1, B),
                s -> s.setRelease(1, B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetReferenceArray(1, " + expected + ", c)", slots,
                    s -> TelemetryRegistry.compareAndSetReferenceArray(s, 1, expected, C),
                    s -> s.compareAndSet(1, expected, C), state);
        }
        add("getAndSetReferenceArray", slots, s -> TelemetryRegistry.getAndSetReferenceArray(s, 1, B),
                s -> s.getAndSet(1, B), state);
        add("getReferenceArray", slots, s -> TelemetryRegistry.getReferenceArray(s, 1), s -> s.get(1), state);
        add("getAcquireReferenceArray", slots, s -> TelemetryRegistry.getAcquireReferenceArray(s, 1),
                s -> s.getAcquire(1), state);
    }

    // ---- reference VarHandles: instance field, static field, array element ---------------------

    private void referenceHandleCases() {
        Supplier<Bean> bean = Bean::new;
        Function<Bean, Object> state = Bean::toString;
        addStore("setReferenceHandle", bean, b -> TelemetryRegistry.setReferenceHandle(REF, b, B),
                b -> REF.set(b, B), state);
        addStore("setVolatileReferenceHandle", bean, b -> TelemetryRegistry.setVolatileReferenceHandle(REF, b, B),
                b -> REF.setVolatile(b, B), state);
        addStore("setReleaseReferenceHandle", bean, b -> TelemetryRegistry.setReleaseReferenceHandle(REF, b, B),
                b -> REF.setRelease(b, B), state);
        addStore("setOpaqueReferenceHandle", bean, b -> TelemetryRegistry.setOpaqueReferenceHandle(REF, b, B),
                b -> REF.setOpaque(b, B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetReferenceHandle(" + expected + ", c)", bean,
                    b -> TelemetryRegistry.compareAndSetReferenceHandle(REF, b, expected, C),
                    b -> (boolean) REF.compareAndSet(b, expected, C), state);
        }
        add("getAndSetReferenceHandle", bean, b -> TelemetryRegistry.getAndSetReferenceHandle(REF, b, B),
                b -> (Object) REF.getAndSet(b, B), state);
        add("getVolatileReferenceHandle", bean, b -> TelemetryRegistry.getVolatileReferenceHandle(REF, b),
                b -> (Object) REF.getVolatile(b), state);
        add("getAcquireReferenceHandle", bean, b -> TelemetryRegistry.getAcquireReferenceHandle(REF, b),
                b -> (Object) REF.getAcquire(b), state);
    }

    private void staticReferenceHandleCases() {
        // The subject is the static field itself; fresh() puts it back before each side runs.
        Supplier<Object> field = () -> {
            staticRef = A;
            return STATIC_REF;
        };
        Function<Object, Object> state = h -> staticRef;
        addStore("setStaticReferenceHandle", field, h -> TelemetryRegistry.setStaticReferenceHandle(STATIC_REF, B),
                h -> STATIC_REF.set(B), state);
        addStore("setVolatileStaticReferenceHandle", field,
                h -> TelemetryRegistry.setVolatileStaticReferenceHandle(STATIC_REF, B),
                h -> STATIC_REF.setVolatile(B), state);
        addStore("setReleaseStaticReferenceHandle", field,
                h -> TelemetryRegistry.setReleaseStaticReferenceHandle(STATIC_REF, B),
                h -> STATIC_REF.setRelease(B), state);
        addStore("setOpaqueStaticReferenceHandle", field,
                h -> TelemetryRegistry.setOpaqueStaticReferenceHandle(STATIC_REF, B),
                h -> STATIC_REF.setOpaque(B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetStaticReferenceHandle(" + expected + ", c)", field,
                    h -> TelemetryRegistry.compareAndSetStaticReferenceHandle(STATIC_REF, expected, C),
                    h -> (boolean) STATIC_REF.compareAndSet(expected, C), state);
        }
        add("getAndSetStaticReferenceHandle", field,
                h -> TelemetryRegistry.getAndSetStaticReferenceHandle(STATIC_REF, B),
                h -> (Object) STATIC_REF.getAndSet(B), state);
        add("getVolatileStaticReferenceHandle", field,
                h -> TelemetryRegistry.getVolatileStaticReferenceHandle(STATIC_REF),
                h -> (Object) STATIC_REF.getVolatile(), state);
        add("getAcquireStaticReferenceHandle", field,
                h -> TelemetryRegistry.getAcquireStaticReferenceHandle(STATIC_REF),
                h -> (Object) STATIC_REF.getAcquire(), state);
    }

    private void arrayReferenceHandleCases() {
        Supplier<Object[]> array = () -> new Object[] {Z, A};
        Function<Object[], Object> state = Arrays::toString;
        addStore("setArrayReferenceHandle", array, a -> TelemetryRegistry.setArrayReferenceHandle(ARRAY, a, 1, B),
                a -> ARRAY.set(a, 1, B), state);
        addStore("setVolatileArrayReferenceHandle", array,
                a -> TelemetryRegistry.setVolatileArrayReferenceHandle(ARRAY, a, 1, B),
                a -> ARRAY.setVolatile(a, 1, B), state);
        addStore("setReleaseArrayReferenceHandle", array,
                a -> TelemetryRegistry.setReleaseArrayReferenceHandle(ARRAY, a, 1, B),
                a -> ARRAY.setRelease(a, 1, B), state);
        addStore("setOpaqueArrayReferenceHandle", array,
                a -> TelemetryRegistry.setOpaqueArrayReferenceHandle(ARRAY, a, 1, B),
                a -> ARRAY.setOpaque(a, 1, B), state);
        for (Object expected : new Object[] {A, B}) {
            add("compareAndSetArrayReferenceHandle(1, " + expected + ", c)", array,
                    a -> TelemetryRegistry.compareAndSetArrayReferenceHandle(ARRAY, a, 1, expected, C),
                    a -> (boolean) ARRAY.compareAndSet(a, 1, expected, C), state);
        }
        add("getAndSetArrayReferenceHandle", array,
                a -> TelemetryRegistry.getAndSetArrayReferenceHandle(ARRAY, a, 1, B),
                a -> (Object) ARRAY.getAndSet(a, 1, B), state);
        add("getVolatileArrayReferenceHandle", array,
                a -> TelemetryRegistry.getVolatileArrayReferenceHandle(ARRAY, a, 1),
                a -> (Object) ARRAY.getVolatile(a, 1), state);
        add("getAcquireArrayReferenceHandle", array,
                a -> TelemetryRegistry.getAcquireArrayReferenceHandle(ARRAY, a, 1),
                a -> (Object) ARRAY.getAcquire(a, 1), state);
    }

    // ---- AtomicStampedReference ----------------------------------------------------------------

    private void stampedReferenceCases() {
        Supplier<AtomicStampedReference<Object>> slot = () -> new AtomicStampedReference<>(A, 3);
        Function<AtomicStampedReference<Object>, Object> state = s -> s.getReference() + "#" + s.getStamp();
        add("getStampedReference", slot, s -> {
            int[] stamp = new int[1];
            return TelemetryRegistry.getStampedReference(s, stamp) + "#" + stamp[0];
        }, s -> {
            int[] stamp = new int[1];
            return s.get(stamp) + "#" + stamp[0];
        }, state);
        add("getReferenceStampedReference", slot, TelemetryRegistry::getReferenceStampedReference,
                AtomicStampedReference::getReference, state);
        addStore("setStampedReference", slot, s -> TelemetryRegistry.setStampedReference(s, B, 4),
                s -> s.set(B, 4), state);
        // Each swap once where reference and stamp both match, and once for each that does not.
        Object[][] swaps = {{A, 3}, {B, 3}, {A, 2}};
        for (Object[] swap : swaps) {
            Object expected = swap[0];
            int stamp = (Integer) swap[1];
            String e = "(" + expected + ", c, " + stamp + ", 4)";
            add("compareAndSetStampedReference" + e, slot,
                    s -> TelemetryRegistry.compareAndSetStampedReference(s, expected, C, stamp, 4),
                    s -> s.compareAndSet(expected, C, stamp, 4), state);
            add("weakCompareAndSetStampedReference" + e, slot,
                    s -> untilWon(() -> TelemetryRegistry.weakCompareAndSetStampedReference(s, expected, C, stamp, 4)),
                    s -> untilWon(() -> s.weakCompareAndSet(expected, C, stamp, 4)), state);
        }
        for (Object expected : new Object[] {A, B}) {
            add("attemptStampStampedReference(" + expected + ", 9)", slot,
                    s -> TelemetryRegistry.attemptStampStampedReference(s, expected, 9),
                    s -> s.attemptStamp(expected, 9), state);
        }
    }
}
