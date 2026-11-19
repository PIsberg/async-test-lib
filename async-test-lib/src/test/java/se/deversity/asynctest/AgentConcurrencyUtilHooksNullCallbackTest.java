package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A woven stream, {@code ConcurrentHashMap.forEach*} or {@code Arrays.parallelSetAll} call throws
 * what the JDK call throws when handed a null callback, inside a run as outside one (#1415).
 *
 * <p>The JDK checks its callbacks on entry, so {@code stream.map(null)} throws at once and
 * {@code Stream.empty().forEach(null)} throws although nothing would run. Inside a run the hooks
 * wrap the callback in a lambda of their own before calling the JDK, which then sees a non-null
 * argument: the null surfaced only when an element ran it, or never, for an empty source or a
 * pipeline with no terminal operation. Outside a run every hook hands its arguments to the JDK as
 * they are, which makes it the oracle here: every hook of these families, every callback null, an
 * empty source, called both ways.
 */
class AgentConcurrencyUtilHooksNullCallbackTest {

    private static final Pattern FAMILIES = Pattern.compile(
            "(stream|intStream|longStream|doubleStream|concurrentMapForEach|arraysParallelSetAll).*");

    @AfterEach
    void leaveTheRun() {
        AsyncTestContext.uninstall();
        TelemetryRegistry.stop();
    }

    @TestFactory
    List<DynamicTest> aNullCallbackThrowsWhatTheJdkThrows() {
        List<DynamicTest> tests = new ArrayList<>();
        Arrays.stream(AgentConcurrencyUtilHooks.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .filter(m -> FAMILIES.matcher(m.getName()).matches())
                .filter(m -> Arrays.stream(m.getParameterTypes()).anyMatch(
                        AgentConcurrencyUtilHooksNullCallbackTest::isCallback))
                .sorted(Comparator.comparing(Method::toGenericString))
                .forEach(hook -> tests.add(DynamicTest.dynamicTest(signature(hook), () -> {
                    String outside = outcome(hook);
                    TelemetryRegistry.start();
                    AsyncTestContext.install(new AsyncTestContext(
                            AsyncTestConfig.builder().detectAll(true).build()));
                    String inside;
                    try {
                        inside = outcome(hook);
                    } finally {
                        AsyncTestContext.uninstall();
                        TelemetryRegistry.stop();
                    }
                    assertEquals(outside, inside, "the woven call must throw what the JDK call throws");
                })));
        assertFalse(tests.isEmpty(), "no hook matched: the families were renamed");
        return tests;
    }

    private static boolean isCallback(Class<?> type) {
        return type.isInterface()
                && (type.isAnnotationPresent(FunctionalInterface.class) || type == Collector.class);
    }

    /** {@return the exception the hook threw with every callback null, or "returned"} */
    private static String outcome(Method hook) throws IllegalAccessException {
        Class<?>[] types = hook.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = emptyValueOf(types[i]);
        }
        try {
            hook.invoke(null, args);
            return "returned";
        } catch (InvocationTargetException e) {
            return e.getCause().getClass().getName();
        }
    }

    private static Object emptyValueOf(Class<?> type) {
        if (isCallback(type)) {
            return null;
        }
        if (type == Stream.class) {
            return Stream.empty().parallel();
        }
        if (type == IntStream.class) {
            return IntStream.empty().parallel();
        }
        if (type == LongStream.class) {
            return LongStream.empty().parallel();
        }
        if (type == DoubleStream.class) {
            return DoubleStream.empty().parallel();
        }
        if (type == ConcurrentHashMap.class) {
            return new ConcurrentHashMap<>();
        }
        if (type.isArray()) {
            return java.lang.reflect.Array.newInstance(type.getComponentType(), 0);
        }
        if (type == long.class) {
            return 1L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == boolean.class) {
            return false;
        }
        return null;
    }

    private static String signature(Method hook) {
        return hook.getName() + Arrays.stream(hook.getParameterTypes()).map(Class::getSimpleName)
                .toList().toString().replace('[', '(').replace(']', ')');
    }
}
