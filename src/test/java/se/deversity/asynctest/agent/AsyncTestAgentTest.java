package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncTestAgentTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        TelemetryRegistry.stop();
    }

    @Test
    void testWriteAccessAdviceDirectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            recorded.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        // Trigger the write advice enter method directly with a pre-combined identifier.
        AsyncTestAgent.WriteAccessAdvice.enter("TestClass.setCount");

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Direct advice enter did not trigger registry callback");
        assertEquals(List.of("TestClass.setCount:true"), recorded);
    }

    @Test
    void testReadAccessAdviceDirectly() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            recorded.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        // Trigger the read advice enter method directly with a pre-combined identifier.
        AsyncTestAgent.ReadAccessAdvice.enter("TestClass.getCount");

        assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "Direct advice enter did not trigger registry callback");
        assertEquals(List.of("TestClass.getCount:false"), recorded);
    }

    @Test
    void testDynamicByteBuddyInstrumentation() throws Exception {
        // Two events expected: one from the getter, one from the setter.
        CountDownLatch latch = new CountDownLatch(2);
        List<String> recorded = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            recorded.add(targetField + ":" + isWrite);
            latch.countDown();
        });

        // Use Byte Buddy to dynamically construct a bean with a real backing field and a
        // genuine getter/setter, then weave BOTH split advices via isGetter()/isSetter().
        Class<?> dynamicType = new ByteBuddy()
                .subclass(Object.class)
                .name("se.deversity.asynctest.agent.DynamicBean")
                .defineField("value", int.class, Visibility.PRIVATE)
                .defineMethod("getValue", int.class, Visibility.PUBLIC)
                .intercept(FieldAccessor.ofField("value"))
                .defineMethod("setValue", void.class, Visibility.PUBLIC)
                .withParameters(int.class)
                .intercept(FieldAccessor.ofField("value"))
                .visit(Advice.to(AsyncTestAgent.ReadAccessAdvice.class)
                        .on(ElementMatchers.isGetter()))
                .visit(Advice.to(AsyncTestAgent.WriteAccessAdvice.class)
                        .on(ElementMatchers.isSetter()))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        // Instantiate and drive real field access through the instrumented accessors.
        Object instance = dynamicType.getDeclaredConstructor().newInstance();
        dynamicType.getMethod("setValue", int.class).invoke(instance, 42);
        int read = (int) dynamicType.getMethod("getValue").invoke(instance);
        assertEquals(42, read, "instrumentation must not alter getter/setter semantics");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Instrumented accessor calls were not captured by telemetry");
        assertEquals(2, recorded.size());
        assertTrue(recorded.contains("se.deversity.asynctest.agent.DynamicBean.setValue:true"),
                "setter must record a write access with the combined identifier; got " + recorded);
        assertTrue(recorded.contains("se.deversity.asynctest.agent.DynamicBean.getValue:false"),
                "getter must record a read access with the combined identifier; got " + recorded);
    }

    @Test
    void testOriginIdentifierIsInternedConstant() throws Exception {
        // Two invocations of the same instrumented method must publish the SAME String
        // reference: @Advice.Origin bakes the identifier into the constant pool (ldc), so
        // no per-call allocation occurs on the hot path.
        CountDownLatch latch = new CountDownLatch(2);
        List<String> rawIdentifiers = Collections.synchronizedList(new ArrayList<>());

        TelemetryRegistry.start((tid, targetField, isWrite) -> {
            rawIdentifiers.add(targetField);
            latch.countDown();
        });

        Class<?> dynamicType = new ByteBuddy()
                .subclass(Object.class)
                .name("se.deversity.asynctest.agent.InternTarget")
                .defineField("value", int.class, Visibility.PRIVATE)
                .defineMethod("setValue", void.class, Visibility.PUBLIC)
                .withParameters(int.class)
                .intercept(FieldAccessor.ofField("value"))
                .visit(Advice.to(AsyncTestAgent.WriteAccessAdvice.class)
                        .on(ElementMatchers.isSetter()))
                .make()
                .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                .getLoaded();

        Object instance = dynamicType.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method setter = dynamicType.getMethod("setValue", int.class);
        setter.invoke(instance, 1);
        setter.invoke(instance, 2);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Both instrumented calls should have been drained");
        assertEquals(2, rawIdentifiers.size());
        assertSame(rawIdentifiers.get(0), rawIdentifiers.get(1),
                "the origin identifier must be the same interned constant on every call "
                        + "(proving the hot path allocates no per-call identifier string)");
    }

    @Test
    void ignoreMatcher_ignoresByteBuddyClasses() {
        assertTrue(AsyncTestAgent.ignoreMatcher()
                        .matches(TypeDescription.ForLoadedType.of(ByteBuddy.class)),
                "net.bytebuddy.* types must be ignored to avoid recursive instrumentation");
    }

    @Test
    void ignoreMatcher_ignoresJdkClasses() {
        assertTrue(AsyncTestAgent.ignoreMatcher()
                        .matches(TypeDescription.ForLoadedType.of(String.class)),
                "java.* types must be ignored by name");
    }

    @Test
    void ignoreMatcher_ignoresSyntheticTypes() {
        // A synthetic type whose name is NOT under any ignored prefix, so a match can
        // only come from the isSynthetic() branch.
        TypeDescription syntheticType = new ByteBuddy()
                .subclass(Object.class)
                .name("com.example.app.SyntheticFixture")
                .modifiers(Visibility.PUBLIC, SyntheticState.SYNTHETIC)
                .make()
                .getTypeDescription();

        assertTrue(syntheticType.isSynthetic(), "fixture precondition: type must be synthetic");
        assertTrue(AsyncTestAgent.ignoreMatcher().matches(syntheticType),
                "synthetic types (e.g. lambdas) must be ignored");
    }

    @Test
    void ignoreMatcher_ignoresOwnLibraryClasses() {
        assertTrue(AsyncTestAgent.ignoreMatcher()
                        .matches(TypeDescription.ForLoadedType.of(TelemetryRegistry.class)),
                "the library's own se.deversity.asynctest.* types must be ignored");
    }

    @Test
    void ignoreMatcher_doesNotIgnorePlainAppClass() {
        // A plain, non-synthetic application class outside every ignored prefix.
        TypeDescription appType = new ByteBuddy()
                .subclass(Object.class)
                .name("com.example.app.OrderService")
                .make()
                .getTypeDescription();

        assertFalse(AsyncTestAgent.ignoreMatcher().matches(appType),
                "ordinary application classes must remain instrumentation candidates");
    }

    @Test
    void typeMatcher_emptyIncludes_matchesAnyType() {
        TypeDescription appType = new ByteBuddy()
                .subclass(Object.class)
                .name("org.other.Bar")
                .make()
                .getTypeDescription();

        assertTrue(AsyncTestAgent.typeMatcher(List.of()).matches(appType),
                "empty includes must fall back to any() — every non-ignored type is a candidate");
    }

    @Test
    void typeMatcher_withIncludes_acceptsPrefixMatchAndRejectsOthers() {
        TypeDescription included = new ByteBuddy()
                .subclass(Object.class)
                .name("com.myapp.Foo")
                .make()
                .getTypeDescription();
        TypeDescription excluded = new ByteBuddy()
                .subclass(Object.class)
                .name("org.other.Bar")
                .make()
                .getTypeDescription();

        var matcher = AsyncTestAgent.typeMatcher(List.of("com.myapp"));
        assertTrue(matcher.matches(included), "includes=com.myapp must accept com.myapp.Foo");
        assertFalse(matcher.matches(excluded), "includes=com.myapp must reject org.other.Bar");
    }

    @Test
    void ignoreMatcher_withExcludes_ignoresExcludedPrefixInAdditionToBuiltins() {
        TypeDescription excludedByArg = new ByteBuddy()
                .subclass(Object.class)
                .name("com.myapp.dto.Order")
                .make()
                .getTypeDescription();
        TypeDescription plainApp = new ByteBuddy()
                .subclass(Object.class)
                .name("com.myapp.service.OrderService")
                .make()
                .getTypeDescription();

        var matcher = AsyncTestAgent.ignoreMatcher(List.of("com.myapp.dto"));
        assertTrue(matcher.matches(excludedByArg),
                "excludes=com.myapp.dto must be appended to the ignore matcher");
        assertFalse(matcher.matches(plainApp),
                "types outside the exclude prefix (and built-ins) remain candidates");
        // Built-in exclusions still apply alongside the user-supplied excludes.
        assertTrue(matcher.matches(TypeDescription.ForLoadedType.of(ByteBuddy.class)),
                "built-in net.bytebuddy.* exclusion must survive the excludes composition");
    }

    @Test
    void ignoreMatcher_withEmptyExcludes_equalsBuiltinMatcher() {
        TypeDescription plainApp = new ByteBuddy()
                .subclass(Object.class)
                .name("com.example.app.OrderService")
                .make()
                .getTypeDescription();

        assertFalse(AsyncTestAgent.ignoreMatcher(List.of()).matches(plainApp),
                "empty excludes must yield exactly the built-in ignore behavior");
    }
}
